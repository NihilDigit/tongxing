package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.adapter.CloudChatAdapter
import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.*

/**
 * 相机视觉识别 Combo
 *
 * 通过相机帧 + 云端视觉模型识别现实环境。
 * 不依赖 MCP 服务，直接调用云端 API。
 */
class DescribeCameraCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig,
    private val visionAdapterProvider: () -> CloudChatAdapter?
) : ComboTool(connectionManager, contextProvider, config) {

    companion object {
        private const val TAG = "DescribeCameraCombo"
        private const val MAX_HISTORY_CHARS = 800
        private const val MAX_HISTORY_MESSAGES = 8

        private const val SYSTEM_PROMPT = """
            你是盲人导航助手的环境感知模块，通过相机帮助用户了解周围环境，你的回答会直接通过TTS语音播报。

            播报要求：
            - 用1-2句简短、紧凑的口语描述最重要的信息
            - 优先级：障碍物 > 路况 > 人/车 > 环境特征
            - 说明方位时用"正前方"、"左侧2米"、"右前方"等精确表达
            - 直接说结果，不要"看到..."、"有..."这样的开场白
            - 紧急情况（障碍、坑洼、台阶）立即警示
            - 不要使用书面语、列表或结束语
        """
    }

    @Volatile private var lastCallMs: Long = 0L
    @Volatile private var backoffUntilMs: Long = 0L

    override val name = "describe_camera"

    override val description = "用摄像头看现实环境：前方路况、障碍物、物体、手持物品"

    override val group = ComboGroup.OTHER

    override val toolType: ToolType = ToolType.ACTION

    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "描述视觉需求（如'前面有什么'/'我手里拿着的是什么药'/'这个瓶子是什么颜色'）")
            }
            putJsonObject("include_history") {
                put("type", "boolean")
                put("description", "是否附带对话摘要以提供背景")
                put("default", false)
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
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

        val base64 = contextProvider.getCameraBase64()
        if (base64.isNullOrEmpty()) {
            return ComboToolResult.success("暂无可用相机帧，请确认已授予相机权限且相机预览正常。")
        }
        lastCallMs = now

        val query = (arguments["query"] as? String)?.takeIf { it.isNotBlank() }
            ?: "请描述当前场景"
        val includeHistory = arguments["include_history"] as? Boolean ?: false
        val historyText = if (includeHistory) summarizeHistory(history) else null

        val messages = mutableListOf(
            ChatMessage(role = "system", content = SYSTEM_PROMPT.trimIndent())
        )
        historyText?.let {
            messages.add(ChatMessage(role = "assistant", content = "最近对话摘要：$it"))
        }
        messages.add(ChatMessage(role = "user", content = query))

        val imageUrl = "data:image/jpeg;base64,$base64"

        return try {
            val result = adapter.call(messages = messages, imageUrls = listOf(imageUrl))
            ComboToolResult.success(result.content.ifBlank { "云端未返回内容。" })
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "describe_camera failed", e)
            backoffUntilMs = System.currentTimeMillis() + config.visionBackoffMs
            ComboToolResult.success("云端视觉暂不可用（${e.message ?: "网络错误"}），已保留本地能力。")
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
