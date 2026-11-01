package com.nihildigit.tongxing.overlay

import com.nihildigit.tongxing.state.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 模式管理器。
 *
 * 模式切换功能已废弃，当前仅保留 NAVIGATION 常量态，
 * 该类仅用于兼容历史调用链与状态展示。
 */
class ModeManager(initial: AssistantMode = AssistantMode.NAVIGATION) {

    private val _currentMode = MutableStateFlow(initial)
    val currentMode: StateFlow<AssistantMode> = _currentMode.asStateFlow()

    @Deprecated("Mode switching is deprecated. Keep NAVIGATION only.")
    fun switchMode(newMode: AssistantMode) {
        _currentMode.value = newMode
        AppState.setAssistantMode(newMode)
    }
}
