#include "danger_assessor.h"

#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

using namespace safetycore_v2;

static ThreatResultV2 assess(float scale, float depth, float speed) {
    TrackedObstacleV2 track{};
    track.trackId = 1;
    track.bbox = {300.0f, 200.0f, 40.0f, 80.0f};
    track.classId = 0;
    track.classSource = 0;
    track.confirmed = true;
    track.depthMeters = depth;
    track.approachSpeed = speed;
    DangerAssessor assessor(ConfigV2{});
    const auto results = assessor.assess({track}, scale, 15.0f, 640, 480);
    if (results.size() != 1) throw std::runtime_error("Expected one person track");
    return results.front();
}

int main() {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    int failures = 0;
    auto check = [&](bool condition, const char* message) {
        if (!condition) {
            std::cerr << message << '\n';
            ++failures;
        }
    };

    for (float invalid : {nan, inf, -inf, 0.0f, -1.0f}) {
        for (bool invalidScale : {true, false}) {
            const auto result = invalidScale ? assess(invalid, 1.0f, 1.0f)
                                             : assess(1.0f, invalid, 1.0f);
            check(result.level == DangerLevel::NONE, "Invalid depth/scale produced a warning");
            check(std::isnan(result.distanceMeters), "Invalid depth/scale produced a distance");
            check(std::isinf(result.ttcSeconds), "Invalid depth/scale produced a finite TTC");
            check(result.score == 0.0f, "Invalid depth/scale produced a threat score");
        }
    }
    for (float invalidSpeed : {nan, inf, -inf}) {
        const auto result = assess(1.0f, 1.0f, invalidSpeed);
        check(result.level == DangerLevel::HIGH, "Invalid speed changed distance-only warning");
        check(std::isinf(result.ttcSeconds), "Invalid speed produced a finite TTC");
    }
    check(assess(1.0f, 1.0f, 2.0f).level == DangerLevel::CRITICAL,
          "Valid approaching obstacle lost critical warning");
    check(assess(1.0f, 1.0f, 0.0f).level == DangerLevel::HIGH,
          "Valid stationary obstacle lost high warning");
    check(assess(5.0f, 1.0f, 0.0f).level == DangerLevel::LOW,
          "Valid distant obstacle lost low warning");
    return failures == 0 ? 0 : 1;
}
