package com.nihildigit.tongxing.state

import android.graphics.Bitmap
import com.nihildigit.tongxing.model.UiComboInfo
import com.nihildigit.tongxing.model.UiFrameSnapshot
import com.nihildigit.tongxing.model.UiMcpConnectionStatus
import com.nihildigit.tongxing.overlay.AssistantMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用全局状态管理
 * 符合 F-002 规范的状态机定义
 */
object AppState {

    data class PreviewFrame(
        val bitmap: Bitmap?,
        val width: Int,
        val height: Int,
        val timestampMs: Long
    )

    // 组件健康状态
    enum class ComponentHealth {
        UNKNOWN,      // 未知/未初始化
        INITIALIZING, // 正在初始化
        READY,        // 就绪
        DEGRADED,     // 降级（部分功能受限）
        FAILED        // 失败
    }

    // 对话状态机
    enum class AssistantState {
        IDLE,           // 空闲
        LISTENING,      // ASR 录音中
        THINKING,       // LLM 生成中
        SPEAKING,       // TTS 播报中
        INTERRUPTED     // 被安全事件中断
    }

    // 安全威胁级别（来自 SafetyCore）
    enum class SafetyLevel {
        SAFE,           // 安全
        LOW,            // 低威胁
        MEDIUM,         // 中威胁
        HIGH,           // 高威胁
        CRITICAL        // 危急威胁
    }

    // 中断原因枚举
    enum class InterruptReason {
        CRITICAL_THREAT,        // 危急障碍
        HIGH_THREAT,            // 高威胁障碍
        USER_CANCEL,            // 用户取消
        SYSTEM_ERROR            // 系统错误
    }

    // 应用屏幕
    enum class Screen {
        MAIN,           // 主屏（相机预览 + 安全提示）
        SETTINGS,       // 设置页
        ERROR           // 异常页
    }

    // 当前屏幕
    private val _currentScreen = MutableStateFlow(Screen.MAIN)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // 对话状态
    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    // 安全级别
    private val _safetyLevel = MutableStateFlow(SafetyLevel.SAFE)
    val safetyLevel: StateFlow<SafetyLevel> = _safetyLevel.asStateFlow()

    // 最后一次安全提示文本
    private val _safetyMessage = MutableStateFlow("")
    val safetyMessage: StateFlow<String> = _safetyMessage.asStateFlow()

    // 当前助手回复（流式）
    private val _assistantResponse = MutableStateFlow("")
    val assistantResponse: StateFlow<String> = _assistantResponse.asStateFlow()

    // 当前正在调用的工具名称（空表示无调用）
    private val _activeToolCall = MutableStateFlow<String?>(null)
    val activeToolCall: StateFlow<String?> = _activeToolCall.asStateFlow()

    // ASR实时转录文本
    private val _asrTranscription = MutableStateFlow("")
    val asrTranscription: StateFlow<String> = _asrTranscription.asStateFlow()

    // 当前助手模式（悬浮球蓝/绿）
    private val _assistantMode = MutableStateFlow(AssistantMode.NAVIGATION)
    val assistantMode: StateFlow<AssistantMode> = _assistantMode.asStateFlow()

    // Stem 预热是否完成（用于 UI 提示）
    private val _stemReady = MutableStateFlow(true)
    val stemReady: StateFlow<Boolean> = _stemReady.asStateFlow()

    enum class StemRuntimeState {
        WARMING_UP,
        READY,
        TASK_RUNNING
    }

    private val _stemRuntimeState = MutableStateFlow(StemRuntimeState.READY)
    val stemRuntimeState: StateFlow<StemRuntimeState> = _stemRuntimeState.asStateFlow()

    // SafetyCore 运行状态（用于设置页仪表盘）
    private val _safetyCoreRunning = MutableStateFlow(true)
    val safetyCoreRunning: StateFlow<Boolean> = _safetyCoreRunning.asStateFlow()

    // 组件健康状态
    private val _cameraHealth = MutableStateFlow(ComponentHealth.UNKNOWN)
    val cameraHealth: StateFlow<ComponentHealth> = _cameraHealth.asStateFlow()

    private val _safetyCoreHealth = MutableStateFlow(ComponentHealth.UNKNOWN)
    val safetyCoreHealth: StateFlow<ComponentHealth> = _safetyCoreHealth.asStateFlow()

    private val _asrHealth = MutableStateFlow(ComponentHealth.UNKNOWN)
    val asrHealth: StateFlow<ComponentHealth> = _asrHealth.asStateFlow()

    private val _agentCoreHealth = MutableStateFlow(ComponentHealth.UNKNOWN)
    val agentCoreHealth: StateFlow<ComponentHealth> = _agentCoreHealth.asStateFlow()

    private val _overallHealth = MutableStateFlow(ComponentHealth.UNKNOWN)
    val overallHealth: StateFlow<ComponentHealth> = _overallHealth.asStateFlow()

    private val _previewFrame = MutableStateFlow<PreviewFrame?>(null)
    val previewFrame: StateFlow<PreviewFrame?> = _previewFrame.asStateFlow()

    private val _latestFrameResult = MutableStateFlow<UiFrameSnapshot?>(null)
    val latestFrameResult: StateFlow<UiFrameSnapshot?> = _latestFrameResult.asStateFlow()

    private fun updateOverallHealth() {
        val all = listOf(_cameraHealth.value, _safetyCoreHealth.value, _asrHealth.value, _agentCoreHealth.value)
        _overallHealth.value = when {
            all.any { it == ComponentHealth.FAILED } -> ComponentHealth.FAILED
            all.any { it == ComponentHealth.INITIALIZING } -> ComponentHealth.INITIALIZING
            all.any { it == ComponentHealth.DEGRADED } -> ComponentHealth.DEGRADED
            all.all { it == ComponentHealth.READY } -> ComponentHealth.READY
            else -> ComponentHealth.UNKNOWN
        }
    }

    // OverlayService 运行状态（用于配置中心展示）
    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    // 错误消息
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // 是否启用云端
    private val _cloudEnabled = MutableStateFlow(true)
    val cloudEnabled: StateFlow<Boolean> = _cloudEnabled.asStateFlow()

    // 已启用的 Combo 集合
    private val _enabledCombos = MutableStateFlow<Set<String>>(emptySet())
    val enabledCombos: StateFlow<Set<String>> = _enabledCombos.asStateFlow()

    // MCP 服务连接状态列表
    private val _mcpConnectionStatuses = MutableStateFlow<List<UiMcpConnectionStatus>>(emptyList())
    val mcpConnectionStatuses: StateFlow<List<UiMcpConnectionStatus>> = _mcpConnectionStatuses.asStateFlow()

    // 可用的 Combo 列表
    private val _availableCombos = MutableStateFlow<List<UiComboInfo>>(emptyList())
    val availableCombos: StateFlow<List<UiComboInfo>> = _availableCombos.asStateFlow()

    // --- 基准测试统计数据 ---
    
    private val _safetyCoreStats = MutableStateFlow<com.nihildigit.tongxing.benchmark.SafetyCoreStats?>(null)
    val safetyCoreStats: StateFlow<com.nihildigit.tongxing.benchmark.SafetyCoreStats?> = _safetyCoreStats.asStateFlow()

    private val _agentCoreStats = MutableStateFlow<com.nihildigit.tongxing.benchmark.AgentCoreStats?>(null)
    val agentCoreStats: StateFlow<com.nihildigit.tongxing.benchmark.AgentCoreStats?> = _agentCoreStats.asStateFlow()

    private val _audioStats = MutableStateFlow<com.nihildigit.tongxing.benchmark.AudioStats?>(null)
    val audioStats: StateFlow<com.nihildigit.tongxing.benchmark.AudioStats?> = _audioStats.asStateFlow()

    fun setSafetyCoreStats(stats: com.nihildigit.tongxing.benchmark.SafetyCoreStats?) {
        _safetyCoreStats.value = stats
    }

    fun setAgentCoreStats(stats: com.nihildigit.tongxing.benchmark.AgentCoreStats?) {
        _agentCoreStats.value = stats
    }

    fun setAudioStats(stats: com.nihildigit.tongxing.benchmark.AudioStats?) {
        _audioStats.value = stats
    }

    // 状态更新方法
    fun setEnabledCombos(combos: Set<String>) {
        _enabledCombos.value = combos
    }

    fun setMcpConnectionStatuses(statuses: List<UiMcpConnectionStatus>) {
        _mcpConnectionStatuses.value = statuses
    }

    fun setAvailableCombos(combos: List<UiComboInfo>) {
        _availableCombos.value = combos
    }

    fun setScreen(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setAssistantState(state: AssistantState) {
        _assistantState.value = state
    }

    fun setSafetyLevel(level: SafetyLevel, message: String = "") {
        _safetyLevel.value = level
        if (message.isNotEmpty()) {
            _safetyMessage.value = message
        }
    }

    fun updateAssistantResponse(response: String) {
        _assistantResponse.value = response
    }

    fun appendAssistantResponse(delta: String) {
        _assistantResponse.value += delta
    }

    fun clearAssistantResponse() {
        _assistantResponse.value = ""
    }

    fun updateAsrTranscription(text: String) {
        _asrTranscription.value = text
    }

    fun clearAsrTranscription() {
        _asrTranscription.value = ""
    }

    fun setAssistantMode(mode: AssistantMode) {
        _assistantMode.value = mode
    }

    fun setStemReady(ready: Boolean) {
        _stemReady.value = ready
        if (!ready) {
            _stemRuntimeState.value = StemRuntimeState.WARMING_UP
        } else if (_stemRuntimeState.value == StemRuntimeState.WARMING_UP) {
            _stemRuntimeState.value = StemRuntimeState.READY
        }
    }

    fun setStemRuntimeState(state: StemRuntimeState) {
        _stemRuntimeState.value = state
        _stemReady.value = state == StemRuntimeState.READY
    }

    fun setSafetyCoreRunning(running: Boolean) {
        _safetyCoreRunning.value = running
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun setPreviewFrame(frame: PreviewFrame?) {
        _previewFrame.value = frame
    }

    fun setLatestFrameResult(result: UiFrameSnapshot?) {
        _latestFrameResult.value = result
    }

    fun setCameraHealth(health: ComponentHealth) {
        _cameraHealth.value = health
        updateOverallHealth()
    }

    fun setSafetyCoreHealth(health: ComponentHealth) {
        _safetyCoreHealth.value = health
        updateOverallHealth()
    }

    fun setAsrHealth(health: ComponentHealth) {
        _asrHealth.value = health
        updateOverallHealth()
    }

    fun setAgentCoreHealth(health: ComponentHealth) {
        _agentCoreHealth.value = health
        updateOverallHealth()
    }

    fun setError(message: String?) {
        _errorMessage.value = message
        if (message != null) {
            _currentScreen.value = Screen.ERROR
        }
    }

    fun clearError() {
        _errorMessage.value = null
        _currentScreen.value = Screen.MAIN
    }

    fun setCloudEnabled(enabled: Boolean) {
        _cloudEnabled.value = enabled
    }

    fun setActiveToolCall(name: String?) {
        _activeToolCall.value = name
    }

    /**
     * 处理安全中断
     * 符合 F-001 规范：安全事件不注入对话上下文，仅发系统消息
     */
    fun handleSafetyInterrupt(reason: InterruptReason, details: String) {
        _assistantState.value = AssistantState.INTERRUPTED
        // 系统消息格式：[因<原因>被中断]
        val reasonText = when (reason) {
            InterruptReason.CRITICAL_THREAT -> "危急障碍"
            InterruptReason.HIGH_THREAT -> "高威胁障碍"
            InterruptReason.USER_CANCEL -> "用户取消"
            InterruptReason.SYSTEM_ERROR -> "系统错误"
        }
        _safetyMessage.value = "[因${reasonText}被中断] $details"
    }

    /**
     * 重置为空闲状态
     */
    fun resetToIdle() {
        _assistantState.value = AssistantState.IDLE
        _assistantResponse.value = ""
    }
}
