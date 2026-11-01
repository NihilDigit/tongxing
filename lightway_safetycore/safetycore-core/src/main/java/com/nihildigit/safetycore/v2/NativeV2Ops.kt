package com.nihildigit.safetycore.v2

/**
 * JNI interface for SafetyCore V2 native operations.
 */
object NativeV2Ops {
    private var loaded = false

    init {
        loaded = try {
            System.loadLibrary("safetycore_v2")
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    /**
     * Initialize the V2 native module with configuration.
     */
    external fun init(
        distanceCritical: Float,
        distanceHigh: Float,
        distanceMedium: Float,
        ttcCritical: Float,
        ttcMin: Float,
        ttcMax: Float,
        lambdaMotion: Float,
        lambdaAppearance: Float,
        mahalanobisThreshold: Float,
        cosineThreshold: Float,
        maxAge: Int,
        nInit: Int,
        maxSizeChange: Float,
        arLogThreshold: Float,
        fps: Float,
        maxApproachSpeed: Float,
        minBoxAreaPx: Int,
        maxBoxAreaRatio: Float,
        centerRegionRatio: Float,
        cameraHeight: Float,
        pitchDeg: Float
    ): Boolean

    /**
     * Reset tracker state.
     */
    external fun reset()

    /**
     * Update scale estimate from segmentation and depth.
     * @return [scaleFactor, stable (0/1), validFrames]
     */
    external fun updateScale(
        segClasses: ByteArray,
        depthRel: FloatArray,
        width: Int,
        height: Int
    ): FloatArray?

    /**
     * Extract obstacles from detections.
     * @return Flattened obstacle data [x, y, w, h, classId, confidence, depthMedian, depthStd, source] per obstacle
     */
    external fun extractObstaclesFromDetections(
        boxes: FloatArray,
        scores: FloatArray,
        classIds: IntArray,
        depthRel: FloatArray,
        width: Int,
        height: Int
    ): FloatArray?

    /**
     * Extract obstacles from segmentation.
     * @return Flattened obstacle data
     */
    external fun extractObstaclesFromSegmentation(
        segClasses: ByteArray,
        depthRel: FloatArray,
        width: Int,
        height: Int,
        minArea: Int
    ): FloatArray?

    /**
     * Update tracker with obstacles and features.
     * @return Flattened track data
     * [trackId, x, y, w, h, classId, classSource, confidence, depthMeters, vx, vy, approachSpeed, timeSinceUpdate, hits, age, confirmed] per track
     */
    external fun updateTracker(
        obstacleData: FloatArray,
        features: FloatArray
    ): FloatArray?

    /**
     * Assess danger for tracks.
     * @return Flattened threat data [trackId, level, distanceMeters, ttcSeconds, score] per threat
     */
    external fun assessDanger(
        trackData: FloatArray,
        scaleFactor: Float,
        fps: Float,
        frameWidth: Int,
        frameHeight: Int
    ): FloatArray?

    /**
     * Fused tracking + danger assessment to reduce JNI crossings.
     * @return [trackCount, threatCount, tracks..., threats...]
     */
    external fun updateTrackerAndAssessDanger(
        obstacleData: FloatArray,
        features: FloatArray,
        scaleFactor: Float,
        fps: Float,
        frameWidth: Int,
        frameHeight: Int
    ): FloatArray?
}
