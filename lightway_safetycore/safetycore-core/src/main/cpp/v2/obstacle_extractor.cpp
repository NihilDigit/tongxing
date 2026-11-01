#include "obstacle_extractor.h"
#include <algorithm>
#include <cmath>
#include <queue>
#include <numeric>

namespace safetycore_v2 {

// NMS helper: compute IoU between two bounding boxes
static float computeIoU(const BBox& a, const BBox& b) {
    float x1 = std::max(a.x, b.x);
    float y1 = std::max(a.y, b.y);
    float x2 = std::min(a.x + a.w, b.x + b.w);
    float y2 = std::min(a.y + a.h, b.y + b.h);

    float interW = std::max(0.0f, x2 - x1);
    float interH = std::max(0.0f, y2 - y1);
    float inter = interW * interH;

    float areaA = a.w * a.h;
    float areaB = b.w * b.h;
    float unionArea = areaA + areaB - inter;

    return unionArea > 0 ? inter / unionArea : 0.0f;
}

static bool passesSegGeometryFilter(
    int dominantClass,
    int area,
    float boxW,
    float boxH,
    int frameW,
    int frameH,
    float fillRatio,
    float dominantRatio) {
    if (area <= 0 || boxW <= 0.0f || boxH <= 0.0f) return false;
    if (fillRatio < 0.12f) return false;
    if (dominantRatio < 0.70f) return false;

    const float areaRatio = static_cast<float>(area) / static_cast<float>(frameW * frameH);
    const float wRatio = boxW / static_cast<float>(frameW);
    const float hRatio = boxH / static_cast<float>(frameH);
    const float aspectHW = boxH / std::max(boxW, 1e-6f);

    if (areaRatio > 0.45f) return false;

    // Cityscapes: person/rider
    if (dominantClass == 11 || dominantClass == 12) {
        if (areaRatio > 0.20f) return false;
        if (wRatio > 0.45f) return false;
        if (aspectHW < 0.45f) return false;
    }
    // car/truck/bus/motorcycle/bicycle
    if (dominantClass == 13 || dominantClass == 14 || dominantClass == 15 ||
        dominantClass == 17 || dominantClass == 18) {
        if (areaRatio > 0.35f) return false;
        if (wRatio > 0.90f || hRatio > 0.80f) return false;
    }
    // pole/wall/fence
    if (dominantClass == 3 || dominantClass == 4 || dominantClass == 5) {
        if (areaRatio > 0.25f) return false;
    }
    return true;
}

ObstacleExtractor::ObstacleExtractor(const ConfigV2& config)
    : config_(config) {
}

std::vector<ObstacleV2> ObstacleExtractor::extractFromDetections(
    const std::vector<Detection>& detections,
    const float* depthRel,
    int width, int height) {

    // Step 1: Filter by confidence threshold
    constexpr float CONF_THRESHOLD = 0.25f;
    constexpr float NMS_THRESHOLD = 0.45f;

    std::vector<Detection> filtered;
    filtered.reserve(detections.size());
    for (const auto& det : detections) {
        if (det.score >= CONF_THRESHOLD) {
            filtered.push_back(det);
        }
    }

    if (filtered.empty()) {
        return {};
    }

    // Step 2: Sort by confidence (descending)
    std::sort(filtered.begin(), filtered.end(),
              [](const Detection& a, const Detection& b) {
                  return a.score > b.score;
              });

    // Step 3: NMS
    std::vector<bool> suppressed(filtered.size(), false);
    std::vector<ObstacleV2> obstacles;
    obstacles.reserve(std::min(filtered.size(), size_t(100)));

    for (size_t i = 0; i < filtered.size(); ++i) {
        if (suppressed[i]) continue;

        const auto& det = filtered[i];
        ObstacleV2 obs;
        obs.bbox = det.bbox;
        obs.classId = det.classId;
        obs.confidence = det.score;
        obs.source = 0;  // detection

        computeDepthStats(depthRel, width, height, det.bbox,
                          obs.depthMedian, obs.depthStd);

        obstacles.push_back(obs);

        // Suppress overlapping detections
        for (size_t j = i + 1; j < filtered.size(); ++j) {
            if (!suppressed[j] && computeIoU(det.bbox, filtered[j].bbox) > NMS_THRESHOLD) {
                suppressed[j] = true;
            }
        }
    }

    return obstacles;
}

std::vector<ObstacleV2> ObstacleExtractor::extractFromSegmentation(
    const uint8_t* segClasses,
    const float* depthRel,
    int width, int height,
    int minArea) {

    // Python parity: only known obstacle classes become path-B candidates.
    constexpr int kObstacleClassIds[] = {11, 12, 13, 14, 15, 17, 18, 5, 3, 4};
    std::vector<bool> isObstacle(256, false);
    for (int id : kObstacleClassIds) {
        if (id >= 0 && id < 256) {
            isObstacle[id] = true;
        }
    }

    // Create obstacle mask.
    std::vector<uint8_t> obstacleMask(width * height, 0);
    for (int i = 0; i < width * height; ++i) {
        if (isObstacle[segClasses[i]]) {
            obstacleMask[i] = 1;
        }
    }

    // Connected component labeling (4-connectivity BFS)
    std::vector<int> labels(width * height, 0);
    int nextLabel = 1;
    std::vector<ObstacleV2> obstacles;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            if (obstacleMask[idx] == 0 || labels[idx] != 0) continue;

            // BFS to find connected component
            std::queue<std::pair<int, int>> queue;
            queue.push({x, y});
            labels[idx] = nextLabel;

            const uint8_t seedClass = segClasses[idx];
            int minX = x, maxX = x, minY = y, maxY = y;
            int area = 0;
            int dominantClass = seedClass;
            std::vector<int> classCounts(256, 0);

            while (!queue.empty()) {
                auto [cx, cy] = queue.front();
                queue.pop();

                int cidx = cy * width + cx;
                ++area;
                ++classCounts[segClasses[cidx]];

                minX = std::min(minX, cx);
                maxX = std::max(maxX, cx);
                minY = std::min(minY, cy);
                maxY = std::max(maxY, cy);

                // 4-connectivity neighbors
                const int dx[] = {-1, 1, 0, 0};
                const int dy[] = {0, 0, -1, 1};
                for (int d = 0; d < 4; ++d) {
                    int nx = cx + dx[d];
                    int ny = cy + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nidx = ny * width + nx;
                    if (obstacleMask[nidx] == 1 &&
                        labels[nidx] == 0 &&
                        segClasses[nidx] == seedClass) {
                        labels[nidx] = nextLabel;
                        queue.push({nx, ny});
                    }
                }
            }

            // Find dominant class
            int maxCount = 0;
            for (int c = 0; c < 256; ++c) {
                if (classCounts[c] > maxCount) {
                    maxCount = classCounts[c];
                    dominantClass = c;
                }
            }

            // Create obstacle if area is large enough
            if (area >= minArea) {
                ObstacleV2 obs;
                obs.bbox.x = static_cast<float>(minX);
                obs.bbox.y = static_cast<float>(minY);
                obs.bbox.w = static_cast<float>(maxX - minX + 1);
                obs.bbox.h = static_cast<float>(maxY - minY + 1);
                obs.classId = dominantClass;
                const float boxArea = std::max(1.0f, obs.bbox.w * obs.bbox.h);
                const float fillRatio = static_cast<float>(area) / boxArea;
                const float dominantRatio = static_cast<float>(maxCount) / static_cast<float>(area);
                obs.confidence = fillRatio;
                obs.source = 1;  // segmentation

                if (!passesSegGeometryFilter(
                        dominantClass,
                        area,
                        obs.bbox.w,
                        obs.bbox.h,
                        width,
                        height,
                        fillRatio,
                        dominantRatio)) {
                    ++nextLabel;
                    continue;
                }

                computeDepthStats(depthRel, width, height, obs.bbox,
                                  obs.depthMedian, obs.depthStd);

                obstacles.push_back(obs);
            }

            ++nextLabel;
        }
    }

    return obstacles;
}

void ObstacleExtractor::computeDepthStats(const float* depthRel, int width, int height,
                                           const BBox& bbox, float& median, float& stddev) {
    int x0 = std::max(0, static_cast<int>(bbox.x));
    int y0 = std::max(0, static_cast<int>(bbox.y));
    int x1 = std::min(width - 1, static_cast<int>(bbox.x + bbox.w));
    int y1 = std::min(height - 1, static_cast<int>(bbox.y + bbox.h));

    std::vector<float> depths;
    depths.reserve((x1 - x0 + 1) * (y1 - y0 + 1));

    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            float d = depthRel[y * width + x];
            if (std::isfinite(d) && d > 0.0f) {
                depths.push_back(d);
            }
        }
    }

    if (depths.empty()) {
        median = std::numeric_limits<float>::quiet_NaN();
        stddev = std::numeric_limits<float>::quiet_NaN();
        return;
    }

    // Compute median
    std::sort(depths.begin(), depths.end());
    median = depths[depths.size() / 2];

    // Compute standard deviation
    float sum = std::accumulate(depths.begin(), depths.end(), 0.0f);
    float mean = sum / depths.size();
    float sqSum = 0.0f;
    for (float d : depths) {
        sqSum += (d - mean) * (d - mean);
    }
    stddev = std::sqrt(sqSum / depths.size());
}

} // namespace safetycore_v2
