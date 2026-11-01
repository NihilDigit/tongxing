package com.nihildigit.agentcore.hub

import android.os.SystemClock
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun interface StatusBroadcastCallback {
    fun onTaxiStatusChange(message: String)
}

class TaxiStatusPoller(
    private val hub: Hub,
    private val callback: StatusBroadcastCallback,
    private val intervalMs: Long = 10_000L,
    private val maxDurationMs: Long = 15 * 60 * 1000L,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    companion object {
        private const val TAG = "TaxiStatusPoller"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var job: Job? = null
    @Volatile
    private var orderId: String? = null
    @Volatile
    private var lastStatus: String? = null
    @Volatile
    private var startTimeMs: Long = 0L
    @Volatile
    private var consecutiveFailures: Int = 0

    fun start(orderId: String) {
        if (orderId.isBlank()) return
        stop()
        this.orderId = orderId
        lastStatus = null
        consecutiveFailures = 0
        startTimeMs = clock()

        job = scope.launch {
            while (isActive) {
                try {
                    pollStatus()
                    delay(intervalMs)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    AgentLogger.error(TAG, "Poll", "polling failed", e)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        orderId = null
        lastStatus = null
        consecutiveFailures = 0
    }

    private suspend fun pollStatus() {
        val order = orderId ?: return
        val elapsed = clock() - startTimeMs
        if (elapsed > maxDurationMs) {
            AgentLogger.normal(TAG, "Poll", "polling timeout, stopping")
            stop()
            callback.onTaxiStatusChange("订单轮询已超时")
            return
        }

        val client = hub.getConnectionManager().ensureConnected("didi-sandbox")
        if (client == null) {
            consecutiveFailures += 1
            AgentLogger.normal(TAG, "Poll", "poll failed", "error" to "didi not connected")
            if (consecutiveFailures >= 3) {
                callback.onTaxiStatusChange("订单状态查询失败")
                stop()
            }
            return
        }

        val result = client.callTool(
            name = "taxi_query_order",
            arguments = mapOf("order_id" to order),
            history = emptyList()
        )

        if (result.isFailure) {
            consecutiveFailures += 1
            AgentLogger.normal(TAG, "Poll", "poll failed",
                "failures" to consecutiveFailures,
                "error" to (result.exceptionOrNull()?.message ?: "unknown")
            )
            if (consecutiveFailures >= 3) {
                callback.onTaxiStatusChange("订单状态查询失败")
                stop()
            }
            return
        }

        consecutiveFailures = 0
        val content = result.getOrNull().orEmpty()
        val statusInfo = TaxiStatusParser.parse(content) ?: return

        val newStatus = statusInfo.status
        if (newStatus != lastStatus) {
            lastStatus = newStatus
            callback.onTaxiStatusChange(TaxiStatusParser.formatTts(statusInfo))
        }

        if (newStatus == "in_trip" || newStatus == "completed" || newStatus == "cancelled") {
            stop()
            if (newStatus == "completed" || newStatus == "cancelled") {
                TaxiOrderStore.clear()
            }
        }
    }

}
