package com.nihildigit.agentcore.compact

import com.nihildigit.agentcore.cloud.DashScopeClient
import com.nihildigit.agentcore.model.ChatMessage

/**
 * LLM 上下文压缩服务：调用云端模型生成紧凑记忆。
 */
class LlmCompactService(
    private val cloudClient: DashScopeClient
) {
    companion object {
        private const val MIN_CHARS = 120
        private const val MAX_CHARS = 240
        private const val MAX_HISTORY_MESSAGES = 40
        private const val MAX_MESSAGE_CHARS = 400
    }

    private val systemPrompt = """
        你是对话记忆压缩助手。请将以下对话历史压缩为 120-240 字的紧凑记忆。

        要求：
        1. 保留任务关键信息（如订单号、目的地、状态）
        2. 保留用户询问的重要事实（如天气、时间）
        3. 使用第三人称描述（"用户正在..."）
        4. 省略无关闲聊和重复内容
    """.trimIndent()

    suspend fun compact(history: List<ChatMessage>, maxChars: Int = MAX_CHARS): String {
        val prompt = buildCompactPrompt(history)
        val raw = cloudClient.chat(systemPrompt = systemPrompt, userPrompt = prompt)
        return sanitizeCompactMemory(raw, maxChars)
    }

    private fun buildCompactPrompt(history: List<ChatMessage>): String {
        val items = history.takeLast(MAX_HISTORY_MESSAGES).mapNotNull { message ->
            val content = message.content?.trim().orEmpty()
            if (content.isBlank()) return@mapNotNull null
            val role = when (message.role) {
                "user" -> "用户"
                "assistant" -> "助手"
                "tool" -> "工具"
                "system" -> "系统"
                else -> message.role
            }
            val normalized = content.replace(Regex("\\s+"), " ").take(MAX_MESSAGE_CHARS)
            "$role: $normalized"
        }

        return buildString {
            append("对话历史：\n")
            append(items.joinToString(separator = "\n"))
            append("\n\n紧凑记忆：")
        }
    }

    private fun sanitizeCompactMemory(raw: String, maxChars: Int): String {
        val trimmed = raw.trim().trim('"')
        val normalized = trimmed.replace(Regex("\\s+"), " ")
        val limited = if (normalized.length > maxChars) normalized.take(maxChars) else normalized
        return limited.takeIf { it.length >= MIN_CHARS } ?: limited
    }
}
