#ifndef SAFETYCORE_V2_KALMAN_FILTER_H
#define SAFETYCORE_V2_KALMAN_FILTER_H

#include <array>
#include <cmath>

namespace safetycore_v2 {

/**
 * 8-dimensional Kalman filter for bounding box tracking.
 * State: [x, y, a, h, vx, vy, va, vh]
 * where (x, y) is center, a is aspect ratio, h is height.
 */
class KalmanFilter {
public:
    static constexpr int STATE_DIM = 8;
    static constexpr int MEASURE_DIM = 4;

    using StateVec = std::array<float, STATE_DIM>;
    using MeasureVec = std::array<float, MEASURE_DIM>;
    using StateCov = std::array<std::array<float, STATE_DIM>, STATE_DIM>;
    using MeasureCov = std::array<std::array<float, MEASURE_DIM>, MEASURE_DIM>;

    KalmanFilter();

    // Initialize track from first measurement [x, y, a, h]
    void initiate(const MeasureVec& measurement, StateVec& mean, StateCov& covariance);

    // Predict next state
    void predict(StateVec& mean, StateCov& covariance);

    // Update state with measurement
    void update(const MeasureVec& measurement, StateVec& mean, StateCov& covariance);

    // Compute gating distance (squared Mahalanobis distance)
    float gatingDistance(const StateVec& mean, const StateCov& covariance,
                         const MeasureVec& measurement, bool onlyPosition = false);

private:
    // Motion model: state transition matrix F
    std::array<std::array<float, STATE_DIM>, STATE_DIM> F_;
    // Observation model: measurement matrix H (MEASURE_DIM x STATE_DIM)
    std::array<std::array<float, STATE_DIM>, MEASURE_DIM> H_;
    // Process noise
    float stdWeightPosition_;
    float stdWeightVelocity_;
    // Measurement noise
    float stdWeightMeasure_;
};

} // namespace safetycore_v2

#endif // SAFETYCORE_V2_KALMAN_FILTER_H
