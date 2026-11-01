package com.nihildigit.tongxing.benchmark

import com.nihildigit.agentcore.model.InferenceMetrics
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 收集 AgentCore 运行时性能统计数据
 * 用于基准测试面板展示
 */
class AgentCoreStatsCollector(
    private val maxMetricsSamples: Int = 50,
    private val maxToolCallRecords: Int = 20
) {
    private val metricsSamples = ConcurrentLinkedDeque<InferenceMetrics>()
    private val toolCallRecords = CopyOnWriteArrayList<ToolCallStats>()

    @Volatile private var lastMetrics: InferenceMetrics? = null

    @Volatile private var backend = "opencl"
    @Volatile private var stemTokenCount = 0
    @Volatile private var stemWarmupTimeMs = 0L
    @Volatile private var lastPromptTokens = 0
    @Volatile private var lastGeneratedTokens = 0
    @Volatile private var firstTokenLatencyMs = 0L
    @Volatile private var firstTokenLatencyWithStemMs = 0L
    @Volatile private var totalGenerationMs = 0L
    @Volatile private var generateSpeed = 0.0
    @Volatile private var prefillSpeed = 0.0

    /**
     * 记录推理性能指标
     */
    fun recordInferenceMetrics(metrics: InferenceMetrics) {
        metricsSamples.addLast(metrics)
        while (metricsSamples.size > maxMetricsSamples) {
            metricsSamples.pollFirst()
        }
        lastMetrics = metrics

        // 更新最新指标
        lastPromptTokens = metrics.promptTokens
        lastGeneratedTokens = metrics.decodeTokens
        totalGenerationMs = metrics.prefillTimeMs + metrics.decodeTimeMs
        generateSpeed = metrics.decodeTokensPerSecond
        prefillSpeed = metrics.prefillTokensPerSecond

        // 首字延迟近似为 prefill 时间 + 第一个 token 的解码时间
        // 简化计算：使用 prefill 时间作为首字延迟
        firstTokenLatencyMs = metrics.prefillTimeMs
    }

    /**
     * 获取最新一条推理指标（可能为 null）。
     */
    fun getLatestInferenceMetrics(): InferenceMetrics? = lastMetrics

    /**
     * 记录 Stem 预热信息
     */
    fun recordStemWarmup(tokenCount: Int, warmupTimeMs: Long) {
        stemTokenCount = tokenCount
        stemWarmupTimeMs = warmupTimeMs
        // Stem 预热后的首字延迟会更低
        if (firstTokenLatencyMs > 0) {
            firstTokenLatencyWithStemMs = firstTokenLatencyMs
        }
    }

    /**
     * 记录工具调用
     */
    fun recordToolCall(name: String, latencyMs: Long, success: Boolean, errorMessage: String? = null) {
        val record = ToolCallStats(
            name = name,
            latencyMs = latencyMs,
            success = success,
            errorMessage = errorMessage
        )
        toolCallRecords.add(record)
        while (toolCallRecords.size > maxToolCallRecords) {
            toolCallRecords.removeAt(0)
        }
    }

    /**
     * 设置后端类型
     */
    fun setBackend(backendType: String) {
        backend = backendType
    }

    /**
     * 获取当前统计数据快照
     */
    fun getStats(): AgentCoreStats? {
        if (metricsSamples.isEmpty() && stemTokenCount == 0) return null

        // 计算平均指标
        val samplesList = metricsSamples.toList()
        val avgGenerateSpeed = if (samplesList.isNotEmpty()) {
            samplesList.map { it.decodeTokensPerSecond }.average()
        } else {
            generateSpeed
        }
        val avgPrefillSpeed = if (samplesList.isNotEmpty()) {
            samplesList.map { it.prefillTokensPerSecond }.average()
        } else {
            prefillSpeed
        }

        return AgentCoreStats(
            backend = backend,
            stemTokenCount = stemTokenCount,
            stemWarmupTimeMs = stemWarmupTimeMs,
            lastPromptTokens = lastPromptTokens,
            lastGeneratedTokens = lastGeneratedTokens,
            firstTokenLatencyMs = firstTokenLatencyMs,
            firstTokenLatencyWithStemMs = firstTokenLatencyWithStemMs.takeIf { it > 0 } ?: firstTokenLatencyMs,
            totalGenerationMs = totalGenerationMs,
            generateSpeed = avgGenerateSpeed,
            prefillSpeed = avgPrefillSpeed,
            recentToolCalls = toolCallRecords.toList()
        )
    }

    /**
     * 重置所有统计数据
     */
    fun reset() {
        metricsSamples.clear()
        toolCallRecords.clear()
        stemTokenCount = 0
        stemWarmupTimeMs = 0L
        lastPromptTokens = 0
        lastGeneratedTokens = 0
        firstTokenLatencyMs = 0L
        firstTokenLatencyWithStemMs = 0L
        totalGenerationMs = 0L
        generateSpeed = 0.0
        prefillSpeed = 0.0
    }
}
