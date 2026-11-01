#include "scale_recovery.h"
#include <algorithm>
#include <cmath>
#include <numeric>

namespace safetycore_v2 {

ScaleRecovery::ScaleRecovery(const ConfigV2& config)
    : config_(config)
    , currentScale_(1.0f)
    , stable_(false)
    , validFrames_(0) {
}

ScaleState ScaleRecovery::update(const uint8_t* segClasses,
                                  const float* depthRel,
                                  int width, int height) {
    float newScale = computeGroundScale(segClasses, depthRel, width, height);

    if (std::isfinite(newScale) && newScale > 0.0f) {
        scaleHistory_.push_back(newScale);
        if (scaleHistory_.size() > static_cast<size_t>(config_.scaleHistorySize)) {
            scaleHistory_.pop_front();
        }
        ++validFrames_;

        // Compute median scale
        std::vector<float> sorted(scaleHistory_.begin(), scaleHistory_.end());
        std::sort(sorted.begin(), sorted.end());
        currentScale_ = sorted[sorted.size() / 2];

        // Check stability
        if (scaleHistory_.size() >= 5) {
            float minScale = *std::min_element(sorted.begin(), sorted.end());
            float maxScale = *std::max_element(sorted.begin(), sorted.end());
            float range = (maxScale - minScale) / currentScale_;
            stable_ = range < config_.scaleStabilityThreshold;
        }
    }

    return ScaleState{currentScale_, stable_, validFrames_};
}

float ScaleRecovery::relativeToMetric(float relDepth) const {
    if (!std::isfinite(relDepth) || relDepth <= 0.0f) {
        return std::numeric_limits<float>::quiet_NaN();
    }
    return currentScale_ / relDepth;
}

float ScaleRecovery::computeGroundScale(const uint8_t* segClasses,
                                         const float* depthRel,
                                         int width, int height) {
    // Build walkable class lookup
    std::vector<bool> isWalkable(256, false);
    for (int id : config_.walkableClassIds) {
        if (id >= 0 && id < 256) {
            isWalkable[id] = true;
        }
    }

    // Collect depth values from ground pixels in bottom half of image
    std::vector<float> groundDepths;
    groundDepths.reserve(width * height / 4);

    int startY = height / 2;  // Only use bottom half
    for (int y = startY; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            uint8_t classId = segClasses[idx];
            if (isWalkable[classId]) {
                float d = depthRel[idx];
                if (std::isfinite(d) && d > 0.0f) {
                    groundDepths.push_back(d);
                }
            }
        }
    }

    if (groundDepths.size() < 100) {
        // Not enough ground pixels
        return std::numeric_limits<float>::quiet_NaN();
    }

    // Compute median ground depth
    std::sort(groundDepths.begin(), groundDepths.end());
    float medianDepth = groundDepths[groundDepths.size() / 2];

    // Scale = camera_height / sin(pitch) * median_depth
    float pitchRad = config_.fixedPitchDeg * 3.14159265f / 180.0f;
    float sinPitch = std::sin(pitchRad);
    if (sinPitch < 0.1f) sinPitch = 0.1f;  // Avoid division by small values

    float scale = config_.cameraHeightDefault / sinPitch * medianDepth;

    return scale;
}

} // namespace safetycore_v2
