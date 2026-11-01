package com.nihildigit.tongxing.overlay.coordinator

import com.nihildigit.tongxing.benchmark.AgentCoreStatsCollector
import com.nihildigit.tongxing.benchmark.AudioStatsCollector
import com.nihildigit.tongxing.benchmark.SafetyCoreStatsCollector
import com.nihildigit.tongxing.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 性能统计收集协调器。
 * 定时从各 StatsCollector 拉取数据并推送到 AppState。
 */
class StatsCoordinator(
    private val scope: CoroutineScope
) {
    val safetyCoreStats = SafetyCoreStatsCollector()
    val agentCoreStats = AgentCoreStatsCollector()
    val audioStats = AudioStatsCollector()

    private var updateJob: Job? = null

    fun start() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(UPDATE_INTERVAL_MS)
                safetyCoreStats.getStats()?.let { AppState.setSafetyCoreStats(it) }
                agentCoreStats.getStats()?.let { AppState.setAgentCoreStats(it) }
                audioStats.getStats()?.let { AppState.setAudioStats(it) }
            }
        }
    }

    fun stop() {
        updateJob?.cancel()
        updateJob = null
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
    }
}
