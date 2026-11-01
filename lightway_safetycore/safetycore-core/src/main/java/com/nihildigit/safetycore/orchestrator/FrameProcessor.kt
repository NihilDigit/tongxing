package com.nihildigit.safetycore.orchestrator

import android.graphics.Bitmap
import com.nihildigit.safetycore.core.HeuristicThreatConfig
import com.nihildigit.safetycore.core.FusionConfig
import com.nihildigit.safetycore.core.YoloNormConfig
import com.nihildigit.safetycore.core.Stage2BDepthConfig
import com.nihildigit.safetycore.core.TrackingConfig
import com.nihildigit.safetycore.core.ImuConfig
import com.nihildigit.safetycore.depth.DepthNormConfig
import com.nihildigit.safetycore.core.Threat
import com.nihildigit.safetycore.core.ThreatExplain
import com.nihildigit.safetycore.core.Track
import com.nihildigit.safetycore.threat.ThreatMetrics
import com.nihildigit.safetycore.sensors.ImuSample
import com.nihildigit.safetycore.pipeline.SchedulerStats

interface FrameProcessor : AutoCloseable {
    fun start()
    fun stop()
    fun onFrame(frame: Bitmap, tsNanos: Long)
    /**
     * Optional IMU sample intake for pose compensation.
     * Default no-op so callers don't need to guard by availability.
     */
    fun onImuSample(sample: ImuSample) {}
    var uiListener: UiListener?
    var statsListener: StatsListener?
    /**
     * Optional runtime tuning for heuristic threat assessor.
     * Default no-op so callers don't need to guard by mode.
     */
    fun updateHeuristicConfig(config: HeuristicThreatConfig) {}
    fun updateYoloNormConfig(config: YoloNormConfig) {}
    fun updateFusionConfig(config: FusionConfig) {}
    fun updateStage2BDepthConfig(config: Stage2BDepthConfig) {}
    fun updateTrackingConfig(config: TrackingConfig) {}
    fun updateImuConfig(config: ImuConfig) {}
    fun updateDepthNormConfig(config: DepthNormConfig) {}
    fun resetTracking() {}

    interface UiListener {
        fun onFrameResult(result: FrameResult)
    }

    interface StatsListener {
        fun onSchedulerStats(stats: SchedulerStats)
    }
}

data class FrameResult(
    val tracks: List<Track>,
    val threats: List<Threat>,
    val detectBoxes: List<FloatArray> = emptyList(),
    val detectClasses: List<Int>? = null,
    val detectScores: List<Float>? = null,
    val tsNanos: Long,
    val telemetry: TelemetryData? = null,
    // New fields for real-time preview visualization (matching Inspector)
    val visualData: VisualData? = null,
    // Threat assessment explanations (for detailed UI)
    val threatsExplain: Map<Int, ThreatExplain>? = null,
    val isDetectionOnly: Boolean = false,
    // Stage 4 tracking debug data (exposed for tuning WebUI)
    val trackingDebug: com.nihildigit.safetycore.core.TrackingDebugData? = null
)

/**
 * Visual data for real-time preview modes (depth, detection).
 * Contains aligned frame-space data and metadata for visualization.
 */
data class VisualData(
    val frameSize: com.nihildigit.safetycore.core.Size,
    val frameId: Long,
    // Depth visualization (raw MiDaS + frame-relative rank)
    val depthRaw: FloatArray? = null,
    val depthRank: FloatArray? = null,
    val depthMin: Float? = null,
    val depthMax: Float? = null,
    // Detection visualization (already in FrameResult, but kept for consistency)
    val detections: List<com.nihildigit.safetycore.core.DetectionViz>? = null,
    // Raw detection visualization (pre-Stage1A)
    val rawDetections: List<com.nihildigit.safetycore.core.DetectionViz>? = null,
    // Stage 2A (YOLO + depth-filtered obstacles)
    val stage2ADetections: List<com.nihildigit.safetycore.core.Obstacle>? = null,
    // Stage 2B (Depth near-field obstacles)
    val stage2BDetections: List<com.nihildigit.safetycore.core.Obstacle>? = null,
    // Stage 2B filtered blobs with reasons
    val stage2BFiltered: List<com.nihildigit.safetycore.core.FilteredBlob>? = null,
    // Stage 3 fused obstacles (after IMU compensation)
    val stage3Detections: List<com.nihildigit.safetycore.core.Obstacle>? = null,
    // Stage 2B masks (work-resolution binary)
    val stage2BMaskRaw: ByteArray? = null,
    val stage2BMaskCcl: ByteArray? = null,
    val stage2BMaskWidth: Int? = null,
    val stage2BMaskHeight: Int? = null,
    // Stage 4 tracking debug data (match pairs, unmatched sets, costs)
    val trackingDebug: com.nihildigit.safetycore.core.TrackingDebugData? = null
)

data class TelemetryData(
    val inferenceMs: Int,           // total infer time (depth+detect)
    val backend: String,
    val e2eMs: Int,                 // end-to-end latency from frame ts to just before UI dispatch
    val visualDataHealthy: Boolean? = null,
    // Per-model timings
    val depthPreMs: Int? = null,
    val depthInferMs: Int? = null,
    val depthPostMs: Int? = null,
    val detectPreMs: Int? = null,
    val detectInferMs: Int? = null,
    val detectPostMs: Int? = null,
    // Unified threat assessor metrics (approach or heuristic)
    val threatMetrics: ThreatMetrics? = null,
    // On-inference breakdown (excludes scheduler/letterbox and UI dispatch)
    val onInferenceMs: Int? = null,
    val imuMs: Int? = null,
    val fusionMs: Int? = null,
    val threatMs: Int? = null,
    val visualMs: Int? = null,
    val fusionTiming: Any? = null
)
