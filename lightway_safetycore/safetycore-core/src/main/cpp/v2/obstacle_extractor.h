#ifndef SAFETYCORE_V2_OBSTACLE_EXTRACTOR_H
#define SAFETYCORE_V2_OBSTACLE_EXTRACTOR_H

#include "types.h"
#include <vector>

namespace safetycore_v2 {

/**
 * Dual-path obstacle extraction from detection and segmentation.
 */
class ObstacleExtractor {
public:
    explicit ObstacleExtractor(const ConfigV2& config);

    /**
     * Extract obstacles from detections (Path A: detection-driven).
     * @param detections YOLOX detections
     * @param depthRel Relative depth map [H*W]
     * @param width Depth map width
     * @param height Depth map height
     * @return Extracted obstacles with depth statistics
     */
    std::vector<ObstacleV2> extractFromDetections(
        const std::vector<Detection>& detections,
        const float* depthRel,
        int width, int height);

    /**
     * Extract obstacles from segmentation (Path B: segmentation-driven).
     * Uses connected component labeling on non-walkable regions.
     * @param segClasses Segmentation class IDs [H*W]
     * @param depthRel Relative depth map [H*W]
     * @param width Image width
     * @param height Image height
     * @param minArea Minimum blob area in pixels
     * @return Extracted obstacles from segmentation blobs
     */
    std::vector<ObstacleV2> extractFromSegmentation(
        const uint8_t* segClasses,
        const float* depthRel,
        int width, int height,
        int minArea = 500);

private:
    ConfigV2 config_;

    // Compute depth statistics within a bounding box
    void computeDepthStats(const float* depthRel, int width, int height,
                           const BBox& bbox, float& median, float& stddev);
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_OBSTACLE_EXTRACTOR_H
