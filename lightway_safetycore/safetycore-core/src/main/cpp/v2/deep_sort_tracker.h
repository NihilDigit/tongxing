#ifndef SAFETYCORE_V2_DEEP_SORT_TRACKER_H
#define SAFETYCORE_V2_DEEP_SORT_TRACKER_H

#include "types.h"
#include "kalman_filter.h"
#include <algorithm>
#include <vector>
#include <array>
#include <deque>
#include <cmath>

namespace safetycore_v2 {

/**
 * DeepSORT tracker with Kalman filtering and appearance features.
 */
class DeepSortTracker {
public:
    explicit DeepSortTracker(const ConfigV2& config);

    /**
     * Update tracker with new detections.
     * @param obstacles Detected obstacles with depth and appearance features
     * @param features Appearance features for each obstacle [N x 1280]
     * @return Updated tracked obstacles
     */
    std::vector<TrackedObstacleV2> update(
        const std::vector<ObstacleV2>& obstacles,
        const std::vector<std::array<float, 1280>>& features);

    /**
     * Get all confirmed tracks.
     */
    std::vector<TrackedObstacleV2> getConfirmedTracks() const;

    /**
     * Get all tracks (including tentative).
     */
    std::vector<TrackedObstacleV2> getAllTracks() const;

private:
    struct Track {
        int id;
        KalmanFilter::StateVec mean;
        KalmanFilter::StateCov covariance;
        std::array<float, 1280> appearance;
        int classId;
        int classSource;
        float confidence;
        float depthMeters;
        std::deque<float> depthHistory;
        int timeSinceUpdate;
        int hits;
        int age;
        bool confirmed;

        BBox getBBox() const {
            // Convert from [cx, cy, a, h] to [x, y, w, h]
            float cx = std::isfinite(mean[0]) ? mean[0] : 0.0f;
            float cy = std::isfinite(mean[1]) ? mean[1] : 0.0f;
            float a = std::isfinite(mean[2]) ? mean[2] : 1.0f;
            float h = std::isfinite(mean[3]) ? mean[3] : 1.0f;
            a = std::clamp(a, 0.05f, 20.0f);
            h = std::clamp(h, 1.0f, 2048.0f);
            float w = std::clamp(a * h, 1.0f, 4096.0f);
            return BBox{cx - w/2, cy - h/2, w, h};
        }
    };

    ConfigV2 config_;
    KalmanFilter kf_;
    std::vector<Track> tracks_;
    int nextId_;

    // Matching functions
    std::vector<std::pair<int, int>> matchCascade(
        const std::vector<ObstacleV2>& obstacles,
        const std::vector<std::array<float, 1280>>& features);

    float computeCost(const Track& track, const ObstacleV2& obs,
                      const std::array<float, 1280>& feature);

    float cosineDistance(const std::array<float, 1280>& a,
                         const std::array<float, 1280>& b);

    KalmanFilter::MeasureVec bboxToMeasurement(const BBox& bbox);
    float computeApproachSpeed(const Track& track) const;
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_DEEP_SORT_TRACKER_H
