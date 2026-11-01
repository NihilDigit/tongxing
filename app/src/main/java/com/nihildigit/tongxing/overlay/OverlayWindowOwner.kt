package com.nihildigit.tongxing.overlay

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 为悬浮窗窗口提供一个“永远处于 RESUMED”的 Owner。
 *
 * Overlay 不在 Activity 窗口内，若依赖 Service 的 Lifecycle（通常只到 STARTED），
 * 设备上可能出现 Compose Recomposer 不跑、动画/StateFlow 不更新的问题。
 */
class OverlayWindowOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val controller: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    init {
        controller.performAttach()
        controller.performRestore(Bundle())
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
