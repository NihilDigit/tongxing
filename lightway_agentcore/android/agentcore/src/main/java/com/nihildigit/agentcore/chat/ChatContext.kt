package com.nihildigit.agentcore.chat

import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.FunctionCall
import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolCall
import com.nihildigit.agentcore.model.ToolSerialization
import com.nihildigit.lightwayllama.llm.LlmEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ChatContext - 会话上下文管理
 *
 * 职责：
 * - 管理会话历史
 * - 管理 systemPrompt 和 toolsJson
 * - 调用 LlmEngine.generate() 并解析响应
 *
 * 设计原则：
 * - 无状态机：简单的数据容器 + 推理调用
 * - 不持有 LlmEngine 引用：使用 LlmEngine 单例
 */
class ChatContext(
    private val systemPrompt: String,
    tools: List<Tool> = emptyList()
) {
    companion object {
        private const val TAG = "ChatContext"
        private const val LOG_CHUNK_SIZE = 3500
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Required by llama.cpp message parser: assistant.tool_calls[].type must be present.
        encodeDefaults = true
        // Avoid emitting null optional fields that confuse upstream OAI parser.
        explicitNulls = false
    }
    private val history = mutableListOf<ChatMessage>()
    private var toolsJson: String? = ToolSerialization.toTemplateToolsJson(tools)
    private var toolsList: List<Tool> = tools

    /**
     * 更新工具列表
     */
    fun setTools(tools: List<Tool>) {
        toolsList = tools
        toolsJson = ToolSerialization.toTemplateToolsJson(tools)
        AgentLogger.verbose(TAG, "SetTools", "tools updated", "count" to tools.size)
    }

    /**
     * 获取工具列表
     */
    fun getTools(): List<Tool> = toolsList

    /**
     * 添加消息到历史
     */
    fun appendMessage(message: ChatMessage) {
        history.add(message)
        AgentLogger.verbose(TAG, "Append", "message added",
            "role" to message.role,
            "contentLen" to (message.content?.length ?: 0)
        )
    }

    /**
     * 获取历史记录
     */
    fun getHistory(): List<ChatMessage> = history.toList()

    /**
     * 清空历史并重置 KV Cache
     */
    fun clearHistory() {
        history.clear()
        LlmEngine.pruneToStem()
        AgentLogger.normal(TAG, "Clear", "history cleared and KV cache pruned")
    }

    /**
     * 聊天响应
     */
    data class ChatResponse(
        val content: String?,
        val toolCalls: List<ToolCall>?,
        val promptTokens: Int,
        val generatedTokens: Int
    )

    /**
     * 执行聊天推理
     * @param onDelta 流式回调
     * @return 聊天响应
     */
    fun chat(onDelta: ((String) -> Unit)? = null): ChatResponse {
        // 构建消息 JSON
        val messagesJson = buildMessagesJson()

        AgentLogger.verbose(TAG, "Chat", "starting inference",
            "historySize" to history.size,
            "hasTools" to (toolsJson != null)
        )

        // 调用 LlmEngine
        val result = LlmEngine.generate(
            messagesJson = messagesJson,
            toolsJson = toolsJson,
            onChunk = if (onDelta != null) { chunk ->
                onDelta(chunk)
                false // 不取消
            } else null
        )

        logParsedResponseJson(result.parsedResponseJson)

        // 直接消费 llama.cpp 的 OAI 兼容 JSON，避免强类型反序列化丢失 tool_calls。
        val (content, toolCalls) = parseOaiAssistantMessage(result.parsedResponseJson)

        AgentLogger.verbose(TAG, "Chat", "inference complete",
            "contentLen" to (content?.length ?: 0),
            "toolCallCount" to (toolCalls?.size ?: 0),
            "promptTokens" to result.promptTokens,
            "generatedTokens" to result.generatedTokens
        )

        return ChatResponse(
            content = content,
            toolCalls = toolCalls,
            promptTokens = result.promptTokens,
            generatedTokens = result.generatedTokens
        )
    }

    private fun logParsedResponseJson(jsonStr: String?) {
        if (jsonStr.isNullOrBlank()) {
            AgentLogger.normal(TAG, "OAI-JSON", "parsedResponseJson empty")
            return
        }
        val total = jsonStr.length
        val chunks = (total + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE
        AgentLogger.normal(TAG, "OAI-JSON", "parsedResponseJson dump begin",
            "length" to total,
            "chunks" to chunks
        )
        for (i in 0 until chunks) {
            val start = i * LOG_CHUNK_SIZE
            val end = minOf(start + LOG_CHUNK_SIZE, total)
            AgentLogger.normal(TAG, "OAI-JSON", "chunk ${i + 1}/$chunks",
                "payload" to jsonStr.substring(start, end)
            )
        }
        AgentLogger.normal(TAG, "OAI-JSON", "parsedResponseJson dump end")
    }

    private fun parseOaiAssistantMessage(jsonStr: String?): Pair<String?, List<ToolCall>?> {
        if (jsonStr.isNullOrBlank()) return null to null
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val content = extractContent(root)
            val toolCalls = extractToolCalls(root)
            content to toolCalls
        } catch (e: Exception) {
            AgentLogger.verbose(TAG, "Parse", "failed to parse OAI json",
                "error" to (e.message ?: "unknown")
            )
            null to null
        }
    }

    private fun extractContent(root: JsonObject): String? {
        val contentElem = root["content"] ?: return null
        return when (contentElem) {
            is JsonPrimitive -> contentElem.contentOrNull
            else -> contentElem.toString()
        }
    }

    private fun extractToolCalls(root: JsonObject): List<ToolCall>? {
        val arr = root["tool_calls"] as? JsonArray ?: return null
        val parsed = arr.mapIndexedNotNull { index, elem ->
            val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
            val functionObj = obj["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (functionObj["name"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: return@mapIndexedNotNull null
            val argumentsElem = functionObj["arguments"]
            val arguments = when (argumentsElem) {
                is JsonPrimitive -> argumentsElem.contentOrNull ?: "{}"
                is JsonElement -> argumentsElem.toString()
                else -> "{}"
            }
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
                ?: "call_${System.currentTimeMillis()}_$index"
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "function"
            ToolCall(
                id = id,
                type = type,
                function = FunctionCall(
                    name = name,
                    arguments = arguments
                )
            )
        }
        return parsed.ifEmpty { null }
    }

    /**
     * 构建消息 JSON
     */
    private fun buildMessagesJson(): String {
        val messages = buildList {
            // 添加 system message
            if (systemPrompt.isNotBlank()) {
                add(ChatMessage(role = "system", content = systemPrompt))
            }
            // 添加历史消息
            addAll(history)
        }
        return json.encodeToString(messages)
    }
}
