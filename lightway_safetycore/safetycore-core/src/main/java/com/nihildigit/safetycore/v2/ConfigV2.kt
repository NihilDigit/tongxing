package com.nihildigit.safetycore.v2

/**
 * Configuration for SafetyCore V2.
 */
data class ConfigV2(
    // Distance thresholds (meters)
    val distanceCritical: Float = 2.0f,
    val distanceHigh: Float = 4.0f,
    val distanceMedium: Float = 6.0f,

    // TTC threshold (seconds)
    val ttcCritical: Float = 1.0f,

    // DeepSORT parameters
    val lambdaMotion: Float = 0.5f,
    val lambdaAppearance: Float = 0.5f,
    val mahalanobisThreshold: Float = 9.4877f,  // Chi-square 95% confidence
    val cosineThreshold: Float = 0.3f,
    val maxAge: Int = 30,
    val nInit: Int = 5,
    val maxSizeChange: Float = 0.5f,
    val arLogThreshold: Float = 0.5f,

    // Scale recovery parameters
    val cameraHeightDefault: Float = 1.2f,      // meters
    val fixedPitchDeg: Float = 35.0f,           // degrees

    // Model asset paths
    val depthModelPath: String = "midas-midas-v2-w8a8.tflite",
    val detectModelPath: String = "yolox-yolo-x-w8a8.tflite",
    val ffnetModelPath: String = "ffnet_78s_lowres-ffnet-78s-lowres-w8a8.tflite",
    val featureModelPath: String = "mobilenet_v2_feature_extractor_w8a8.tflite",

    // Processing parameters
    val minSegmentationArea: Int = 500,
    val fps: Float = 15.0f,
    val maxObstaclesForFeatures: Int = 12,  // Performance guardrail: extract features for top-N obstacles
    val featureBatchSize: Int = 4,

    // Detection/segmentation fusion heuristics
    val walkableClassIds: IntArray = intArrayOf(0, 1, 9),
    val detWalkableRatioMax: Float = 0.7f,
    val detConfidenceHigh: Float = 0.6f,
    val segMaskMarginRatio: Float = 0.1f,

    // Danger assessment heuristics
    val minBoxAreaPx: Int = 100,
    val maxBoxAreaRatio: Float = 0.4f,
    val centerRegionRatio: Float = 0.2f,
    val ttcMinSeconds: Float = 0.1f,
    val ttcMaxSeconds: Float = 30.0f,
    val maxApproachSpeed: Float = 15.0f
)
