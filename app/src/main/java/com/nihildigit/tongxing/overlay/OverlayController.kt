package com.nihildigit.tongxing.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.nihildigit.tongxing.state.AppState

/**
 * 负责创建/移除悬浮球视图的控制器。
 *
 * 注意：悬浮球不应干扰 TalkBack，因此使用 NOT_FOCUSABLE + NOT_TOUCHABLE，
 * 并在 ComposeView 上设置 importantForAccessibility=NO。
 */
class OverlayController(
    private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var overlayOwner: OverlayWindowOwner? = null

    // 悬浮球位置/尺寸参数（真机可调）
    private val marginEndPx = dpToPx(16)
    private val marginVerticalPx = dpToPx(0)

    fun show() {
        if (overlayView != null) return
        if (overlayOwner != null) return

        val owner = OverlayWindowOwner()

        val composeView = ComposeView(context).apply {
            // 悬浮窗不在 Activity 视图树中，Compose 默认找不到 ViewTreeLifecycleOwner 会崩溃。
            // 这里用 tag 方式设置 owner，避免依赖不存在的 viewtree 工具类。
            setTag(androidx.lifecycle.runtime.R.id.view_tree_lifecycle_owner, owner)
            setTag(androidx.savedstate.R.id.view_tree_saved_state_registry_owner, owner)

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setContent {
                OverlayView(
                    assistantStateFlow = AppState.assistantState,
                    safetyLevelFlow = AppState.safetyLevel,
                    assistantResponseFlow = AppState.assistantResponse,
                    asrTranscriptionFlow = AppState.asrTranscription,
                    activeToolCallFlow = AppState.activeToolCall,
                    previewFrameFlow = AppState.previewFrame,
                    stemReadyFlow = AppState.stemReady
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Siri 风格：左下角，避开导航栏
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            x = marginEndPx
            y = dpToPx(48) // 留出导航栏空间
        }

        windowManager.addView(composeView, params)
        overlayView = composeView
        overlayOwner = owner
    }

    fun hide() {
        overlayView?.let {
            windowManager.removeView(it)
        }
        overlayView = null
        overlayOwner?.destroy()
        overlayOwner = null
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
