package com.nihildigit.tongxing.settings

import android.content.Context
import android.content.SharedPreferences
import com.nihildigit.tongxing.BuildConfig

/**
 * 应用设置管理
 */
class AppPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "lightway_prefs"
        private const val KEY_LLM_CONFIG_PATH = "llm_config_path"
        private const val KEY_CLOUD_API_KEY = "cloud_api_key"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_ONBOARDING_STEP_ROUTE = "onboarding_step_route"
        private const val KEY_ENABLED_COMBOS = "enabled_combos"
        private const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        private const val KEY_CURRENT_MODE = "current_mode"
        private const val KEY_NAV_SESSION_RESET_TOKEN = "nav_session_reset_token"

        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_SAFETY_SENSITIVITY = "safety_sensitivity"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_AGENT_AUTO_PRUNE_DELAY_SECONDS = "agent_auto_prune_delay_seconds"
        private const val KEY_AGENT_MAX_LOOPS = "agent_max_loops"
        private const val KEY_AGENT_LOG_LEVEL = "agent_log_level"

        private const val KEY_LLM_TEMPERATURE = "llm_temperature"
        private const val KEY_LLM_TOP_P = "llm_top_p"
        private const val KEY_LLM_TOP_K = "llm_top_k"
        private const val KEY_LLM_MIN_P = "llm_min_p"
        private const val KEY_LLM_THINKING_MODE = "llm_thinking_mode"
        private const val KEY_DEMO_VIDEO_PATH_ONE = "demo_video_path_one"
        private const val KEY_DEMO_VIDEO_PATH_TWO = "demo_video_path_two"
        private const val KEY_SAFETY_CORE_DISABLED = "safety_core_disabled"
        private const val KEY_TUNING_SERVER_ENABLED = "tuning_server_enabled"
        private const val MCP_URL_PREFIX = "mcp_url_"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mcpServiceUrls: Map<String, String>
        get() = prefs.all.entries.filter { it.key.startsWith(MCP_URL_PREFIX) }
            .associate { it.key.removePrefix(MCP_URL_PREFIX) to (it.value as? String).orEmpty() }
        set(value) {
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith(MCP_URL_PREFIX) }.forEach { remove(it) }
                value.forEach { (id, url) -> putString(MCP_URL_PREFIX + id, url.trim()) }
            }.apply()
        }

    // GGUF model file path
    var llmConfigPath: String
        get() = prefs.getString(KEY_LLM_CONFIG_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_CONFIG_PATH, value).apply()

    var cloudApiKey: String
        get() {
            val userKey = prefs.getString(KEY_CLOUD_API_KEY, "") ?: ""
            return if (userKey.isNotEmpty()) userKey else BuildConfig.CLOUD_API_KEY
        }
        set(value) = prefs.edit().putString(KEY_CLOUD_API_KEY, value).apply()

    fun hasCustomApiKey(): Boolean {
        return prefs.getString(KEY_CLOUD_API_KEY, "")?.isNotEmpty() == true
    }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var onboardingStepRoute: String
        get() = prefs.getString(KEY_ONBOARDING_STEP_ROUTE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ONBOARDING_STEP_ROUTE, value).apply()

    /**
     * 已启用的 Combo 工具名称集合
     * 注意：首次启动时的“默认全启用”由 OverlayService 在运行时回填。
     * 此处仅返回持久化值；未配置时返回空集合。
     */
    var enabledCombos: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_COMBOS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_COMBOS, value).apply()

    /**
     * 是否已存在 enabledCombos 的用户配置。
     * 用于区分“首次启动未配置”和“用户显式配置为空集合”。
     */
    fun hasEnabledCombosConfigured(): Boolean = prefs.contains(KEY_ENABLED_COMBOS)

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_ENABLED, value).apply()

    /**
     * 当前助手模式（供 AccessibilityService 等后台组件读取）
     *
     * 取值："NAVIGATION"
     */
    var currentMode: String
        get() = prefs.getString(KEY_CURRENT_MODE, "NAVIGATION") ?: "NAVIGATION"
        set(value) = prefs.edit().putString(KEY_CURRENT_MODE, value).apply()

    /**
     * 导航模式下自动点击“开始导航”的会话去重重置 token。
     * 每次进入 NAVIGATION 模式时更新，用于通知 AccessibilityService 清空去重状态。
     */
    var navSessionResetToken: String
        get() = prefs.getString(KEY_NAV_SESSION_RESET_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NAV_SESSION_RESET_TOKEN, value).apply()

    /**
     * 检查是否至少配置了一个后端（本地或云端）
     */
    fun hasValidConfig(): Boolean {
        return llmConfigPath.isNotEmpty() || cloudApiKey.isNotEmpty()
    }

    var ttsSpeechRate: Float
        get() = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.1f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    /**
     * LOW | MEDIUM | HIGH
     */
    var safetySensitivity: String
        get() = prefs.getString(KEY_SAFETY_SENSITIVITY, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString(KEY_SAFETY_SENSITIVITY, value).apply()

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    /**
     * <0 disables auto-prune.
     */
    var agentAutoPruneDelaySeconds: Int
        get() = prefs.getInt(KEY_AGENT_AUTO_PRUNE_DELAY_SECONDS, 30)
        set(value) = prefs.edit().putInt(KEY_AGENT_AUTO_PRUNE_DELAY_SECONDS, value).apply()

    var agentMaxLoops: Int
        get() = prefs.getInt(KEY_AGENT_MAX_LOOPS, 10)
        set(value) = prefs.edit().putInt(KEY_AGENT_MAX_LOOPS, value.coerceIn(5, 15)).apply()

    /**
     * MINIMAL | NORMAL | VERBOSE
     */
    var agentLogLevel: String
        get() = prefs.getString(KEY_AGENT_LOG_LEVEL, "NORMAL") ?: "NORMAL"
        set(value) = prefs.edit().putString(KEY_AGENT_LOG_LEVEL, value).apply()

    var llmTemperature: Float
        get() = prefs.getFloat(KEY_LLM_TEMPERATURE, 0.7f)
        set(value) = prefs.edit().putFloat(KEY_LLM_TEMPERATURE, value.coerceIn(0.0f, 2.0f)).apply()

    var llmTopP: Float
        get() = prefs.getFloat(KEY_LLM_TOP_P, 0.8f)
        set(value) = prefs.edit().putFloat(KEY_LLM_TOP_P, value.coerceIn(0.0f, 1.0f)).apply()

    var llmTopK: Int
        get() = prefs.getInt(KEY_LLM_TOP_K, 20)
        set(value) = prefs.edit().putInt(KEY_LLM_TOP_K, value.coerceIn(1, 100)).apply()

    var llmMinP: Float
        get() = prefs.getFloat(KEY_LLM_MIN_P, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_LLM_MIN_P, value.coerceIn(0.0f, 1.0f)).apply()

    var llmThinkingMode: Boolean
        get() = prefs.getBoolean(KEY_LLM_THINKING_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_LLM_THINKING_MODE, value).apply()

    var demoVideoPathOne: String
        get() = prefs.getString(KEY_DEMO_VIDEO_PATH_ONE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEMO_VIDEO_PATH_ONE, value).apply()

    var demoVideoPathTwo: String
        get() = prefs.getString(KEY_DEMO_VIDEO_PATH_TWO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEMO_VIDEO_PATH_TWO, value).apply()

    /**
     * 禁用 SafetyCore 避障功能（开发者选项）
     */
    var safetyCoreDisabled: Boolean
        get() = prefs.getBoolean(KEY_SAFETY_CORE_DISABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SAFETY_CORE_DISABLED, value).apply()

    var tuningServerEnabled: Boolean
        get() = prefs.getBoolean(KEY_TUNING_SERVER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TUNING_SERVER_ENABLED, value).apply()

    /**
     * 清除所有设置
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
