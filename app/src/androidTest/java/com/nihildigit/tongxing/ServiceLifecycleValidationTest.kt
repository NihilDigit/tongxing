package com.nihildigit.tongxing

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nihildigit.tongxing.overlay.OverlayService
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.mcp.MCPServiceConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ServiceLifecycleValidationTest {
    @Test
    fun startsAndReleasesAllLocalComponentsTwice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validateService") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppPreferences(context)
        val previousPath = preferences.llmConfigPath
        val previousDisabled = preferences.safetyCoreDisabled
        val previousUrls = preferences.mcpServiceUrls
        val previousCloudKey = preferences.cloudApiKey
        val model = File(context.getExternalFilesDir("models"), "Qwen3-1.7B-Q4_0-pure.gguf")
        assertTrue("Prepare GGUF model before service validation", model.isFile)
        val service = Intent(context, OverlayService::class.java)
        try {
            preferences.llmConfigPath = model.absolutePath
            preferences.safetyCoreDisabled = false
            preferences.mcpServiceUrls = MCPServiceConfig.PRESET_SERVICES.associate { it.id to "" }
            preferences.cloudApiKey = ""
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                repeat(2) { cycle ->
                    activity.onActivity {
                        it.startForegroundService(Intent(service).setAction(OverlayService.ACTION_START))
                    }
                    awaitState("cycle $cycle initialization", 90_000) {
                        health().all { it == AppState.ComponentHealth.READY }
                    }
                    context.stopService(service)
                    awaitState("cycle $cycle release", 30_000) {
                        !AppState.overlayRunning.value &&
                            health().all { it == AppState.ComponentHealth.UNKNOWN }
                    }
                }
            }
        } finally {
            context.stopService(service)
            preferences.llmConfigPath = previousPath
            preferences.safetyCoreDisabled = previousDisabled
            preferences.mcpServiceUrls = previousUrls
            preferences.cloudApiKey = previousCloudKey
        }
    }

    private fun health() = listOf(AppState.cameraHealth.value, AppState.safetyCoreHealth.value,
        AppState.asrHealth.value, AppState.agentCoreHealth.value)

    private fun awaitState(label: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("$label: component health=${health()}", condition())
    }
}
