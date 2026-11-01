package com.nihildigit.tongxing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nihildigit.tongxing.mcp.MCPServiceConfig
import com.nihildigit.tongxing.settings.AppPreferences
import com.nihildigit.tongxing.ui.screen.ServiceConnectionsScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ServiceConnectionsTest {
    @get:Rule val compose = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "service-connection-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(preferenceName, mode)
    }

    @After
    fun cleanup() { base.deleteSharedPreferences(preferenceName) }

    @Test
    fun runtimeUrlsPersistAndBecomeHubDefinitions() {
        val urls = MCPServiceConfig.PRESET_SERVICES.associate { it.id to "https://example.invalid/${it.id}/mcp" }
        AppPreferences(context).mcpServiceUrls = urls
        val restored = AppPreferences(context).mcpServiceUrls
        assertEquals(urls, restored)
        val definitions = MCPServiceConfig.toHubDefinitions(restored)
        assertEquals(urls.keys, definitions.map { it.id }.toSet())
        definitions.forEach { assertEquals(urls[it.id], it.streamableHttpUrl) }
    }

    @Test
    fun emptySavedUrlDisablesTheConnection() {
        val preferences = AppPreferences(context)
        preferences.mcpServiceUrls = mapOf("amap-maps" to "https://example.invalid/mcp")
        preferences.mcpServiceUrls = mapOf("amap-maps" to "")
        assertTrue(MCPServiceConfig.toHubDefinitions(preferences.mcpServiceUrls).none { it.id == "amap-maps" })
    }

    @Test
    fun acceptsHttpsEndpointsAndRejectsInvalidAddresses() {
        assertTrue(MCPServiceConfig.isValidServiceUrl(""))
        assertTrue(MCPServiceConfig.isValidServiceUrl("https://example.invalid/mcp?key=placeholder"))
        for (url in listOf("http://example.invalid/mcp", "https://", "not a url",
            "https://user:password@example.invalid/mcp", "https://example.invalid/#fragment")) {
            assertFalse(url, MCPServiceConfig.isValidServiceUrl(url))
        }
    }

    @Test
    fun systemLanguageSelectsChineseOrEnglishFallback() {
        fun localized(language: String): Context {
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocales(LocaleList.forLanguageTags(language))
            return base.createConfigurationContext(configuration)
        }
        assertEquals("服务连接", localized("zh-CN").getString(R.string.service_connections))
        assertEquals("服務連線", localized("zh-TW").getString(R.string.service_connections))
        assertEquals("Service connections", localized("en-US").getString(R.string.service_connections))
        assertEquals("Service connections", localized("fr-FR").getString(R.string.service_connections))
    }

    @Test
    fun englishEditorSavesAUsableRuntimeEndpoint() {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags("en-US"))
        val english = base.createConfigurationContext(configuration)
        val preferences = AppPreferences(context)
        compose.setContent {
            val view = LocalView.current
            DisposableEffect(view) {
                val previous = view.keepScreenOn
                view.keepScreenOn = true
                onDispose { view.keepScreenOn = previous }
            }
            CompositionLocalProvider(LocalContext provides english, LocalConfiguration provides configuration) {
                ServiceConnectionsScreen(preferences) { }
            }
        }
        compose.onNodeWithText("Amap: maps and weather").performTextInput("https://example.invalid/mcp")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.runOnIdle {
            val restored = AppPreferences(context).mcpServiceUrls
            assertEquals("https://example.invalid/mcp", restored["amap-maps"])
            assertEquals(listOf("amap-maps"), MCPServiceConfig.toHubDefinitions(restored).map { it.id })
        }
    }
}
