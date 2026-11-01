package com.nihildigit.tongxing.benchmark

import com.nihildigit.safetycore.orchestrator.TelemetryData
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 收集 SafetyCore 运行时性能统计数据
 * 用于基准测试面板展示
 */
class SafetyCoreStatsCollector(
    private val maxSamples: Int = 300 // 约 10 秒 @ 30fps
) {
    private val detectionSamples = ConcurrentLinkedDeque<Double>()
    private val depthSamples = ConcurrentLinkedDeque<Double>()
    private val fusionSamples = ConcurrentLinkedDeque<Double>()
    private val e2eSamples = ConcurrentLinkedDeque<Double>()

    @Volatile private var frameCount = 0
    @Volatile private var lastBackend = "unknown"
    @Volatile private var qnnAvailable = false
    @Volatile private var qnnLatencyMs: Double? = null
    @Volatile private var openclLatencyMs: Double? = null
    @Volatile private var cpuLatencyMs: Double? = null

    /**
     * 记录一帧的遥测数据
     */
    fun record(telemetry: TelemetryData) {
        frameCount++
        lastBackend = telemetry.backend

        // 检测延迟
        val detectMs = telemetry.detectInferMs?.toDouble()
        if (detectMs != null && detectMs > 0) {
            addSample(detectionSamples, detectMs)
        }

        // 深度延迟
        val depthMs = telemetry.depthInferMs?.toDouble()
        if (depthMs != null && depthMs > 0) {
            addSample(depthSamples, depthMs)
        }

        // 融合延迟 (总推理时间)
        val fusionMs = telemetry.inferenceMs.toDouble()
        if (fusionMs > 0) {
            addSample(fusionSamples, fusionMs)
        }

        // 端到端延迟
        val e2eMs = telemetry.e2eMs.toDouble()
        if (e2eMs > 0) {
            addSample(e2eSamples, e2eMs)
        }

        // 记录后端特定延迟
        when {
            telemetry.backend.contains("QNN", ignoreCase = true) -> {
                qnnAvailable = true
                qnnLatencyMs = fusionMs
            }
            telemetry.backend.contains("OpenCL", ignoreCase = true) ||
            telemetry.backend.contains("GPU", ignoreCase = true) -> {
                openclLatencyMs = fusionMs
            }
            telemetry.backend.contains("CPU", ignoreCase = true) -> {
                cpuLatencyMs = fusionMs
            }
        }
    }

    private fun addSample(deque: ConcurrentLinkedDeque<Double>, value: Double) {
        deque.addLast(value)
        while (deque.size > maxSamples) {
            deque.pollFirst()
        }
    }

    /**
     * 获取当前统计数据快照
     */
    fun getStats(): SafetyCoreStats? {
        if (frameCount == 0) return null

        val detectionList = detectionSamples.toList()
        val depthList = depthSamples.toList()
        val fusionList = fusionSamples.toList()
        val e2eList = e2eSamples.toList()

        if (detectionList.isEmpty() || depthList.isEmpty()) return null

        val detectionStats = computeStats(detectionList)
        val depthStats = computeStats(depthList)
        val fusionStats = computeStats(fusionList)

        val avgE2eMs = if (e2eList.isNotEmpty()) e2eList.average() else 0.0
        val fps = if (avgE2eMs > 0) 1000.0 / avgE2eMs else 0.0

        return SafetyCoreStats(
            backend = lastBackend,
            frameCount = frameCount,
            detectionMeanMs = detectionStats.mean,
            detectionP50Ms = detectionStats.p50,
            detectionP95Ms = detectionStats.p95,
            detectionMinMs = detectionStats.min,
            detectionMaxMs = detectionStats.max,
            depthMeanMs = depthStats.mean,
            depthP50Ms = depthStats.p50,
            depthP95Ms = depthStats.p95,
            depthMinMs = depthStats.min,
            depthMaxMs = depthStats.max,
            fusionMeanMs = fusionStats.mean,
            fusionP95Ms = fusionStats.p95,
            endToEndFps = fps,
            qnnAvailable = qnnAvailable,
            qnnLatencyMs = qnnLatencyMs,
            openclLatencyMs = openclLatencyMs,
            cpuLatencyMs = cpuLatencyMs
        )
    }

    /**
     * 重置所有统计数据
     */
    fun reset() {
        detectionSamples.clear()
        depthSamples.clear()
        fusionSamples.clear()
        e2eSamples.clear()
        frameCount = 0
    }

    private data class Stats(
        val mean: Double,
        val p50: Double,
        val p95: Double,
        val min: Double,
        val max: Double
    )

    private fun computeStats(samples: List<Double>): Stats {
        if (samples.isEmpty()) {
            return Stats(0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val sorted = samples.sorted()
        val mean = samples.average()
        val p50 = percentile(sorted, 50)
        val p95 = percentile(sorted, 95)
        val min = sorted.first()
        val max = sorted.last()
        return Stats(mean, p50, p95, min, max)
    }

    private fun percentile(sortedList: List<Double>, percentile: Int): Double {
        if (sortedList.isEmpty()) return 0.0
        val index = (sortedList.size * percentile / 100.0).toInt()
            .coerceIn(0, sortedList.size - 1)
        return sortedList[index]
    }
}
