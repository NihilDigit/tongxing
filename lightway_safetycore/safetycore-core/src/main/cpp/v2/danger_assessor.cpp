#include "danger_assessor.h"
#include <algorithm>
#include <cmath>
#include <limits>

namespace safetycore_v2 {

DangerAssessor::DangerAssessor(const ConfigV2& config)
    : config_(config) {
}

namespace {

enum class SemanticGroup {
    Unknown = 0,
    Person,
    Vehicle,
    TwoWheel,
    Structure,
    Other
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
        case 10: // fire hydrant
        case 13: // bench
        case 15: // cat
        case 16: // dog
        case 58: // potted plant
            return SemanticGroup::Other;
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

} // namespace

std::vector<ThreatResultV2> DangerAssessor::assess(
    const std::vector<TrackedObstacleV2>& tracks,
    float scaleFactor,
    float fps,
    int frameWidth,
    int frameHeight) {
    std::vector<ThreatResultV2> results;
    results.reserve(tracks.size());

    for (const auto& track : tracks) {
        if (!track.confirmed) continue;

        const auto group = semanticGroup(track.classSource, track.classId);
        if (group == SemanticGroup::Unknown) continue;

        const float area = track.bbox.area();
        const float frameArea = static_cast<float>(frameWidth) * static_cast<float>(frameHeight);
        const float minArea = std::max(
            1.0f,
            static_cast<float>(config_.minBoxAreaPx) * (frameArea / (1280.0f * 720.0f)));
        if (frameArea > 0.0f) {
            if (area < minArea) continue;
            if (area >= config_.maxBoxAreaRatio * frameArea) continue;
        }

        if (group == SemanticGroup::Structure && frameWidth > 0) {
            const float centerX = track.bbox.centerX();
            const float ratio = centerX / static_cast<float>(frameWidth);
            const float half = config_.centerRegionRatio * 0.5f;
            if (ratio < (0.5f - half) || ratio > (0.5f + half)) {
                continue;
            }
        }

        ThreatResultV2 threat;
        threat.trackId = track.trackId;

        // V2 tracker currently stores relative depth in depthMeters field.
        float distanceMeters = std::numeric_limits<float>::quiet_NaN();
        if (std::isfinite(scaleFactor) && scaleFactor > 0.0f &&
            std::isfinite(track.depthMeters) && track.depthMeters > 0.0f) {
            distanceMeters = scaleFactor / track.depthMeters;
        }
        threat.distanceMeters = distanceMeters;

        // Approach speed comes from depth history in tracker.
        const float approachSpeed = computeApproachSpeed(track, scaleFactor, fps);
        threat.ttcSeconds = computeTTC(distanceMeters, approachSpeed);
        threat.level = classifyDanger(distanceMeters, threat.ttcSeconds);

        float distScore = 0.0f;
        if (std::isfinite(distanceMeters) && distanceMeters > 0.0f) {
            distScore = 1.0f - std::min(distanceMeters / config_.distanceMedium, 1.0f);
        }
        float ttcScore = 0.0f;
        if (std::isfinite(threat.ttcSeconds) && threat.ttcSeconds > 0.0f) {
            ttcScore = 1.0f - std::min(threat.ttcSeconds / 5.0f, 1.0f);
        }
        threat.score = std::max(distScore, ttcScore);

        results.push_back(threat);
    }

    std::sort(results.begin(), results.end(),
              [](const ThreatResultV2& a, const ThreatResultV2& b) {
                  return a.score > b.score;
              });
    return results;
}

DangerLevel DangerAssessor::classifyDanger(float distanceMeters, float ttcSeconds) {
    if (!std::isfinite(distanceMeters)) {
        return DangerLevel::NONE;
    }

    // Python parity:
    // <=2m -> HIGH, and <=2m + TTC<=1s -> CRITICAL
    if (distanceMeters <= config_.distanceCritical) {
        if (std::isfinite(ttcSeconds) && ttcSeconds > 0.0f && ttcSeconds <= config_.ttcCritical) {
            return DangerLevel::CRITICAL;
        }
        return DangerLevel::HIGH;
    }
    if (distanceMeters <= config_.distanceHigh) return DangerLevel::MEDIUM;
    if (distanceMeters <= config_.distanceMedium) return DangerLevel::LOW;
    return DangerLevel::NONE;
}

float DangerAssessor::computeTTC(float distanceMeters, float approachSpeed) {
    if (!std::isfinite(distanceMeters) || !std::isfinite(approachSpeed) || approachSpeed <= 0.0f) {
        return std::numeric_limits<float>::infinity();
    }
    float ttc = distanceMeters / approachSpeed;
    if (ttc < config_.ttcMinSeconds || ttc > config_.ttcMaxSeconds) {
        return std::numeric_limits<float>::infinity();
    }
    return ttc;
}

float DangerAssessor::computeApproachSpeed(const TrackedObstacleV2& track,
                                           float scaleFactor, float fps) {
    (void)fps;
    if (!std::isfinite(track.approachSpeed)) return 0.0f;
    if (!(std::isfinite(scaleFactor) && scaleFactor > 0.0f)) return 0.0f;
    if (!(std::isfinite(track.depthMeters) && track.depthMeters > 0.0f)) return 0.0f;

    const float relSpeed = std::max(track.approachSpeed, 0.0f);
    float speed = scaleFactor * relSpeed / (track.depthMeters * track.depthMeters);
    if (!std::isfinite(speed)) return 0.0f;
    speed = std::min(speed, config_.maxApproachSpeed);
    return speed;
}

} // namespace safetycore_v2
