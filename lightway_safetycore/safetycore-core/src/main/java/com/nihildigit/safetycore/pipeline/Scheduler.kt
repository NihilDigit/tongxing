package com.nihildigit.safetycore.pipeline

/**
 * Compatibility stats payload kept for FrameProcessor API stability.
 */
data class SchedulerStats(
    val mode: String,
    val lboxDepthMs: Int? = null,
    val lboxDetMs: Int? = null,
    val sensorLatencyMs: Int? = null,
    val processMs: Int? = null,
    val depthFailureStreak: Int = 0,
    val detectFailureStreak: Int = 0
)
