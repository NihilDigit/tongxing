package com.nihildigit.tongxing.init

import com.nihildigit.tongxing.state.AppState
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object InitializationManager {

    const val TIMEOUT_CAMERA_MS = 10_000L
    const val TIMEOUT_SAFETY_CORE_MS = 30_000L
    const val TIMEOUT_ASR_MS = 15_000L
    const val TIMEOUT_AGENT_CORE_MS = 60_000L

    const val MAX_RETRIES = 3
    const val RETRY_DELAY_MS = 1_000L

    sealed class InitResult {
        data class Success(val component: String) : InitResult()
        data class Timeout(val component: String) : InitResult()
        data class Error(val component: String, val throwable: Throwable) : InitResult()
    }

    suspend fun <T> withTimeout(
        component: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): InitResult {
        return try {
            withTimeout(timeoutMs) {
                block()
            }
            InitResult.Success(component)
        } catch (e: TimeoutCancellationException) {
            InitResult.Timeout(component)
        } catch (e: Exception) {
            InitResult.Error(component, e)
        }
    }

    suspend fun <T> initWithRetry(
        component: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend () -> T
    ): Pair<Boolean, Exception?> {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                block()
                return true to null
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(RETRY_DELAY_MS * attempt)
                }
            }
        }
        return false to lastException
    }

    fun validateSystemHealth(): List<String> {
        val issues = mutableListOf<String>()
        when (AppState.cameraHealth.value) {
            AppState.ComponentHealth.FAILED -> issues.add("相机")
            AppState.ComponentHealth.DEGRADED -> issues.add("相机(降级)")
            else -> {}
        }
        when (AppState.safetyCoreHealth.value) {
            AppState.ComponentHealth.FAILED -> issues.add("安全引擎")
            AppState.ComponentHealth.DEGRADED -> issues.add("安全引擎(降级)")
            else -> {}
        }
        when (AppState.asrHealth.value) {
            AppState.ComponentHealth.FAILED -> issues.add("语音识别")
            AppState.ComponentHealth.DEGRADED -> issues.add("语音识别(降级)")
            else -> {}
        }
        when (AppState.agentCoreHealth.value) {
            AppState.ComponentHealth.FAILED -> issues.add("助手核心")
            AppState.ComponentHealth.DEGRADED -> issues.add("助手核心(降级)")
            else -> {}
        }
        return issues
    }
}
