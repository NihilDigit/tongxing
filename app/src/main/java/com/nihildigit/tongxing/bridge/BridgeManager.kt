package com.nihildigit.tongxing.bridge

import android.content.Context
import android.util.Log
import com.nihildigit.tongxing.audio.AudioPriorityManager
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.safetycore.core.Track
import com.nihildigit.safetycore.core.ThreatLevel
import com.nihildigit.safetycore.orchestrator.FrameProcessor
import com.nihildigit.safetycore.orchestrator.FrameResult
import com.nihildigit.safetycore.core.Threat
import com.nihildigit.safetycore.core.ThreatDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 桥接管理器
 * 负责 SafetyCore 与 AgentCore 之间的协调和中断管理
 *
 * 符合 F-001 规范：
 * - 安全事件不注入对话上下文
 * - 触发时发送系统消息 [因<原因>被中断]
 * - 立即停止 ASR/TTS/LLM
 */
class BridgeManager(
    private val context: Context,
    private val audioManager: AudioPriorityManager
) {
    companion object {
        private const val TAG = "BridgeManager"

        // 中断冷却时间（毫秒）
        private const val CRITICAL_INTERRUPT_COOLDOWN_MS = 2000L
    }

    // 中断事件流
    private val _interruptEvents = MutableSharedFlow<InterruptEvent>()
    val interruptEvents: SharedFlow<InterruptEvent> = _interruptEvents.asSharedFlow()

    // 是否正在处理中断
    private val isInterrupting = AtomicBoolean(false)

    // 上次中断时间
    private var lastInterruptTime = 0L

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // AgentCore 取消回调
    private var agentCancelCallback: (() -> Unit)? = null

    // ASR 停止回调
    private var asrStopCallback: (() -> Unit)? = null

    /**
     * 中断事件数据类
     */
    data class InterruptEvent(
        val reason: AppState.InterruptReason,
        val message: String,
        val threatLevel: AppState.SafetyLevel
    )

    /**
     * 设置 AgentCore 取消回调
     */
    fun setAgentCancelCallback(callback: () -> Unit) {
        agentCancelCallback = callback
    }

    /**
     * 设置 ASR 停止回调
     */
    fun setAsrStopCallback(callback: () -> Unit) {
        asrStopCallback = callback
    }

    /**
     * 处理 SafetyCore 帧结果
     * 当检测到 HIGH 及以上威胁时触发中断
     */
    fun onSafetyCoreResult(result: FrameResult) {
        // 检查是否禁用 SafetyCore
        if (AppPreferences(context).safetyCoreDisabled) {
            return
        }

        // 更新安全级别状态
        val highestThreat = result.threats.maxByOrNull { it.level.ordinal }

        if (highestThreat != null) {
            val safetyLevel = mapThreatLevel(highestThreat.level)
            val message = buildThreatMessage(highestThreat, result.tracks)

            AppState.setSafetyLevel(safetyLevel, message)

            // 检查是否需要中断
            if (shouldInterrupt(highestThreat.level)) {
                triggerInterrupt(highestThreat, result.tracks)
            }
        } else {
            AppState.setSafetyLevel(AppState.SafetyLevel.SAFE)
        }
    }

    /**
     * 判断是否应该中断助手
     * HIGH 及以上级别会触发中断
     */
    private fun shouldInterrupt(level: ThreatLevel): Boolean {
        // HIGH 及以上触发中断
        if (level.ordinal < ThreatLevel.HIGH.ordinal) {
            return false
        }

        // 检查冷却时间
        val now = System.currentTimeMillis()
        if (now - lastInterruptTime < CRITICAL_INTERRUPT_COOLDOWN_MS) {
            return false
        }

        // 检查当前助手状态是否可中断
        val currentState = AppState.assistantState.value
        return currentState == AppState.AssistantState.LISTENING ||
               currentState == AppState.AssistantState.THINKING ||
               currentState == AppState.AssistantState.SPEAKING
    }

    /**
     * 触发安全中断
     */
    private fun triggerInterrupt(threat: Threat, tracks: List<Track>) {
        if (!isInterrupting.compareAndSet(false, true)) {
            return // 已经在处理中断
        }

        lastInterruptTime = System.currentTimeMillis()

        scope.launch {
            try {
                Log.w(TAG, "触发安全中断: ${threat.level}, ${threat.reason}")

                // 1. 停止 ASR
                asrStopCallback?.invoke()

                // 2. 取消 AgentCore 生成
                agentCancelCallback?.invoke()

                // 3. 中断当前 TTS，并播报安全告警
                audioManager.interruptForSafety()
                val threatMessage = buildThreatMessage(threat, tracks)
                audioManager.speak(threatMessage, AudioPriorityManager.Priority.SAFETY)

                // 4. 更新应用状态
                AppState.handleSafetyInterrupt(
                    AppState.InterruptReason.CRITICAL_THREAT,
                    threat.reason
                )

                // 5. 发送中断事件
                val event = InterruptEvent(
                    reason = AppState.InterruptReason.CRITICAL_THREAT,
                    message = "[因危急障碍被中断]",
                    threatLevel = mapThreatLevel(threat.level)
                )
                _interruptEvents.emit(event)

                // 6. 延迟后恢复空闲状态
                delay(2000)
                AppState.resetToIdle()

            } finally {
                isInterrupting.set(false)
            }
        }
    }

    /**
     * 映射 SafetyCore 威胁级别到应用级别
     */
    private fun mapThreatLevel(level: ThreatLevel): AppState.SafetyLevel {
        return when (level) {
            ThreatLevel.LOW -> AppState.SafetyLevel.LOW
            ThreatLevel.MEDIUM -> AppState.SafetyLevel.MEDIUM
            ThreatLevel.HIGH -> AppState.SafetyLevel.HIGH
            ThreatLevel.CRITICAL -> AppState.SafetyLevel.CRITICAL
        }
    }

    /**
     * 构建威胁提示消息（用于 TTS 播报和 UI 显示）
     * 当置信度 > 0.6 时使用 COCO 类别名称，否则使用通用描述
     */
    private fun buildThreatMessage(threat: Threat, tracks: List<Track>): String {
        val direction = when (threat.direction) {
            ThreatDirection.LEFT -> "左侧"
            ThreatDirection.FRONT -> "前方"
            ThreatDirection.RIGHT -> "右侧"
        }

        // 查找对应的 track 获取类别和置信度
        val track = tracks.find { it.id == threat.trackId }
        val classId = track?.classId
        val confidence = track?.confidence ?: 0f

        // COCO 类别名称映射
        val className: String? = when (classId) {
            0 -> "有人"
            1 -> "有自行车"
            2 -> "有汽车"
            3 -> "有摩托车"
            5 -> "有公交车"
            7 -> "有火车"
            15 -> "有猫"
            16 -> "有狗"
            17 -> "有马"
            21 -> "有大象"
            22 -> "有熊"
            23 -> "有斑马"
            24 -> "有长颈鹿"
            else -> null
        }

        // 当置信度 > 0.6 时使用具体类别名称，否则使用通用描述
        return if (confidence > 0.6f && className != null) {
            direction + className + "接近"
        } else {
            direction + "有障碍物"
        }
    }

    /**
     * 手动触发用户取消
     */
    fun cancelByUser() {
        scope.launch {
            asrStopCallback?.invoke()
            agentCancelCallback?.invoke()
            audioManager.stopAll()

            AppState.handleSafetyInterrupt(
                AppState.InterruptReason.USER_CANCEL,
                "用户取消操作"
            )

            delay(500)
            AppState.resetToIdle()
        }
    }

    /**
     * 清理资源
     */
    fun release() {
        scope.cancel()
    }
}
