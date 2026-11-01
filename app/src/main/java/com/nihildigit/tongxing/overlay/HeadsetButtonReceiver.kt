package com.nihildigit.tongxing.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 耳机媒体键广播接收器（作为 MediaSession 的兜底）。
 *
 * Android 新版本主要依赖 MediaSession 捕获全局媒体键，
 * 但保留 Receiver 以符合 spec 与老设备兼容。
 */
class HeadsetButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeadsetButtonReceiver"

        @Volatile
        var service: OverlayService? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val event = intent?.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        Log.w(TAG, "media button event action=${event.action} code=${event.keyCode}")
        val supportedKeys = setOf(
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )
        if (event.keyCode !in supportedKeys) {
            return
        }

        // 只响应 ACTION_UP，避免重复触发
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            Log.d(TAG, "Click triggered")
            service?.toggleAsrClearingTts()
        }
    }
}
