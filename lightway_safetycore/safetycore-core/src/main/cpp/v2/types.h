#ifndef SAFETYCORE_V2_TYPES_H
#define SAFETYCORE_V2_TYPES_H

#include <vector>
#include <array>
#include <cstdint>

namespace safetycore_v2 {

// Bounding box in [x, y, w, h] format
struct BBox {
    float x, y, w, h;

    float centerX() const { return x + w / 2.0f; }
    float centerY() const { return y + h / 2.0f; }
    float area() const { return w * h; }
};

// Detection from YOLOX
struct Detection {
    BBox bbox;
    float score;
    int classId;
};

// Obstacle extracted from detection or segmentation
struct ObstacleV2 {
    BBox bbox;
    int classId;
    float confidence;
    float depthMedian;      // Median depth in meters
    float depthStd;         // Depth standard deviation
    int source;             // 0=detection, 1=segmentation
};

// Tracked obstacle with Kalman state
struct TrackedObstacleV2 {
    int trackId;
    BBox bbox;
    int classId;
    int classSource;
    float confidence;
    // Relative depth copied from depthMedian and passed through JNI unchanged.
    // DangerAssessor computes metric distance as scaleFactor / depthMeters.
    float depthMeters;
    float velocityX;        // Velocity in pixels/frame
    float velocityY;
    float approachSpeed;    // Relative depth trend per second (positive = approaching)
    std::array<float, 1280> appearance;  // L2-normalized feature vector
    int timeSinceUpdate;
    int hits;
    int age;
    bool confirmed;         // True if hits >= n_init
};

// Danger level enum
enum class DangerLevel {
    NONE = 0,
    LOW = 1,
    MEDIUM = 2,
    HIGH = 3,
    CRITICAL = 4
};

// Threat assessment result
struct ThreatResultV2 {
    int trackId;
    DangerLevel level;
    float distanceMeters;
    float ttcSeconds;       // Time to collision
    float score;            // Overall threat score [0, 1]
};

// Scale recovery state
struct ScaleState {
    float scaleFactor;      // Current scale factor
    bool stable;            // Whether scale is stable
    int validFrames;        // Number of valid frames
};

// Fusion result for a single frame
struct FusionResultV2 {
    std::vector<TrackedObstacleV2> tracks;
    std::vector<ThreatResultV2> threats;
    ScaleState scaleState;
    float frameTimeMs;
};

// Configuration parameters
struct ConfigV2 {
    // Distance thresholds (meters)
    float distanceCritical = 2.0f;
    float distanceHigh = 4.0f;
    float distanceMedium = 6.0f;

    // TTC threshold (seconds)
    float ttcCritical = 1.0f;

    // DeepSORT parameters
    float lambdaMotion = 0.5f;
    float lambdaAppearance = 0.5f;
    float mahalanobisThreshold = 9.4877f;  // Chi-square 95% confidence
    float cosineThreshold = 0.3f;
    int maxAge = 30;
    int nInit = 5;
    float maxSizeChange = 0.5f;
    float arLogThreshold = 0.5f;
    float emaAlpha = 0.3f;
    float fps = 15.0f;

    // Danger assessment heuristics
    float ttcMinSeconds = 0.1f;
    float ttcMaxSeconds = 30.0f;
    float maxApproachSpeed = 15.0f;
    int minBoxAreaPx = 100;
    float maxBoxAreaRatio = 0.4f;
    float centerRegionRatio = 0.2f;

    // Scale recovery parameters
    float cameraHeightDefault = 1.2f;      // meters
    float fixedPitchDeg = 35.0f;           // degrees
    int scaleHistorySize = 10;
    float scaleStabilityThreshold = 0.1f;

    // Walkable class IDs (Cityscapes)
    std::vector<int> walkableClassIds = {0, 1, 9};  // road, sidewalk, terrain
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_TYPES_H
