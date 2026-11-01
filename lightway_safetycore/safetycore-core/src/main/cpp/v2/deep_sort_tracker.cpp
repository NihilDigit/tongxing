#include "deep_sort_tracker.h"
#include "hungarian.h"
#include <algorithm>
#include <cmath>
#include <set>

namespace safetycore_v2 {

namespace {

enum class SemanticGroup {
    Unknown = 0,
    Person,
    Vehicle,
    TwoWheel,
    Structure
};

SemanticGroup groupFromCoco(int classId) {
    switch (classId) {
        case 0:  // person
            return SemanticGroup::Person;
        case 1:  // bicycle
        case 3:  // motorcycle
            return SemanticGroup::TwoWheel;
        case 2:  // car
        case 5:  // bus
        case 7:  // truck
            return SemanticGroup::Vehicle;
        default:
            return SemanticGroup::Unknown;
    }
}

SemanticGroup groupFromCityscapes(int classId) {
    switch (classId) {
        case 11: // person
        case 12: // rider
            return SemanticGroup::Person;
        case 13: // car
        case 14: // truck
        case 15: // bus
            return SemanticGroup::Vehicle;
        case 17: // motorcycle
        case 18: // bicycle
            return SemanticGroup::TwoWheel;
        case 3:  // wall
        case 4:  // fence
        case 5:  // pole
            return SemanticGroup::Structure;
        default:
            return SemanticGroup::Unknown;
    }
}

SemanticGroup semanticGroup(int source, int classId) {
    return source == 0 ? groupFromCoco(classId) : groupFromCityscapes(classId);
}

bool classesCompatible(int trackSource, int trackClassId, int detSource, int detClassId) {
    const auto g1 = semanticGroup(trackSource, trackClassId);
    const auto g2 = semanticGroup(detSource, detClassId);
    if (g1 != SemanticGroup::Unknown && g2 != SemanticGroup::Unknown) {
        return g1 == g2;
    }
    // Fallback: strict equality only within same taxonomy.
    return trackSource == detSource && trackClassId == detClassId;
}

bool isFiniteBBox(const BBox& bbox) {
    return std::isfinite(bbox.x) && std::isfinite(bbox.y) &&
           std::isfinite(bbox.w) && std::isfinite(bbox.h);
}

bool isDrawableBBox(const BBox& bbox) {
    if (!isFiniteBBox(bbox)) return false;
    if (bbox.w <= 0.0f || bbox.h <= 0.0f) return false;
    if (bbox.w > 1e4f || bbox.h > 1e4f) return false;
    if (std::fabs(bbox.x) > 1e6f || std::fabs(bbox.y) > 1e6f) return false;
    return true;
}

void sanitizeState(KalmanFilter::StateVec& mean, KalmanFilter::StateCov& covariance) {
    for (int i = 0; i < KalmanFilter::STATE_DIM; ++i) {
        if (!std::isfinite(mean[i])) mean[i] = 0.0f;
    }
    mean[2] = std::clamp(mean[2], 0.05f, 20.0f);      // aspect ratio
    mean[3] = std::clamp(mean[3], 1.0f, 2048.0f);     // height
    mean[4] = std::clamp(mean[4], -2048.0f, 2048.0f); // vx
    mean[5] = std::clamp(mean[5], -2048.0f, 2048.0f); // vy
    mean[6] = std::clamp(mean[6], -10.0f, 10.0f);     // va
    mean[7] = std::clamp(mean[7], -2048.0f, 2048.0f); // vh

    for (int r = 0; r < KalmanFilter::STATE_DIM; ++r) {
        for (int c = 0; c < KalmanFilter::STATE_DIM; ++c) {
            if (!std::isfinite(covariance[r][c])) covariance[r][c] = 0.0f;
        }
        covariance[r][r] = std::clamp(covariance[r][r], 1e-6f, 1e6f);
    }
}

} // namespace

DeepSortTracker::DeepSortTracker(const ConfigV2& config)
    : config_(config)
    , nextId_(1) {
}

std::vector<TrackedObstacleV2> DeepSortTracker::update(
    const std::vector<ObstacleV2>& obstacles,
    const std::vector<std::array<float, 1280>>& features) {

    // Predict all tracks
    for (auto& track : tracks_) {
        kf_.predict(track.mean, track.covariance);
        sanitizeState(track.mean, track.covariance);
        ++track.age;
        ++track.timeSinceUpdate;
    }

    // Match detections to tracks using cascade matching
    auto matches = matchCascade(obstacles, features);

    // Update matched tracks
    std::set<int> matchedTrackIndices;
    std::set<int> matchedObsIndices;

    for (const auto& [trackIdx, obsIdx] : matches) {
        matchedTrackIndices.insert(trackIdx);
        matchedObsIndices.insert(obsIdx);

        auto& track = tracks_[trackIdx];
        auto measurement = bboxToMeasurement(obstacles[obsIdx].bbox);
        const auto predBox = track.getBBox();
        const float predW = std::max(predBox.w, 1e-6f);
        const float predH = std::max(predBox.h, 1e-6f);
        const float detW = std::max(obstacles[obsIdx].bbox.w, 1e-6f);
        const float detH = std::max(obstacles[obsIdx].bbox.h, 1e-6f);
        const float dw = std::fabs(detW - predW) / predW;
        const float dh = std::fabs(detH - predH) / predH;
        if (dw > config_.maxSizeChange || dh > config_.maxSizeChange) {
            auto predMeas = bboxToMeasurement(predBox);
            measurement[2] = predMeas[2];
            measurement[3] = predMeas[3];
        }
        kf_.update(measurement, track.mean, track.covariance);
        sanitizeState(track.mean, track.covariance);

        // Update appearance with exponential moving average
        const auto& newFeature = features[obsIdx];
        for (int i = 0; i < 1280; ++i) {
            track.appearance[i] = 0.9f * track.appearance[i] + 0.1f * newFeature[i];
        }

        track.classId = obstacles[obsIdx].classId;
        track.classSource = obstacles[obsIdx].source;
        track.confidence = obstacles[obsIdx].confidence;
        track.depthMeters = obstacles[obsIdx].depthMedian;
        track.depthHistory.push_back(obstacles[obsIdx].depthMedian);
        if (track.depthHistory.size() > 10) {
            track.depthHistory.pop_front();
        }
        track.timeSinceUpdate = 0;
        ++track.hits;

        if (!track.confirmed && track.hits >= config_.nInit) {
            track.confirmed = true;
        }
    }

    // Create new tracks for unmatched detections
    for (int i = 0; i < static_cast<int>(obstacles.size()); ++i) {
        if (matchedObsIndices.count(i) > 0) continue;

        Track newTrack;
        newTrack.id = nextId_++;
        auto measurement = bboxToMeasurement(obstacles[i].bbox);
        kf_.initiate(measurement, newTrack.mean, newTrack.covariance);
        sanitizeState(newTrack.mean, newTrack.covariance);
        newTrack.appearance = features[i];
        newTrack.classId = obstacles[i].classId;
        newTrack.classSource = obstacles[i].source;
        newTrack.confidence = obstacles[i].confidence;
        newTrack.depthMeters = obstacles[i].depthMedian;
        newTrack.depthHistory.push_back(obstacles[i].depthMedian);
        newTrack.timeSinceUpdate = 0;
        newTrack.hits = 1;
        newTrack.age = 1;
        newTrack.confirmed = false;

        tracks_.push_back(newTrack);
    }

    // Remove dead tracks
    tracks_.erase(
        std::remove_if(tracks_.begin(), tracks_.end(),
            [this](const Track& t) {
                return t.timeSinceUpdate > config_.maxAge;
            }),
        tracks_.end());

    std::vector<TrackedObstacleV2> result;
    result.reserve(tracks_.size());
    for (const auto& track : tracks_) {
        if (!track.confirmed) continue;
        if (track.timeSinceUpdate != 0) continue;

        TrackedObstacleV2 t;
        t.trackId = track.id;
        t.bbox = track.getBBox();
        if (!isDrawableBBox(t.bbox)) continue;
        t.classId = track.classId;
        t.classSource = track.classSource;
        t.confidence = track.confidence;
        t.depthMeters = track.depthMeters;
        t.velocityX = track.mean[4];
        t.velocityY = track.mean[5];
        t.approachSpeed = computeApproachSpeed(track);
        t.appearance = track.appearance;
        t.timeSinceUpdate = track.timeSinceUpdate;
        t.hits = track.hits;
        t.age = track.age;
        t.confirmed = track.confirmed;
        result.push_back(t);
    }
    return result;
}

std::vector<TrackedObstacleV2> DeepSortTracker::getConfirmedTracks() const {
    std::vector<TrackedObstacleV2> result;
    for (const auto& track : tracks_) {
        if (!track.confirmed) continue;

        TrackedObstacleV2 t;
        t.trackId = track.id;
        t.bbox = track.getBBox();
        if (!isDrawableBBox(t.bbox)) continue;
        t.classId = track.classId;
        t.confidence = track.confidence;
        t.depthMeters = track.depthMeters;
        t.velocityX = track.mean[4];
        t.velocityY = track.mean[5];
        t.approachSpeed = computeApproachSpeed(track);
        t.appearance = track.appearance;
        t.timeSinceUpdate = track.timeSinceUpdate;
        t.hits = track.hits;
        t.age = track.age;
        t.confirmed = track.confirmed;

        result.push_back(t);
    }
    return result;
}

std::vector<TrackedObstacleV2> DeepSortTracker::getAllTracks() const {
    std::vector<TrackedObstacleV2> result;
    for (const auto& track : tracks_) {
        TrackedObstacleV2 t;
        t.trackId = track.id;
        t.bbox = track.getBBox();
        if (!isDrawableBBox(t.bbox)) continue;
        t.classId = track.classId;
        t.confidence = track.confidence;
        t.depthMeters = track.depthMeters;
        t.velocityX = track.mean[4];
        t.velocityY = track.mean[5];
        t.approachSpeed = computeApproachSpeed(track);
        t.appearance = track.appearance;
        t.timeSinceUpdate = track.timeSinceUpdate;
        t.hits = track.hits;
        t.age = track.age;
        t.confirmed = track.confirmed;

        result.push_back(t);
    }
    return result;
}

std::vector<std::pair<int, int>> DeepSortTracker::matchCascade(
    const std::vector<ObstacleV2>& obstacles,
    const std::vector<std::array<float, 1280>>& features) {

    std::vector<std::pair<int, int>> allMatches;
    std::set<int> unmatchedObs;
    for (int i = 0; i < static_cast<int>(obstacles.size()); ++i) {
        unmatchedObs.insert(i);
    }

    // Cascade matching: match by time_since_update groups
    for (int level = 0; level <= config_.maxAge; ++level) {
        if (unmatchedObs.empty()) break;

        // Get track indices at this cascade level
        std::vector<int> trackIndices;
        for (int i = 0; i < static_cast<int>(tracks_.size()); ++i) {
            if (tracks_[i].timeSinceUpdate == level) {
                trackIndices.push_back(i);
            }
        }
        if (trackIndices.empty()) continue;

        // Build cost matrix
        std::vector<int> obsIndices(unmatchedObs.begin(), unmatchedObs.end());
        std::vector<std::vector<float>> costs(
            trackIndices.size(),
            std::vector<float>(obsIndices.size(), 1e9f));

        for (size_t ti = 0; ti < trackIndices.size(); ++ti) {
            for (size_t oi = 0; oi < obsIndices.size(); ++oi) {
                int trackIdx = trackIndices[ti];
                int obsIdx = obsIndices[oi];
                costs[ti][oi] = computeCost(tracks_[trackIdx], obstacles[obsIdx], features[obsIdx]);
            }
        }

        // Solve assignment
        float threshold = config_.lambdaMotion + config_.lambdaAppearance;
        auto matches = Hungarian::solve(costs, threshold);

        // Record matches
        for (const auto& [ti, oi] : matches) {
            int trackIdx = trackIndices[ti];
            int obsIdx = obsIndices[oi];
            allMatches.emplace_back(trackIdx, obsIdx);
            unmatchedObs.erase(obsIdx);
        }
    }

    return allMatches;
}

float DeepSortTracker::computeCost(const Track& track, const ObstacleV2& obs,
                                    const std::array<float, 1280>& feature) {
    // Match Python's semantic-compatibility hard constraint.
    if (!classesCompatible(track.classSource, track.classId, obs.source, obs.classId)) {
        return 1e9f;
    }

    const auto trackBox = track.getBBox();
    const float trackAR = trackBox.w / std::max(trackBox.h, 1e-6f);
    const float obsAR = obs.bbox.w / std::max(obs.bbox.h, 1e-6f);
    const float arDiff = std::fabs(std::log(trackAR + 0.1f) - std::log(obsAR + 0.1f));
    if (arDiff > config_.arLogThreshold) {
        return 1e9f;
    }

    // Mahalanobis distance (motion cost)
    auto measurement = bboxToMeasurement(obs.bbox);
    float mahalDist = kf_.gatingDistance(track.mean, track.covariance, measurement);

    // Cosine distance (appearance cost)
    float cosDist = cosineDistance(track.appearance, feature);
    if (!std::isfinite(mahalDist) || !std::isfinite(cosDist)) {
        return 1e9f;
    }

    // Python parity: allow appearance-strong matches even if motion gate fails.
    bool mahaOk = mahalDist <= config_.mahalanobisThreshold;
    bool appearanceOk = cosDist < config_.cosineThreshold;
    if (!mahaOk && !appearanceOk) {
        return 1e9f;
    }

    float cost = 0.0f;
    if (mahaOk) {
        cost =
            config_.lambdaMotion * (mahalDist / std::max(config_.mahalanobisThreshold, 1e-6f)) +
            config_.lambdaAppearance * cosDist;
    } else {
        cost = 0.3f + config_.lambdaAppearance * cosDist;
    }

    return cost;
}

float DeepSortTracker::cosineDistance(const std::array<float, 1280>& a,
                                       const std::array<float, 1280>& b) {
    float dot = 0.0f;
    for (int i = 0; i < 1280; ++i) {
        dot += a[i] * b[i];
    }
    // Features are L2-normalized, so cosine similarity = dot product
    // Cosine distance = 1 - cosine similarity
    return 1.0f - dot;
}

KalmanFilter::MeasureVec DeepSortTracker::bboxToMeasurement(const BBox& bbox) {
    // Convert [x, y, w, h] to [cx, cy, a, h]
    float x = std::isfinite(bbox.x) ? bbox.x : 0.0f;
    float y = std::isfinite(bbox.y) ? bbox.y : 0.0f;
    float w = std::isfinite(bbox.w) ? bbox.w : 1.0f;
    float h = std::isfinite(bbox.h) ? bbox.h : 1.0f;
    w = std::clamp(w, 1.0f, 4096.0f);
    h = std::clamp(h, 1.0f, 4096.0f);
    float cx = x + w / 2.0f;
    float cy = y + h / 2.0f;
    float a = std::clamp(w / std::max(h, 1e-6f), 0.05f, 20.0f);  // aspect ratio
    return {cx, cy, a, h};
}

float DeepSortTracker::computeApproachSpeed(const Track& track) const {
    if (track.depthHistory.size() < 2) return 0.0f;

    const float fps = std::max(config_.fps, 1.0f);
    const float frameInterval = 1.0f / fps;
    const float current = track.depthHistory[track.depthHistory.size() - 1];
    const float previous = track.depthHistory[track.depthHistory.size() - 2];
    if (!(current > 0.0f && previous > 0.0f)) return 0.0f;

    float speed = (current - previous) / frameInterval;
    if (track.depthHistory.size() >= 3) {
        const float prev2 = track.depthHistory[track.depthHistory.size() - 3];
        if (prev2 > 0.0f) {
            const float prevSpeed = (previous - prev2) / frameInterval;
            speed = config_.emaAlpha * speed + (1.0f - config_.emaAlpha) * prevSpeed;
        }
    }
    return speed;
}

} // namespace safetycore_v2
