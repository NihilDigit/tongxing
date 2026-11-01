package com.nihildigit.tongxing

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.nihildigit.tongxing.model.UiComboConfigResult
import com.nihildigit.tongxing.model.UiComboInfo
import com.nihildigit.tongxing.model.UiFrameSnapshot
import com.nihildigit.tongxing.model.UiMcpConnectionStatus
import com.nihildigit.tongxing.audio.AudioPriorityManager
import com.nihildigit.tongxing.bridge.BridgeManager
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.settings.AppPreferences
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TongXing 主 ViewModel
 * 管理 UI 状态与配置转发。AI 核心由 OverlayService 持有。
 */
class TongXingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TongXingViewModel"
    }

    private val context: Context = application.applicationContext
    val preferences = AppPreferences(context)

    val audioManager = AudioPriorityManager(context)
    val bridgeManager = BridgeManager(context, audioManager)
    private val isInitialized = AtomicBoolean(false)
    private var frameResultCallback: ((UiFrameSnapshot) -> Unit)? = null

    // 已启用的 Combo 名称集合 (通过 AppState 观察由 Service 同步的状态)
    val enabledCombosFlow: StateFlow<Set<String>> = AppState.enabledCombos

    // MCP 服务 AppState 同步状态列表 (通过)
    val mcpStatusFlow: StateFlow<List<UiMcpConnectionStatus>> = AppState.mcpConnectionStatuses

    // 可用的 Combo 列表
    val availableCombosFlow: StateFlow<List<UiComboInfo>> = AppState.availableCombos

    fun initialize() {
        if (isInitialized.getAndSet(true)) return
        Log.i(TAG, "ViewModel initialized in Observer Mode.")
    }

    fun setFrameResultCallback(callback: (UiFrameSnapshot) -> Unit) {
        frameResultCallback = callback
    }

    fun onFrameResult(result: UiFrameSnapshot) {
        frameResultCallback?.invoke(result)
    }

    // --- 配置方法转发 (由 SettingsScreen 调用) ---

    fun updateTtsSettings(speechRate: Float, pitch: Float) {
        preferences.ttsSpeechRate = speechRate
        preferences.ttsPitch = pitch
        audioManager.setSpeechRate(speechRate)
        audioManager.setPitch(pitch)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        preferences.vibrationEnabled = enabled
        audioManager.setVibrationEnabled(enabled)
    }

    fun setSafetyCoreEnabled(enabled: Boolean) {
        AppState.setSafetyCoreRunning(enabled)
    }

    fun setSafetySensitivity(level: String) {
        preferences.safetySensitivity = level
    }

    fun setAgentAutoPruneDelaySeconds(seconds: Int) {
        preferences.agentAutoPruneDelaySeconds = seconds
    }

    fun setAgentMaxLoops(maxLoops: Int) {
        preferences.agentMaxLoops = maxLoops
    }

    fun setAgentLogLevel(level: String) {
        preferences.agentLogLevel = level
    }

    fun rebuildStem() {
        // 通过 AppState 触发 Service 重建 Stem
        Log.i(TAG, "Stem rebuild requested")
    }

    // --- Combo 管理转发 ---

    fun getRegisteredCombos(): List<UiComboInfo> = AppState.availableCombos.value

    suspend fun applyComboConfig(enabledComboNames: Set<String>): UiComboConfigResult {
        preferences.enabledCombos = enabledComboNames
        AppState.setEnabledCombos(enabledComboNames)
        return UiComboConfigResult.Success(enabledComboNames.size)
    }

    fun getHub(): Any? = null // UI 不再直接访问 Hub instance

    // --- 其他 ---

    fun toggleAsr() {
        // 触发 Service ASR
    }

    fun cancel() {
        bridgeManager.cancelByUser()
    }

    fun clearConversation() {
        audioManager.speak("对话已清除", AudioPriorityManager.Priority.SYSTEM)
    }

    // --- 基准测试统计数据获取 ---

    /**
     * 获取 SafetyCore 运行时统计数据
     * 通过 AppState 获取 Service 端收集的数据
     */
    fun getSafetyCoreStats(): com.nihildigit.tongxing.benchmark.SafetyCoreStats? {
        return AppState.safetyCoreStats.value
    }

    /**
     * 获取 AgentCore 运行时统计数据
     */
    fun getAgentCoreStats(): com.nihildigit.tongxing.benchmark.AgentCoreStats? {
        return AppState.agentCoreStats.value
    }

    /**
     * 获取 Audio 模块运行时统计数据
     */
    fun getAudioStats(): com.nihildigit.tongxing.benchmark.AudioStats? {
        return AppState.audioStats.value
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.close()
    }
}
