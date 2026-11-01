package com.nihildigit.tongxing.benchmark

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 收集 Audio (ASR/TTS) 运行时性能统计数据
 * 用于基准测试面板展示
 */
class AudioStatsCollector(
    private val maxSamples: Int = 50
) {
    private val asrRtfSamples = ConcurrentLinkedDeque<Double>()
    private val ttsLatencySamples = ConcurrentLinkedDeque<Long>()

    @Volatile private var asrSessionCount = 0
    @Volatile private var ttsUtteranceCount = 0
    @Volatile private var lastAsrAudioDurationMs = 0L
    @Volatile private var lastAsrProcessingTimeMs = 0L
    @Volatile private var lastTtsStartLatencyMs = 0L

    /**
     * 记录 ASR 会话完成
     * @param audioDurationMs 音频时长（毫秒）
     * @param processingTimeMs 处理时间（毫秒）
     */
    fun recordAsrSession(audioDurationMs: Long, processingTimeMs: Long) {
        if (audioDurationMs <= 0) return

        asrSessionCount++
        lastAsrAudioDurationMs = audioDurationMs
        lastAsrProcessingTimeMs = processingTimeMs

        // 计算 RTF (Real-Time Factor)
        val rtf = processingTimeMs.toDouble() / audioDurationMs
        addSample(asrRtfSamples, rtf)
    }

    /**
     * 记录 TTS 播放开始延迟
     * @param latencyMs 从 speak 请求到开始播放的延迟（毫秒）
     */
    fun recordTtsLatency(latencyMs: Long) {
        if (latencyMs <= 0) return

        ttsUtteranceCount++
        lastTtsStartLatencyMs = latencyMs
        ttsLatencySamples.addLast(latencyMs)
        while (ttsLatencySamples.size > maxSamples) {
            ttsLatencySamples.pollFirst()
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
    fun getStats(): AudioStats? {
        if (asrSessionCount == 0 && ttsUtteranceCount == 0) return null

        val rtfList = asrRtfSamples.toList()
        val ttsLatencyList = ttsLatencySamples.toList()

        val avgRtf = if (rtfList.isNotEmpty()) rtfList.average() else 0.0
        val avgTtsLatency = if (ttsLatencyList.isNotEmpty()) {
            ttsLatencyList.average().toLong()
        } else {
            0L
        }

        val p95Rtf = if (rtfList.isNotEmpty()) {
            percentile(rtfList.sorted(), 95)
        } else {
            0.0
        }

        return AudioStats(
            asrSessionCount = asrSessionCount,
            asrRtfMean = avgRtf,
            asrRtfP95 = p95Rtf,
            lastAsrAudioDurationMs = lastAsrAudioDurationMs,
            lastAsrProcessingTimeMs = lastAsrProcessingTimeMs,
            ttsUtteranceCount = ttsUtteranceCount,
            ttsLatencyMeanMs = avgTtsLatency,
            lastTtsLatencyMs = lastTtsStartLatencyMs
        )
    }

    /**
     * 重置所有统计数据
     */
    fun reset() {
        asrRtfSamples.clear()
        ttsLatencySamples.clear()
        asrSessionCount = 0
        ttsUtteranceCount = 0
        lastAsrAudioDurationMs = 0L
        lastAsrProcessingTimeMs = 0L
        lastTtsStartLatencyMs = 0L
    }

    private fun percentile(sortedList: List<Double>, percentile: Int): Double {
        if (sortedList.isEmpty()) return 0.0
        val index = (sortedList.size * percentile / 100.0).toInt()
            .coerceIn(0, sortedList.size - 1)
        return sortedList[index]
    }
}
