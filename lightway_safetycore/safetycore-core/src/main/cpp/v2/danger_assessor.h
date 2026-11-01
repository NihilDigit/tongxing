#ifndef SAFETYCORE_V2_DANGER_ASSESSOR_H
#define SAFETYCORE_V2_DANGER_ASSESSOR_H

#include "types.h"
#include <vector>

namespace safetycore_v2 {

/**
 * Danger assessment based on distance and TTC.
 */
class DangerAssessor {
public:
    explicit DangerAssessor(const ConfigV2& config);

    /**
     * Assess danger level for tracked obstacles.
     * @param tracks Tracked obstacles with depth and velocity
     * @param scaleFactor Current scale factor for depth conversion
     * @param fps Frame rate for TTC calculation
     * @param frameWidth Track-space width for spatial heuristics
     * @param frameHeight Track-space height for spatial heuristics
     * @return Threat assessment results
     */
    std::vector<ThreatResultV2> assess(
        const std::vector<TrackedObstacleV2>& tracks,
        float scaleFactor,
        float fps,
        int frameWidth,
        int frameHeight);

private:
    ConfigV2 config_;

    DangerLevel classifyDanger(float distanceMeters, float ttcSeconds);
    float computeTTC(float distanceMeters, float approachSpeed);
    float computeApproachSpeed(const TrackedObstacleV2& track, float scaleFactor, float fps);
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_DANGER_ASSESSOR_H
