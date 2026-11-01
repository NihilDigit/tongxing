package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.adapter.CloudChatAdapter
import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.*

/**
 * 屏幕内容识别 Combo
 *
 * 通过无障碍截图 + 云端视觉模型识别屏幕内容。
 * 不依赖 MCP 服务，直接调用云端 API。
 */
class DescribeScreenCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig,
    private val visionAdapterProvider: () -> CloudChatAdapter?
) : ComboTool(connectionManager, contextProvider, config) {

    companion object {
        private const val TAG = "DescribeScreenCombo"
        private const val MAX_HISTORY_CHARS = 800
        private const val MAX_HISTORY_MESSAGES = 8

        private const val SYSTEM_PROMPT = """
            你是盲人助手的屏幕识别模块，帮助用户理解手机屏幕内容，你的回答会直接通过TTS语音播报。

            播报要求：
            - 用1-3句自然口语提炼屏幕的核心信息
            - 如有验证码：只回复"验证码："加验证码内容，字符之间用中文逗号隔开
            - 如有按钮文字、重要提示，逐字朗读
            - 按用户问题的关注点回答，不要面面俱到
            - 直接说重点，不要"屏幕显示..."这样的开场白
            - 不要使用列表、序号或书面语
            - 不要添加任何元信息或结束语
        """
    }

    @Volatile private var lastCallMs: Long = 0L
    @Volatile private var backoffUntilMs: Long = 0L

    override val name = "describe_screen"

    override val description = "读取手机屏幕内容：验证码、App界面文字"

    override val group = ComboGroup.OTHER

    override val toolType: ToolType = ToolType.ACTION

    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("question") {
                put("type", "string")
                put("description", "可选，想问屏幕上的什么内容")
            }
        }
    }

    override val requiredServices: List<String> = emptyList()

    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        if (config.cloudApiKey.isEmpty()) {
            return ComboToolResult.success("云端已禁用或未配置 API Key。")
        }
        val adapter = visionAdapterProvider()
            ?: return ComboToolResult.success("云端视觉未配置。")

        val now = System.currentTimeMillis()
        if (now < backoffUntilMs) {
            return ComboToolResult.success("云端视觉暂不可用，请稍后再试。")
        }
        if (now - lastCallMs < config.visionCooldownMs) {
            return ComboToolResult.success("视觉请求过于频繁，请稍后再试。")
        }

        val base64 = contextProvider.getScreenshotBase64()
            ?: return ComboToolResult.success("无障碍截图不可用，请确认已开启无障碍服务并允许读取屏幕内容。")

        lastCallMs = now

        val question = (arguments["question"] as? String)?.takeIf { it.isNotBlank() }
            ?: "请描述当前屏幕内容"
        val historyText = summarizeHistory(history)

        val messages = mutableListOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT.trimIndent())
        )
        historyText?.let {
            messages.add(ChatMessage(role = "assistant", content = "最近对话摘要：$it"))
        }
        messages.add(ChatMessage(role = "user", content = question))

        val imageUrl = "data:image/jpeg;base64,$base64"

        return try {
            val result = adapter.call(messages = messages, imageUrls = listOf(imageUrl))
            ComboToolResult.success(result.content.ifBlank { "云端未返回内容。" })
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "describe_screen failed", e)
            backoffUntilMs = System.currentTimeMillis() + config.visionBackoffMs
            ComboToolResult.success("云端视觉暂不可用（${e.message ?: "网络错误"}）。")
        }
    }

    private fun summarizeHistory(
        history: List<ChatMessage>,
        maxMessages: Int = MAX_HISTORY_MESSAGES,
        maxChars: Int = MAX_HISTORY_CHARS
    ): String? {
        if (history.isEmpty()) return null
        val window = history.takeLast(maxMessages)
        val builder = StringBuilder()
        for (msg in window) {
            val content = msg.content ?: continue
            val line = "${msg.role}: ${content.trim()}\n"
            if (builder.length + line.length > maxChars) break
            builder.append(line)
        }
        return builder.toString().trim().ifEmpty { null }
    }
}
