package com.nihildigit.safetycore

import android.content.Context
import android.os.SystemClock
import com.nihildigit.safetycore.core.BackendMode
import com.nihildigit.safetycore.core.CameraIntrinsics
import com.nihildigit.safetycore.core.DebugFlags
import com.nihildigit.safetycore.core.FusionConfig
import com.nihildigit.safetycore.core.HeuristicThreatConfig
import com.nihildigit.safetycore.core.ImuConfig
import com.nihildigit.safetycore.core.ModelAssets
import com.nihildigit.safetycore.core.ModelSizes
import com.nihildigit.safetycore.core.SdkRuntimeConfig
import com.nihildigit.safetycore.core.Stage2BDepthConfig
import com.nihildigit.safetycore.core.Threat
import com.nihildigit.safetycore.core.ThreatDirection
import com.nihildigit.safetycore.core.ThreatLevel
import com.nihildigit.safetycore.core.Track
import com.nihildigit.safetycore.core.TrackingConfig
import com.nihildigit.safetycore.core.YoloNormConfig
import com.nihildigit.safetycore.orchestrator.FrameProcessor
import com.nihildigit.safetycore.orchestrator.FrameResult
import com.nihildigit.safetycore.orchestrator.TelemetryData
import com.nihildigit.safetycore.pipeline.SchedulerStats
import com.nihildigit.safetycore.v2.ConfigV2
import com.nihildigit.safetycore.v2.FrameProcessorV2
import com.nihildigit.safetycore.v2.SafetyCoreV2Engine
import com.nihildigit.safetycore.v2.types.DangerLevelV2
import com.nihildigit.safetycore.v2.types.FusionResultV2
import com.nihildigit.safetycore.v2.types.TrackedObstacleV2
import com.nihildigit.safetycore.depth.DepthNormConfig

/**
 * SafetyCore Engine - compatibility entry that now runs SafetyCore V2 underneath.
 *
 * App/Overlay still depends on FrameProcessor/FrameResult API, but V1 runtime pipeline
 * has been retired. Calls are bridged to FrameProcessorV2 and converted back to
 * compatibility models.
 */
object SafetyCoreEngine {

    /**
     * Create a FrameProcessor for real-time camera frame processing.
     *
     * @param context Android Context for loading assets and initializing SDK
     * @param config SafetyCore configuration
     * @return FrameProcessor instance ready for use
     */
    fun createFrameProcessor(
        context: Context,
        config: SafetyCoreConfig = SafetyCoreConfig()
    ): FrameProcessor {
        val v2Config = ConfigV2(
            depthModelPath = config.modelAssets.depth,
            detectModelPath = config.modelAssets.detect
        )
        val v2Processor = SafetyCoreV2Engine.createFrameProcessor(context.applicationContext, v2Config)
        return V2FrameProcessorAdapter(v2Processor)
    }
}

private class V2FrameProcessorAdapter(
    private val v2: FrameProcessorV2
) : FrameProcessor {
    private var started = false

    override var uiListener: FrameProcessor.UiListener? = null
    override var statsListener: FrameProcessor.StatsListener? = null

    @Synchronized override fun start() {
        started = true
    }

    @Synchronized override fun stop() {
        started = false
    }

    @Synchronized override fun onFrame(frame: android.graphics.Bitmap, tsNanos: Long) {
        if (!started) return
        val result = v2.processFrame(frame)
        val frameResult = result.toFrameResult(tsNanos)
        uiListener?.onFrameResult(frameResult)
        statsListener?.onSchedulerStats(
            SchedulerStats(
                mode = "v2",
                processMs = result.timings.totalMs.toInt(),
                sensorLatencyMs = ((SystemClock.elapsedRealtimeNanos() - tsNanos) / 1_000_000L).toInt()
            )
        )
    }

    @Synchronized override fun close() {
        started = false
        v2.close()
    }

    private fun FusionResultV2.toFrameResult(tsNanos: Long): FrameResult {
        val tracksV1 = tracks.map { it.toTrack() }
        val trackMap = tracksV1.associateBy { it.id }
        val threatsV1 = threats.map { it.toThreat(trackMap, trackSpaceWidth, inputFrameWidth) }
        val telemetry = TelemetryData(
            inferenceMs = (timings.depthInferMs + timings.detectInferMs + timings.segInferMs).toInt(),
            backend = "V2",
            e2eMs = timings.totalMs.toInt(),
            depthInferMs = timings.depthInferMs.toInt(),
            detectInferMs = timings.detectInferMs.toInt(),
            onInferenceMs = timings.totalMs.toInt()
        )
        return FrameResult(
            tracks = tracksV1,
            threats = threatsV1,
            tsNanos = tsNanos,
            telemetry = telemetry
        )
    }

    private fun TrackedObstacleV2.toTrack(): Track {
        return Track(
            id = trackId,
            bbox = bbox.copyOf(),
            confidence = confidence,
            classId = classId
        )
    }

    private fun com.nihildigit.safetycore.v2.types.ThreatResultV2.toThreat(
        trackMap: Map<Int, Track>,
        trackSpaceWidth: Int,
        inputFrameWidth: Int
    ): Threat {
        val mappedLevel = when (level) {
            DangerLevelV2.NONE -> ThreatLevel.LOW
            DangerLevelV2.LOW -> ThreatLevel.LOW
            DangerLevelV2.MEDIUM -> ThreatLevel.MEDIUM
            DangerLevelV2.HIGH -> ThreatLevel.HIGH
            DangerLevelV2.CRITICAL -> ThreatLevel.CRITICAL
        }
        val direction = computeDirection(trackMap[trackId], trackSpaceWidth, inputFrameWidth)
        return Threat(
            trackId = trackId,
            level = mappedLevel,
            reason = "v2",
            score = score,
            direction = direction
        )
    }

    private fun computeDirection(track: Track?, trackSpaceWidth: Int, inputFrameWidth: Int): ThreatDirection {
        val bbox = track?.bbox ?: return ThreatDirection.FRONT
        val width = when {
            trackSpaceWidth > 0 -> trackSpaceWidth.toFloat()
            inputFrameWidth > 0 -> inputFrameWidth.toFloat()
            else -> return ThreatDirection.FRONT
        }
        val centerX = bbox[0] + bbox[2] / 2f
        val ratio = (centerX / width).coerceIn(0f, 1f)
        return when {
            ratio < 0.4f -> ThreatDirection.LEFT
            ratio > 0.6f -> ThreatDirection.RIGHT
            else -> ThreatDirection.FRONT
        }
    }
}

/**
 * Unified configuration for SafetyCore.
 * Consolidates all configuration parameters into a single class.
 */
data class SafetyCoreConfig(
    val sdkRuntime: SdkRuntimeConfig = SdkRuntimeConfig(),
    val modelAssets: ModelAssets = ModelAssets(),
    val modelSizes: ModelSizes = ModelSizes(),
    val yoloNorm: YoloNormConfig = YoloNormConfig(),
    val fusion: FusionConfig = FusionConfig(),
    val stage2B: Stage2BDepthConfig = Stage2BDepthConfig(),
    val tracking: TrackingConfig = TrackingConfig(),
    val heuristic: HeuristicThreatConfig = HeuristicThreatConfig(),
    val depthNorm: DepthNormConfig = DepthNormConfig(),
    val cameraIntrinsics: CameraIntrinsics = CameraIntrinsics(),
    val imu: ImuConfig = ImuConfig(),
    val debug: DebugFlags = DebugFlags()
)
