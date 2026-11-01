#ifndef SAFETYCORE_V2_SCALE_RECOVERY_H
#define SAFETYCORE_V2_SCALE_RECOVERY_H

#include "types.h"
#include <deque>

namespace safetycore_v2 {

/**
 * Scale recovery from ground plane depth.
 * Uses camera height and pitch angle to estimate metric scale.
 */
class ScaleRecovery {
public:
    explicit ScaleRecovery(const ConfigV2& config);

    /**
     * Update scale estimate from segmentation and depth maps.
     * @param segClasses Segmentation class IDs [H*W]
     * @param depthRel Relative depth values [H*W]
     * @param width Image width
     * @param height Image height
     * @return Updated scale state
     */
    ScaleState update(const uint8_t* segClasses,
                      const float* depthRel,
                      int width, int height);

    /**
     * Convert relative depth to metric depth.
     */
    float relativeToMetric(float relDepth) const;

    /**
     * Get current scale factor.
     */
    float getScaleFactor() const { return currentScale_; }

    /**
     * Check if scale is stable.
     */
    bool isStable() const { return stable_; }

private:
    ConfigV2 config_;
    std::deque<float> scaleHistory_;
    float currentScale_;
    bool stable_;
    int validFrames_;

    float computeGroundScale(const uint8_t* segClasses,
                             const float* depthRel,
                             int width, int height);
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_SCALE_RECOVERY_H
