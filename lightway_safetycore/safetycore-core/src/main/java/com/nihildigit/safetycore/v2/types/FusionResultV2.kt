package com.nihildigit.safetycore.v2.types

/**
 * Scale recovery state.
 */
data class ScaleStateV2(
    val scaleFactor: Float,
    val stable: Boolean,
    val validFrames: Int
)

/**
 * Complete fusion result for a single frame.
 */
data class FusionResultV2(
    val tracks: List<TrackedObstacleV2>,
    val threats: List<ThreatResultV2>,
    val scaleState: ScaleStateV2,
    val frameTimeMs: Float,
    val timings: TimingsV2,
    val inputFrameWidth: Int = 0,
    val inputFrameHeight: Int = 0,
    val trackSpaceWidth: Int = 0,
    val trackSpaceHeight: Int = 0,
    val isDegraded: Boolean = false,
    val degradationReason: String = ""
)

/**
 * Timing breakdown for V2 pipeline.
 */
data class TimingsV2(
    val depthInferMs: Float,
    val detectInferMs: Float,
    val segInferMs: Float,
    val featureInferMs: Float,
    val fusionMs: Float,
    val totalMs: Float
)
