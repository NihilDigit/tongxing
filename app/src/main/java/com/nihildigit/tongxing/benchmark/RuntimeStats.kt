package com.nihildigit.tongxing.benchmark

/**
 * SafetyCore 运行时统计数据
 * 用于基准测试面板收集
 */
data class SafetyCoreStats(
    val backend: String,
    val frameCount: Int,
    val detectionMeanMs: Double,
    val detectionP50Ms: Double,
    val detectionP95Ms: Double,
    val detectionMinMs: Double,
    val detectionMaxMs: Double,
    val depthMeanMs: Double,
    val depthP50Ms: Double,
    val depthP95Ms: Double,
    val depthMinMs: Double,
    val depthMaxMs: Double,
    val fusionMeanMs: Double,
    val fusionP95Ms: Double,
    val endToEndFps: Double,
    val qnnAvailable: Boolean,
    val qnnLatencyMs: Double? = null,
    val openclLatencyMs: Double? = null,
    val cpuLatencyMs: Double? = null
)

/**
 * AgentCore 运行时统计数据
 */
data class AgentCoreStats(
    val backend: String,
    val stemTokenCount: Int,
    val stemWarmupTimeMs: Long,
    val lastPromptTokens: Int,
    val lastGeneratedTokens: Int,
    val firstTokenLatencyMs: Long,
    val firstTokenLatencyWithStemMs: Long,
    val totalGenerationMs: Long,
    val generateSpeed: Double,
    val prefillSpeed: Double,
    val recentToolCalls: List<ToolCallStats>
)

data class ToolCallStats(
    val name: String,
    val latencyMs: Long,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Audio 运行时统计数据
 */
data class AudioStats(
    val asrSessionCount: Int,
    val asrRtfMean: Double,
    val asrRtfP95: Double,
    val lastAsrAudioDurationMs: Long,
    val lastAsrProcessingTimeMs: Long,
    val ttsUtteranceCount: Int,
    val ttsLatencyMeanMs: Long,
    val lastTtsLatencyMs: Long
)
