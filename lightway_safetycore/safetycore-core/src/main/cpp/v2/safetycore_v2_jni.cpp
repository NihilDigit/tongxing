#include <jni.h>
#include <android/log.h>
#include <vector>
#include <array>
#include <cstring>

#include "types.h"
#include "kalman_filter.h"
#include "hungarian.h"
#include "scale_recovery.h"
#include "obstacle_extractor.h"
#include "deep_sort_tracker.h"
#include "danger_assessor.h"

#define LOG_TAG "safetycore_v2"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace safetycore_v2;

// Global instances (per-process singleton pattern)
static ConfigV2 g_config;
static std::unique_ptr<ScaleRecovery> g_scaleRecovery;
static std::unique_ptr<ObstacleExtractor> g_obstacleExtractor;
static std::unique_ptr<DeepSortTracker> g_tracker;
static std::unique_ptr<DangerAssessor> g_dangerAssessor;
static bool g_initialized = false;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_init(
    JNIEnv* env, jclass clazz,
    jfloat distanceCritical, jfloat distanceHigh, jfloat distanceMedium,
    jfloat ttcCritical, jfloat ttcMin, jfloat ttcMax,
    jfloat lambdaMotion, jfloat lambdaAppearance,
    jfloat mahalanobisThreshold, jfloat cosineThreshold,
    jint maxAge, jint nInit,
    jfloat maxSizeChange, jfloat arLogThreshold,
    jfloat fps,
    jfloat maxApproachSpeed,
    jint minBoxAreaPx, jfloat maxBoxAreaRatio, jfloat centerRegionRatio,
    jfloat cameraHeight, jfloat pitchDeg) {

    g_config.distanceCritical = distanceCritical;
    g_config.distanceHigh = distanceHigh;
    g_config.distanceMedium = distanceMedium;
    g_config.ttcCritical = ttcCritical;
    g_config.ttcMinSeconds = ttcMin;
    g_config.ttcMaxSeconds = ttcMax;
    g_config.lambdaMotion = lambdaMotion;
    g_config.lambdaAppearance = lambdaAppearance;
    g_config.mahalanobisThreshold = mahalanobisThreshold;
    g_config.cosineThreshold = cosineThreshold;
    g_config.maxAge = maxAge;
    g_config.nInit = nInit;
    g_config.maxSizeChange = maxSizeChange;
    g_config.arLogThreshold = arLogThreshold;
    g_config.fps = fps;
    g_config.maxApproachSpeed = maxApproachSpeed;
    g_config.minBoxAreaPx = minBoxAreaPx;
    g_config.maxBoxAreaRatio = maxBoxAreaRatio;
    g_config.centerRegionRatio = centerRegionRatio;
    g_config.cameraHeightDefault = cameraHeight;
    g_config.fixedPitchDeg = pitchDeg;

    g_scaleRecovery = std::make_unique<ScaleRecovery>(g_config);
    g_obstacleExtractor = std::make_unique<ObstacleExtractor>(g_config);
    g_tracker = std::make_unique<DeepSortTracker>(g_config);
    g_dangerAssessor = std::make_unique<DangerAssessor>(g_config);
    g_initialized = true;

    ALOGD("SafetyCore V2 initialized");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_reset(JNIEnv* env, jclass clazz) {
    if (g_initialized) {
        g_scaleRecovery = std::make_unique<ScaleRecovery>(g_config);
        g_tracker = std::make_unique<DeepSortTracker>(g_config);
        ALOGD("SafetyCore V2 reset");
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_updateScale(
    JNIEnv* env, jclass clazz,
    jbyteArray segClasses, jfloatArray depthRel,
    jint width, jint height) {

    if (!g_initialized || !g_scaleRecovery) {
        return nullptr;
    }

    jbyte* segPtr = env->GetByteArrayElements(segClasses, nullptr);
    jfloat* depthPtr = env->GetFloatArrayElements(depthRel, nullptr);

    ScaleState state = g_scaleRecovery->update(
        reinterpret_cast<uint8_t*>(segPtr),
        depthPtr, width, height);

    env->ReleaseByteArrayElements(segClasses, segPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthRel, depthPtr, JNI_ABORT);

    // Return [scaleFactor, stable (0/1), validFrames]
    jfloatArray result = env->NewFloatArray(3);
    float data[3] = {state.scaleFactor, state.stable ? 1.0f : 0.0f, static_cast<float>(state.validFrames)};
    env->SetFloatArrayRegion(result, 0, 3, data);

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_extractObstaclesFromDetections(
    JNIEnv* env, jclass clazz,
    jfloatArray boxes, jfloatArray scores, jintArray classIds,
    jfloatArray depthRel, jint width, jint height) {

    if (!g_initialized || !g_obstacleExtractor) {
        return nullptr;
    }

    int numDets = env->GetArrayLength(scores);
    if (numDets == 0) {
        return env->NewFloatArray(0);
    }

    jfloat* boxPtr = env->GetFloatArrayElements(boxes, nullptr);
    jfloat* scorePtr = env->GetFloatArrayElements(scores, nullptr);
    jint* classPtr = env->GetIntArrayElements(classIds, nullptr);
    jfloat* depthPtr = env->GetFloatArrayElements(depthRel, nullptr);

    // Build detection list
    std::vector<Detection> detections(numDets);
    for (int i = 0; i < numDets; ++i) {
        detections[i].bbox.x = boxPtr[i * 4 + 0];
        detections[i].bbox.y = boxPtr[i * 4 + 1];
        detections[i].bbox.w = boxPtr[i * 4 + 2];
        detections[i].bbox.h = boxPtr[i * 4 + 3];
        detections[i].score = scorePtr[i];
        detections[i].classId = classPtr[i];
    }

    auto obstacles = g_obstacleExtractor->extractFromDetections(detections, depthPtr, width, height);

    env->ReleaseFloatArrayElements(boxes, boxPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(scores, scorePtr, JNI_ABORT);
    env->ReleaseIntArrayElements(classIds, classPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthRel, depthPtr, JNI_ABORT);

    // Return flattened obstacle data: [x, y, w, h, classId, confidence, depthMedian, depthStd, source] per obstacle
    int stride = 9;
    jfloatArray result = env->NewFloatArray(obstacles.size() * stride);
    std::vector<float> data(obstacles.size() * stride);
    for (size_t i = 0; i < obstacles.size(); ++i) {
        data[i * stride + 0] = obstacles[i].bbox.x;
        data[i * stride + 1] = obstacles[i].bbox.y;
        data[i * stride + 2] = obstacles[i].bbox.w;
        data[i * stride + 3] = obstacles[i].bbox.h;
        data[i * stride + 4] = static_cast<float>(obstacles[i].classId);
        data[i * stride + 5] = obstacles[i].confidence;
        data[i * stride + 6] = obstacles[i].depthMedian;
        data[i * stride + 7] = obstacles[i].depthStd;
        data[i * stride + 8] = static_cast<float>(obstacles[i].source);
    }
    env->SetFloatArrayRegion(result, 0, data.size(), data.data());

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_extractObstaclesFromSegmentation(
    JNIEnv* env, jclass clazz,
    jbyteArray segClasses, jfloatArray depthRel,
    jint width, jint height, jint minArea) {

    if (!g_initialized || !g_obstacleExtractor) {
        return nullptr;
    }

    jbyte* segPtr = env->GetByteArrayElements(segClasses, nullptr);
    jfloat* depthPtr = env->GetFloatArrayElements(depthRel, nullptr);

    auto obstacles = g_obstacleExtractor->extractFromSegmentation(
        reinterpret_cast<uint8_t*>(segPtr), depthPtr, width, height, minArea);

    env->ReleaseByteArrayElements(segClasses, segPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthRel, depthPtr, JNI_ABORT);

    // Return flattened obstacle data
    int stride = 9;
    jfloatArray result = env->NewFloatArray(obstacles.size() * stride);
    std::vector<float> data(obstacles.size() * stride);
    for (size_t i = 0; i < obstacles.size(); ++i) {
        data[i * stride + 0] = obstacles[i].bbox.x;
        data[i * stride + 1] = obstacles[i].bbox.y;
        data[i * stride + 2] = obstacles[i].bbox.w;
        data[i * stride + 3] = obstacles[i].bbox.h;
        data[i * stride + 4] = static_cast<float>(obstacles[i].classId);
        data[i * stride + 5] = obstacles[i].confidence;
        data[i * stride + 6] = obstacles[i].depthMedian;
        data[i * stride + 7] = obstacles[i].depthStd;
        data[i * stride + 8] = static_cast<float>(obstacles[i].source);
    }
    env->SetFloatArrayRegion(result, 0, data.size(), data.data());

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_updateTracker(
    JNIEnv* env, jclass clazz,
    jfloatArray obstacleData, jfloatArray features) {

    if (!g_initialized || !g_tracker) {
        return nullptr;
    }

    int obstacleLen = env->GetArrayLength(obstacleData);
    int featureLen = env->GetArrayLength(features);
    int stride = 9;
    int numObs = obstacleLen / stride;

    if (numObs == 0) {
        return env->NewFloatArray(0);
    }

    jfloat* obsPtr = env->GetFloatArrayElements(obstacleData, nullptr);
    jfloat* featPtr = env->GetFloatArrayElements(features, nullptr);

    // Parse obstacles
    std::vector<ObstacleV2> obstacles(numObs);
    for (int i = 0; i < numObs; ++i) {
        obstacles[i].bbox.x = obsPtr[i * stride + 0];
        obstacles[i].bbox.y = obsPtr[i * stride + 1];
        obstacles[i].bbox.w = obsPtr[i * stride + 2];
        obstacles[i].bbox.h = obsPtr[i * stride + 3];
        obstacles[i].classId = static_cast<int>(obsPtr[i * stride + 4]);
        obstacles[i].confidence = obsPtr[i * stride + 5];
        obstacles[i].depthMedian = obsPtr[i * stride + 6];
        obstacles[i].depthStd = obsPtr[i * stride + 7];
        obstacles[i].source = static_cast<int>(obsPtr[i * stride + 8]);
    }

    // Parse features
    std::vector<std::array<float, 1280>> featureVecs(numObs);
    for (int i = 0; i < numObs; ++i) {
        for (int j = 0; j < 1280; ++j) {
            featureVecs[i][j] = featPtr[i * 1280 + j];
        }
    }

    auto tracks = g_tracker->update(obstacles, featureVecs);

    env->ReleaseFloatArrayElements(obstacleData, obsPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(features, featPtr, JNI_ABORT);

    // Return flattened track data:
    // [trackId, x, y, w, h, classId, classSource, confidence, depthMeters, vx, vy, approachSpeed, timeSinceUpdate, hits, age, confirmed]
    int trackStride = 16;
    jfloatArray result = env->NewFloatArray(tracks.size() * trackStride);
    std::vector<float> data(tracks.size() * trackStride);
    for (size_t i = 0; i < tracks.size(); ++i) {
        data[i * trackStride + 0] = static_cast<float>(tracks[i].trackId);
        data[i * trackStride + 1] = tracks[i].bbox.x;
        data[i * trackStride + 2] = tracks[i].bbox.y;
        data[i * trackStride + 3] = tracks[i].bbox.w;
        data[i * trackStride + 4] = tracks[i].bbox.h;
        data[i * trackStride + 5] = static_cast<float>(tracks[i].classId);
        data[i * trackStride + 6] = static_cast<float>(tracks[i].classSource);
        data[i * trackStride + 7] = tracks[i].confidence;
        data[i * trackStride + 8] = tracks[i].depthMeters;
        data[i * trackStride + 9] = tracks[i].velocityX;
        data[i * trackStride + 10] = tracks[i].velocityY;
        data[i * trackStride + 11] = tracks[i].approachSpeed;
        data[i * trackStride + 12] = static_cast<float>(tracks[i].timeSinceUpdate);
        data[i * trackStride + 13] = static_cast<float>(tracks[i].hits);
        data[i * trackStride + 14] = static_cast<float>(tracks[i].age);
        data[i * trackStride + 15] = tracks[i].confirmed ? 1.0f : 0.0f;
    }
    env->SetFloatArrayRegion(result, 0, data.size(), data.data());

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_assessDanger(
    JNIEnv* env, jclass clazz,
    jfloatArray trackData, jfloat scaleFactor, jfloat fps,
    jint frameWidth, jint frameHeight) {

    if (!g_initialized || !g_dangerAssessor) {
        return nullptr;
    }

    int trackLen = env->GetArrayLength(trackData);
    int trackStride = 16;
    int numTracks = trackLen / trackStride;

    if (numTracks == 0) {
        return env->NewFloatArray(0);
    }

    jfloat* trackPtr = env->GetFloatArrayElements(trackData, nullptr);

    // Parse tracks
    std::vector<TrackedObstacleV2> tracks(numTracks);
    for (int i = 0; i < numTracks; ++i) {
        tracks[i].trackId = static_cast<int>(trackPtr[i * trackStride + 0]);
        tracks[i].bbox.x = trackPtr[i * trackStride + 1];
        tracks[i].bbox.y = trackPtr[i * trackStride + 2];
        tracks[i].bbox.w = trackPtr[i * trackStride + 3];
        tracks[i].bbox.h = trackPtr[i * trackStride + 4];
        tracks[i].classId = static_cast<int>(trackPtr[i * trackStride + 5]);
        tracks[i].classSource = static_cast<int>(trackPtr[i * trackStride + 6]);
        tracks[i].confidence = trackPtr[i * trackStride + 7];
        tracks[i].depthMeters = trackPtr[i * trackStride + 8];
        tracks[i].velocityX = trackPtr[i * trackStride + 9];
        tracks[i].velocityY = trackPtr[i * trackStride + 10];
        tracks[i].approachSpeed = trackPtr[i * trackStride + 11];
        tracks[i].timeSinceUpdate = static_cast<int>(trackPtr[i * trackStride + 12]);
        tracks[i].hits = static_cast<int>(trackPtr[i * trackStride + 13]);
        tracks[i].age = static_cast<int>(trackPtr[i * trackStride + 14]);
        tracks[i].confirmed = trackPtr[i * trackStride + 15] > 0.5f;
    }

    auto threats = g_dangerAssessor->assess(tracks, scaleFactor, fps, frameWidth, frameHeight);

    env->ReleaseFloatArrayElements(trackData, trackPtr, JNI_ABORT);

    // Return flattened threat data: [trackId, level, distanceMeters, ttcSeconds, score] per threat
    int threatStride = 5;
    jfloatArray result = env->NewFloatArray(threats.size() * threatStride);
    std::vector<float> data(threats.size() * threatStride);
    for (size_t i = 0; i < threats.size(); ++i) {
        data[i * threatStride + 0] = static_cast<float>(threats[i].trackId);
        data[i * threatStride + 1] = static_cast<float>(threats[i].level);
        data[i * threatStride + 2] = threats[i].distanceMeters;
        data[i * threatStride + 3] = threats[i].ttcSeconds;
        data[i * threatStride + 4] = threats[i].score;
    }
    env->SetFloatArrayRegion(result, 0, data.size(), data.data());

    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_nihildigit_safetycore_v2_NativeV2Ops_updateTrackerAndAssessDanger(
    JNIEnv* env, jclass clazz,
    jfloatArray obstacleData, jfloatArray features,
    jfloat scaleFactor, jfloat fps,
    jint frameWidth, jint frameHeight) {

    if (!g_initialized || !g_tracker || !g_dangerAssessor) {
        return nullptr;
    }

    int obstacleLen = env->GetArrayLength(obstacleData);
    int stride = 9;
    int numObs = obstacleLen / stride;
    if (numObs == 0) {
        jfloatArray empty = env->NewFloatArray(2);
        float header[2] = {0.0f, 0.0f};
        env->SetFloatArrayRegion(empty, 0, 2, header);
        return empty;
    }

    jfloat* obsPtr = env->GetFloatArrayElements(obstacleData, nullptr);
    jfloat* featPtr = env->GetFloatArrayElements(features, nullptr);

    std::vector<ObstacleV2> obstacles(numObs);
    for (int i = 0; i < numObs; ++i) {
        obstacles[i].bbox.x = obsPtr[i * stride + 0];
        obstacles[i].bbox.y = obsPtr[i * stride + 1];
        obstacles[i].bbox.w = obsPtr[i * stride + 2];
        obstacles[i].bbox.h = obsPtr[i * stride + 3];
        obstacles[i].classId = static_cast<int>(obsPtr[i * stride + 4]);
        obstacles[i].confidence = obsPtr[i * stride + 5];
        obstacles[i].depthMedian = obsPtr[i * stride + 6];
        obstacles[i].depthStd = obsPtr[i * stride + 7];
        obstacles[i].source = static_cast<int>(obsPtr[i * stride + 8]);
    }

    std::vector<std::array<float, 1280>> featureVecs(numObs);
    for (int i = 0; i < numObs; ++i) {
        for (int j = 0; j < 1280; ++j) {
            featureVecs[i][j] = featPtr[i * 1280 + j];
        }
    }

    auto tracks = g_tracker->update(obstacles, featureVecs);
    auto threats = g_dangerAssessor->assess(tracks, scaleFactor, fps, frameWidth, frameHeight);

    env->ReleaseFloatArrayElements(obstacleData, obsPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(features, featPtr, JNI_ABORT);

    const int trackStride = 16;
    const int threatStride = 5;
    const int header = 2;
    const int total = header + tracks.size() * trackStride + threats.size() * threatStride;
    jfloatArray result = env->NewFloatArray(total);
    std::vector<float> data(total);
    data[0] = static_cast<float>(tracks.size());
    data[1] = static_cast<float>(threats.size());

    int offset = header;
    for (size_t i = 0; i < tracks.size(); ++i) {
        data[offset + i * trackStride + 0] = static_cast<float>(tracks[i].trackId);
        data[offset + i * trackStride + 1] = tracks[i].bbox.x;
        data[offset + i * trackStride + 2] = tracks[i].bbox.y;
        data[offset + i * trackStride + 3] = tracks[i].bbox.w;
        data[offset + i * trackStride + 4] = tracks[i].bbox.h;
        data[offset + i * trackStride + 5] = static_cast<float>(tracks[i].classId);
        data[offset + i * trackStride + 6] = static_cast<float>(tracks[i].classSource);
        data[offset + i * trackStride + 7] = tracks[i].confidence;
        data[offset + i * trackStride + 8] = tracks[i].depthMeters;
        data[offset + i * trackStride + 9] = tracks[i].velocityX;
        data[offset + i * trackStride + 10] = tracks[i].velocityY;
        data[offset + i * trackStride + 11] = tracks[i].approachSpeed;
        data[offset + i * trackStride + 12] = static_cast<float>(tracks[i].timeSinceUpdate);
        data[offset + i * trackStride + 13] = static_cast<float>(tracks[i].hits);
        data[offset + i * trackStride + 14] = static_cast<float>(tracks[i].age);
        data[offset + i * trackStride + 15] = tracks[i].confirmed ? 1.0f : 0.0f;
    }

    offset += tracks.size() * trackStride;
    for (size_t i = 0; i < threats.size(); ++i) {
        data[offset + i * threatStride + 0] = static_cast<float>(threats[i].trackId);
        data[offset + i * threatStride + 1] = static_cast<float>(threats[i].level);
        data[offset + i * threatStride + 2] = threats[i].distanceMeters;
        data[offset + i * threatStride + 3] = threats[i].ttcSeconds;
        data[offset + i * threatStride + 4] = threats[i].score;
    }

    env->SetFloatArrayRegion(result, 0, total, data.data());
    return result;
}

} // extern "C"
