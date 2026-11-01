package com.nihildigit.tongxing.overlay

import android.graphics.Bitmap
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred

/**
 * OverlayService 与 AccessibilityService 之间的极简通信桥。
 *
 * 当前只支持截图请求。若 AccessibilityService 未启用则返回 null。
 */
object AccessibilityBridge {
    @Volatile
    private var serviceRef: WeakReference<TongXingAccessibilityService>? = null

    fun attach(service: TongXingAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach(service: TongXingAccessibilityService) {
        val ref = serviceRef?.get()
        if (ref == service) serviceRef = null
    }

    fun isServiceConnected(): Boolean = serviceRef?.get() != null

    fun getLastScreenshotErrorCode(): Int? = serviceRef?.get()?.getLastScreenshotErrorCode()

    fun getLastScreenshotErrorAtMs(): Long = serviceRef?.get()?.getLastScreenshotErrorAtMs() ?: 0L

    suspend fun takeScreenshot(): Bitmap? {
        val service = serviceRef?.get() ?: return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            service.takeScreenshotSuspend()
        } else {
            null
        }
    }

    /**
     * 退出高德导航
     */
    fun exitAmapNavigation() {
        serviceRef?.get()?.exitAmapNavigation()
    }
}
