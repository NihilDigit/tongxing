package com.nihildigit.safetycore.core

enum class BackendMode { QNN_ONLY, DEFAULT, GPU_ONLY, CPU_ONLY }

data class SdkRuntimeConfig(
    // 默认使用自适应委托策略（QNN->GPU->CPU），避免在不支持 QNN 的设备上初始化失败
    val backendMode: BackendMode = BackendMode.DEFAULT
)

data class ModelAssets(
    val detect: String = "yolox-yolo-x-w8a8.tflite",
    val depth: String = "midas-midas-v2-w8a8.tflite"
)

/**
 * Model input sizes for detection and depth models.
 * Camera frame is 640×480 (RGBA_8888).
 */
data class ModelSizes(
    val cameraFrame: Size = Size(640, 480),
    val yolox: Size = Size(640, 640),
    val midas: Size = Size(256, 256)
)

data class YoloNormConfig(
    val scoreThresh: Float = 0.4f,
    val iouThresh: Float = 0.2f,
    val topK: Int = 10
)

data class FusionConfig(
    // ===== Stage 2A (Detection → Obstacles / Depth filtering) =====
    // Depth-rank thresholds for Stage 2A filtering (rank: 0=farthest, 1=nearest)
    val detDepthRankThreshHigh: Float = 0.5f,   // person/vehicle
    val detDepthRankThreshLow: Float = 0.7f,    // other classes
    val detDepthRankThreshIgnore: Float = 1.0f, // signs/lights (effectively filtered)

    // ===== Stage 3 (Fusion - Simplified) =====
    // IoU threshold for filtering depth obstacles against detection obstacles.
    // If a depth obstacle has IoU > threshold with any detection, it is discarded.
    // Higher = stricter filtering (keep more depth obstacles). Lower = more aggressive filtering.
    val fusionIouThresh: Float = 0.05f
)

/**
 * Stage 2B (Depth → Obstacle) configuration for near-mask thresholding, morphology,
 * and connected-component filtering.
 *
 * All operations are performed in frame-relative depth rank space:
 * - rank = 0: farthest
 * - rank = 1: nearest
 *
 * This config is intended to be runtime-tunable by inspection tools.
 */
data class Stage2BDepthConfig(
    // Near-mask threshold in rank space: pixels with rank >= nearThreshold are considered "near".
    val nearThreshold: Float = 0.7f,

    // Suppression bands to avoid systematic artifacts (floor/road band near, sky noise).
    // Ratios are relative to work-resolution height [0,1].
    val ignoreBottomRatio: Float = 0.30f,
    val ignoreTopRatio: Float = 0.05f,

    // Suppression bands to avoid corridor boundary walls at the edges.
    // Ratios are relative to work-resolution width [0,1].
    val ignoreLeftRatio: Float = 0.20f,
    val ignoreRightRatio: Float = 0.20f,

    // If near pixels dominate the eligible region, suppress mask to avoid full-frame spikes.
    // Range [0,1]. Example: 0.35 means if >35% of eligible pixels are near, clear near-mask.
    val maxNearShare: Float = 0.30f,

    // Morphology kernel sizes (odd positive integers recommended).
    // open: remove small speckle noise
    // close: fill small holes / connect fragmented blobs
    val morphOpenKernel: Int = 3,
    val morphCloseKernel: Int = 7,

    // Minimum connected-component area (in work-resolution pixels) to keep as a depth obstacle.
    val minAreaPx: Int = 20,

    // Maximum connected-component area to keep, expressed as a ratio of eligible pixels.
    // This is a safeguard against large near-field blobs (e.g., walking close to a wall/building)
    // that would otherwise produce huge, persistent bboxes and dominate downstream fusion/tracking.
    //
    // Ratio is applied in work resolution, on the eligible region AFTER ignoreTop/Bottom suppression:
    // comp.area / eligiblePx <= maxAreaRatio
    //
    // Range [0,1]. Set to 1.0 to effectively disable.
    val maxAreaRatio: Float = 0.25f,

    // Work resolution scale divider (2 means work size = frame/2).
    // Kept configurable for experimentation/perf; must be >= 1.
    val workScaleDiv: Int = 2
)



/**
 * Tracking configuration with simplified Hungarian assignment.
 * Simplified Stage4: IoU + center distance only; no depthRank in matching.
 * No Kalman prediction in association; no second-chance fallback.
 */
data class TrackingConfig(
    // Ghost track: retain IDs across brief dropouts (5 frames)
    val maxMisses: Int = 5,
    // DEPRECATED (simplify-depthtrend-only-pipeline): EWMA smoothing removed
    // Track state now updated directly from observations without EMA smoothing.
    // This field is retained for backward compatibility with existing configs but no longer used.
    @Deprecated(
        "EWMA smoothing removed per simplify-depthtrend-only-pipeline change",
        level = DeprecationLevel.WARNING
    )
    val ewmaAlpha: Float = 0.6f,
    // Hungarian cost threshold
    val costThresh: Float = 1.25f,

    // Simplified Hungarian cost: IoU + center distance only
    // (depthRank and velocity terms removed per Simplify Stage4 change)
    val costWeightIou: Float = 1.0f,         // Weight for (1 - IoU) term

    // Center distance normalization factor (expected max displacement per frame, for reference only)
    val maxCentroidDispPxPerFrame: Float = 90f,

    // History buffer size: 10 frames (reduced from 12)
    val historyBufferSize: Int = 10,

    // Kalman covariance update interval (update every N frames; 1=every frame, 3=every 3rd frame)
    // Reduces CPU overhead while maintaining tracking accuracy
    val covarianceUpdateInterval: Int = 3
)


/**
 * Configuration for multisignal heuristic threat assessment.
 * Combines proximity (depthRank), approach trend (depthTrend), and size trend (areaTrend).
 * per update-threat-scoring-multisignal change.
 *
 * Algorithm:
 *   prox = sigmoid(proximityAlpha * (depthRank - proximityTau))
 *   trend = clamp( max(depthTrend, 0) / trendScale, 0, 1 )
 *   area  = clamp( max(areaTrend, 0) / areaScale, 0, 1 )
 *   score = clamp( prox * (proximityBase + weightTrend * trend + weightArea * area), 0, 1 )
 */
data class HeuristicThreatConfig(
    // Thresholds for threat level classification
    val criticalThreshold: Float = 0.85f,
    val highThreshold: Float = 0.62f,
    val mediumThreshold: Float = 0.40f,

    // ===== Hysteresis / anti-spike =====
    // If enabled, require N consecutive frames meeting HIGH/CRITICAL before promoting to that level.
    // This is a post-processing guard at the assessor output layer; it does not change the raw score.
    // Default: 2-frame confirmation for HIGH/CRITICAL.
    val enableLevelHysteresis: Boolean = true,
    val highConfirmFrames: Int = 1,
    val criticalConfirmFrames: Int = 2,

    // ===== Multisignal proximity gating + trend boosting (update-threat-scoring-multisignal) =====
    // Proximity function: sigmoid gate based on depthRank
    // prox = sigmoid(proximityAlpha * (depthRank - proximityTau))
    val proximityAlpha: Float = 12.0f,
    val proximityTau: Float = 0.65f,
    // Base score contribution when prox is high
    val proximityBase: Float = 0.45f,

    // Trend normalization scales: depthTrend and areaTrend are divided by these before weighting
    val trendScale: Float = 0.12f,
    val areaScale: Float = 0.60f,

    // Trend signal weights
    val weightTrend: Float = 0.50f,
    val weightArea: Float = 0.20f,

    // ===== DepthRank logistic weighting (deprecated - superseded by proximity gating) =====
    // If enabled, apply a logistic gain based on depthRank to amplify close approach cases.
    // gain = 1 + k * sigmoid(alpha * (rank - tau))
    // DEPRECATED: Replaced by proximity gating in multisignal model.
    // Retained for backward compatibility; will be ignored if proximity gating is active.
    val enableDepthRankLogistic: Boolean = true,
    val depthRankLogisticK: Float = 1.0f,
    val depthRankLogisticAlpha: Float = 10.0f,
    val depthRankLogisticTau: Float = 0.75f
)

// ThreatAssessorMode enum removed - only HEURISTIC mode is supported now

/**
 * Camera intrinsics for 3D reconstruction.
 * For better accuracy, obtain from CameraX API: imageProxy.imageInfo.intrinsicMatrix
 */
data class CameraIntrinsics(
    val fx: Float = 640f,     // Focal length x (pixels)
    val fy: Float = 640f,     // Focal length y (pixels)
    val cx: Float = 320f,     // Principal point x (pixels)
    val cy: Float = 240f      // Principal point y (pixels)
) {
    /**
     * Compute inverse intrinsic matrix K^-1 for pixel-to-ray transformation.
     * Returns [3x3] matrix flattened row-major: [m00, m01, m02, m10, m11, m12, m20, m21, m22]
     */
    fun inverseMatrix(): FloatArray {
        return floatArrayOf(
            1f / fx, 0f,      -cx / fx,
            0f,      1f / fy, -cy / fy,
            0f,      0f,      1f
        )
    }
}


data class DebugFlags(
    val enableOverlay: Boolean = true,
    val allowDefaultBackend: Boolean = false,
    val showBoxes: Boolean = true,
    val showDepth: Boolean = false,
    val showThreats: Boolean = false,
    val showImu: Boolean = false,
    // Real-time preview visualization controls
    val enableRealtimeVisualData: Boolean = true,     // Generate visual data for depth/detect preview
    val showDepthLegend: Boolean = true,              // Show depth min/max legend in preview
    val enableTrackingDebug: Boolean = false,          // Generate tracking debug data (match pairs, costs)
    val schedulerMinIntervalMs: Long? = null,          // Override scheduler sleep; set 0 to disable
    val enableFusionProfiling: Boolean = false,        // Fine-grained fusion timings (benchmark only)
    val forceKotlinOps: Boolean = false                // Disable native fast-paths to measure Kotlin pipeline
)

data class ImuConfig(
    val enableCompensation: Boolean = false,
    val alignmentToleranceNs: Long = 20_000_000L, // 20ms
    val maxSampleAgeNs: Long = 200_000_000L,      // 200ms
    val maxIntegrationWindowNs: Long = 200_000_000L, // 200ms
    val accelAlpha: Float = 0.98f,                // gyro weight in complementary filter
    val accelMinG: Float = 7.0f,                   // accel magnitude lower bound
    val accelMaxG: Float = 12.0f                   // accel magnitude upper bound
)

/**
 * COCO-80 类别名称映射（用于 YOLOX 检测可视化标签）。
 */
object Coco80 {
    val NAMES = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe",
        "backpack", "umbrella", "handbag", "tie", "suitcase",
        "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
        "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
        "banana", "apple", "sandwich", "orange", "broccoli", "carrot",
        "hot dog", "pizza", "donut", "cake",
        "chair", "couch", "potted plant", "bed", "dining table", "toilet",
        "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator",
        "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
}
