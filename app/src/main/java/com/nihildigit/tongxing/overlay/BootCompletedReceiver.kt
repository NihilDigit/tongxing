package com.nihildigit.tongxing.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nihildigit.tongxing.settings.AppPreferences

/**
 * 开机自启动 OverlayService（需用户在设置中显式开启）。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AppPreferences(context.applicationContext)
        if (!prefs.autoStartEnabled) return

        val serviceIntent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}

