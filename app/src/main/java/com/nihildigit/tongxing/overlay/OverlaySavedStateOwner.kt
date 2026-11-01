package com.nihildigit.tongxing.overlay

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 为悬浮窗 ComposeView 提供 SavedStateRegistryOwner。
 *
 * 悬浮窗不在 Activity 视图树中，Compose 需要 SavedStateRegistryOwner；
 * 这里创建一个极简 owner，生命周期委托给外部 LifecycleOwner（OverlayService）。
 */
class OverlaySavedStateOwner(
    private val delegateLifecycleOwner: LifecycleOwner
) : SavedStateRegistryOwner {

    private val controller: SavedStateRegistryController =
        SavedStateRegistryController.create(this)

    init {
        controller.performAttach()
        controller.performRestore(Bundle())
    }

    override val lifecycle: Lifecycle
        get() = delegateLifecycleOwner.lifecycle

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry
}

