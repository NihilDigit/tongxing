#include "kalman_filter.h"
#include <algorithm>
#include <cstring>

namespace safetycore_v2 {

KalmanFilter::KalmanFilter()
    : stdWeightPosition_(1.0f / 20.0f)
    , stdWeightVelocity_(1.0f / 160.0f)
    , stdWeightMeasure_(1.0f / 20.0f) {
    // Initialize state transition matrix F (identity + velocity terms)
    std::memset(&F_, 0, sizeof(F_));
    for (int i = 0; i < STATE_DIM; ++i) {
        F_[i][i] = 1.0f;
    }
    // Add velocity terms: x += vx, y += vy, a += va, h += vh
    F_[0][4] = 1.0f;  // x += vx
    F_[1][5] = 1.0f;  // y += vy
    F_[2][6] = 1.0f;  // a += va
    F_[3][7] = 1.0f;  // h += vh

    // Initialize measurement matrix H
    std::memset(&H_, 0, sizeof(H_));
    H_[0][0] = 1.0f;  // measure x
    H_[1][1] = 1.0f;  // measure y
    H_[2][2] = 1.0f;  // measure a
    H_[3][3] = 1.0f;  // measure h
}

void KalmanFilter::initiate(const MeasureVec& measurement, StateVec& mean, StateCov& covariance) {
    // Initialize mean: [x, y, a, h, 0, 0, 0, 0]
    mean[0] = measurement[0];  // x
    mean[1] = measurement[1];  // y
    mean[2] = measurement[2];  // a (aspect ratio)
    mean[3] = measurement[3];  // h (height)
    mean[4] = 0.0f;  // vx
    mean[5] = 0.0f;  // vy
    mean[6] = 0.0f;  // va
    mean[7] = 0.0f;  // vh

    // Initialize covariance
    float h = measurement[3];
    float std_pos = 2.0f * stdWeightPosition_ * h;
    float std_vel = 10.0f * stdWeightVelocity_ * h;

    std::memset(&covariance, 0, sizeof(covariance));
    covariance[0][0] = std_pos * std_pos;
    covariance[1][1] = std_pos * std_pos;
    covariance[2][2] = 1e-2f;  // aspect ratio variance
    covariance[3][3] = std_pos * std_pos;
    covariance[4][4] = std_vel * std_vel;
    covariance[5][5] = std_vel * std_vel;
    covariance[6][6] = 1e-5f;  // aspect ratio velocity variance
    covariance[7][7] = std_vel * std_vel;
}

void KalmanFilter::predict(StateVec& mean, StateCov& covariance) {
    // Predict mean: mean = F * mean
    StateVec newMean;
    for (int i = 0; i < STATE_DIM; ++i) {
        newMean[i] = 0.0f;
        for (int j = 0; j < STATE_DIM; ++j) {
            newMean[i] += F_[i][j] * mean[j];
        }
    }
    mean = newMean;

    // Process noise Q
    float h = mean[3];
    float std_pos = stdWeightPosition_ * h;
    float std_vel = stdWeightVelocity_ * h;

    StateCov Q;
    std::memset(&Q, 0, sizeof(Q));
    Q[0][0] = std_pos * std_pos;
    Q[1][1] = std_pos * std_pos;
    Q[2][2] = 1e-2f;
    Q[3][3] = std_pos * std_pos;
    Q[4][4] = std_vel * std_vel;
    Q[5][5] = std_vel * std_vel;
    Q[6][6] = 1e-5f;
    Q[7][7] = std_vel * std_vel;

    // Predict covariance: P = F * P * F^T + Q
    StateCov FP;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < STATE_DIM; ++j) {
            FP[i][j] = 0.0f;
            for (int k = 0; k < STATE_DIM; ++k) {
                FP[i][j] += F_[i][k] * covariance[k][j];
            }
        }
    }

    StateCov newCov;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < STATE_DIM; ++j) {
            newCov[i][j] = Q[i][j];
            for (int k = 0; k < STATE_DIM; ++k) {
                newCov[i][j] += FP[i][k] * F_[j][k];  // F^T
            }
        }
    }
    covariance = newCov;
}

void KalmanFilter::update(const MeasureVec& measurement, StateVec& mean, StateCov& covariance) {
    // Innovation: y = z - H * x
    MeasureVec innovation;
    for (int i = 0; i < MEASURE_DIM; ++i) {
        innovation[i] = measurement[i];
        for (int j = 0; j < STATE_DIM; ++j) {
            innovation[i] -= H_[i][j] * mean[j];
        }
    }

    // Measurement noise R
    float h = mean[3];
    float std_meas = stdWeightMeasure_ * h;
    MeasureCov R;
    std::memset(&R, 0, sizeof(R));
    R[0][0] = std_meas * std_meas;
    R[1][1] = std_meas * std_meas;
    R[2][2] = 1e-1f;
    R[3][3] = std_meas * std_meas;

    // Innovation covariance: S = H * P * H^T + R
    // First compute P * H^T
    std::array<std::array<float, MEASURE_DIM>, STATE_DIM> PHt;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < MEASURE_DIM; ++j) {
            PHt[i][j] = 0.0f;
            for (int k = 0; k < STATE_DIM; ++k) {
                PHt[i][j] += covariance[i][k] * H_[j][k];
            }
        }
    }

    // S = H * PHt + R
    MeasureCov S;
    for (int i = 0; i < MEASURE_DIM; ++i) {
        for (int j = 0; j < MEASURE_DIM; ++j) {
            S[i][j] = R[i][j];
            for (int k = 0; k < STATE_DIM; ++k) {
                S[i][j] += H_[i][k] * PHt[k][j];
            }
        }
    }

    // Kalman gain: K = P * H^T * S^-1
    // For 4x4 matrix, use simple inversion
    MeasureCov Sinv;
    // Simple diagonal approximation for stability
    for (int i = 0; i < MEASURE_DIM; ++i) {
        for (int j = 0; j < MEASURE_DIM; ++j) {
            Sinv[i][j] = (i == j) ? 1.0f / std::max(S[i][i], 1e-6f) : 0.0f;
        }
    }

    // K = PHt * Sinv
    std::array<std::array<float, MEASURE_DIM>, STATE_DIM> K;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < MEASURE_DIM; ++j) {
            K[i][j] = 0.0f;
            for (int k = 0; k < MEASURE_DIM; ++k) {
                K[i][j] += PHt[i][k] * Sinv[k][j];
            }
        }
    }

    // Update mean: x = x + K * y
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < MEASURE_DIM; ++j) {
            mean[i] += K[i][j] * innovation[j];
        }
    }

    // Update covariance: P = (I - K * H) * P
    StateCov KH;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < STATE_DIM; ++j) {
            KH[i][j] = 0.0f;
            for (int k = 0; k < MEASURE_DIM; ++k) {
                KH[i][j] += K[i][k] * H_[k][j];
            }
        }
    }

    StateCov newCov;
    for (int i = 0; i < STATE_DIM; ++i) {
        for (int j = 0; j < STATE_DIM; ++j) {
            newCov[i][j] = 0.0f;
            for (int k = 0; k < STATE_DIM; ++k) {
                float IKH = (i == k ? 1.0f : 0.0f) - KH[i][k];
                newCov[i][j] += IKH * covariance[k][j];
            }
        }
    }
    covariance = newCov;
}

float KalmanFilter::gatingDistance(const StateVec& mean, const StateCov& covariance,
                                   const MeasureVec& measurement, bool onlyPosition) {
    // Project state to measurement space
    MeasureVec projected;
    for (int i = 0; i < MEASURE_DIM; ++i) {
        projected[i] = 0.0f;
        for (int j = 0; j < STATE_DIM; ++j) {
            projected[i] += H_[i][j] * mean[j];
        }
    }

    // Innovation
    MeasureVec diff;
    for (int i = 0; i < MEASURE_DIM; ++i) {
        diff[i] = measurement[i] - projected[i];
    }

    // Compute innovation covariance S = H * P * H^T + R
    float h = mean[3];
    float std_meas = stdWeightMeasure_ * h;

    // Simplified: use diagonal approximation
    int dim = onlyPosition ? 2 : MEASURE_DIM;
    float dist = 0.0f;
    for (int i = 0; i < dim; ++i) {
        float var = covariance[i][i] + std_meas * std_meas;
        dist += diff[i] * diff[i] / std::max(var, 1e-6f);
    }

    return dist;
}

} // namespace safetycore_v2
