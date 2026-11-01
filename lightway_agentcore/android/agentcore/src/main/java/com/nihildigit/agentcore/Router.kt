package com.nihildigit.agentcore

import android.content.Context
import com.nihildigit.agentcore.chat.ChatContext
import com.nihildigit.agentcore.hub.LocationStore
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolCall
import com.nihildigit.agentcore.model.ToolSerialization
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.lightwayllama.llm.LlmEngine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Router - LLM 黑箱入口
 *
 * 设计原则：
 * - 黑箱封装：App 层只需调用 process(message)
 * - ReAct 路由：自动处理工具调用循环
 * - 依赖 ToolClient 接口而非 Hub 类型
 *
 * ACTION 工具语义：
 * - ACTION 工具执行后 toolResult 不回到当前 ReAct 循环
 * - 结果存入 deferredResults 队列，下次 process() 时注入 history
 * - 确保任何 Tool 都有 ToolResult，LLM 在下一轮对话中能看到执行结果
 *
 * 使用流程：
 * 1. val router = Router(context, config, toolClient)
 * 2. router.process(message) - 处理用户消息
 * 3. router.clearHistory() - 清空历史
 * 4. router.release() - 释放资源
 */
class Router(
    context: Context,
    private val config: Config,
    private val toolClient: ToolClient? = null
) {
    companion object {
        private const val TAG = "Router"

        /** 取消当前 LLM 生成（静态，因为 LlmEngine 是全局的） */
        fun cancelGeneration() {
            LlmEngine.cancelGeneration()
            AgentLogger.normal(TAG, "Cancel", "generation cancelled")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val chatContext: ChatContext

    /** ACTION 工具的延迟结果队列：(assistant tool_call msg, tool result msg) */
    private val deferredResults = mutableListOf<Pair<ChatMessage, ChatMessage>>()

    /**
     * Router 配置
     */
    data class Config(
        val llmConfig: LlmEngine.Config,
        val systemPrompt: String,
        val maxLoops: Int = 10,
        val toolTimeoutMs: Long = 30_000,
        /** Whether ACTION tool results are injected into next turn history. */
        val injectDeferredActionResults: Boolean = true
    )

    data class ToolCallDetail(val name: String, val arguments: String)

    /**
     * 处理结果
     */
    data class Result(
        val response: String?,
        val toolsUsed: List<String> = emptyList(),
        val toolCallDetails: List<ToolCallDetail> = emptyList(),
        val error: String? = null,
        /** 本轮使用工具中最大的 renewalSeconds，无工具调用时为 -1 */
        val sessionTimeoutSeconds: Int = -1
    )

    init {
        AgentLogger.normal(TAG, "Init", "initializing router",
            "modelPath" to config.llmConfig.modelPath,
            "hasToolClient" to (toolClient != null)
        )

        // 加载 LLM
        LlmEngine.load(context, config.llmConfig)

        // 创建 ChatContext
        val tools = toolClient?.listTools() ?: emptyList()
        chatContext = ChatContext(config.systemPrompt, tools)

        // 预热 Stem（System Prompt + Tools）
        val systemMessage = ChatMessage(role = "system", content = config.systemPrompt)
        val messagesJson = json.encodeToString(listOf(systemMessage))
        val toolsJson = ToolSerialization.toTemplateToolsJson(tools)
        val warmupResult = LlmEngine.warmupStem(messagesJson, toolsJson)

        AgentLogger.normal(TAG, "Init", "router initialized",
            "toolCount" to tools.size,
            "stemWarmup" to (warmupResult == 0)
        )
    }

    /**
     * 刷新工具列表
     */
    fun refreshTools() {
        val tools = toolClient?.listTools() ?: emptyList()
        chatContext.setTools(tools)
        AgentLogger.normal(TAG, "RefreshTools", "tools refreshed", "count" to tools.size)
    }

    /**
     * 处理用户消息
     */
    suspend fun process(
        userMessage: String,
        onEvent: (suspend (AgentEvent) -> Unit)? = null
    ): Result {
        try {
            val toolsSnapshot = toolClient?.listTools().orEmpty()
            AgentLogger.normal(TAG, "Process", "processing message",
                "length" to userMessage.length,
                "deferredCount" to deferredResults.size,
                "toolsCount" to toolsSnapshot.size,
                "toolsHead" to toolsSnapshot.take(12).joinToString(",") { it.function.name },
                "message" to userMessage.take(120)
            )

            // 注入上一轮 ACTION 工具的延迟结果
            if (deferredResults.isNotEmpty()) {
                if (config.injectDeferredActionResults) {
                    for ((assistantMsg, toolMsg) in deferredResults) {
                        chatContext.appendMessage(assistantMsg)
                        chatContext.appendMessage(toolMsg)
                    }
                    AgentLogger.normal(TAG, "Process", "injected deferred results",
                        "count" to deferredResults.size
                    )
                } else {
                    AgentLogger.normal(TAG, "Process", "deferred results dropped by config",
                        "count" to deferredResults.size
                    )
                }
                deferredResults.clear()
            }

            // 添加用户消息
            chatContext.appendMessage(ChatMessage(role = "user", content = userMessage))

            var loopCount = 0
            val toolsUsed = mutableListOf<String>()
            val toolCallDetails = mutableListOf<ToolCallDetail>()
            var maxRenewalSeconds = -1

            while (loopCount < config.maxLoops) {
                loopCount++

                AgentLogger.verbose(TAG, "Process", "loop iteration",
                    "current" to loopCount,
                    "max" to config.maxLoops
                )

                // 调用 LLM
                val response = chatContext.chat()

                // 添加 assistant 消息到历史（无论是否有 toolCalls）
                chatContext.appendMessage(ChatMessage(
                    role = "assistant",
                    content = response.content,
                    toolCalls = response.toolCalls
                ))

                // 路由决策
                if (response.toolCalls.isNullOrEmpty()) {
                    response.content?.let { text ->
                        onEvent?.invoke(AgentEvent.Response(text))
                    }
                    AgentLogger.normal(TAG, "Process", "no tool calls, returning response",
                        "contentLen" to (response.content?.length ?: 0),
                        "contentPreview" to (response.content?.take(180) ?: "")
                    )
                    return Result(
                        response = response.content,
                        toolsUsed = toolsUsed,
                        toolCallDetails = toolCallDetails,
                        sessionTimeoutSeconds = maxRenewalSeconds
                    )
                }

                // 执行工具
                for (toolCall in response.toolCalls) {
                    val toolName = toolCall.function.name
                    toolsUsed.add(toolName)
                    toolCallDetails.add(ToolCallDetail(toolName, toolCall.function.arguments))
                    toolsSnapshot.find { it.function.name == toolName }
                        ?.renewalSeconds?.takeIf { it > 0 }
                        ?.let { if (it > maxRenewalSeconds) maxRenewalSeconds = it }

                    onEvent?.invoke(AgentEvent.ToolStart(toolName))
                    AgentLogger.normal(TAG, "Process", "executing tool",
                        "tool" to toolName
                    )

                    val toolResult = executeToolCall(toolCall, config.toolTimeoutMs)

                    if (toolResult.toolType == ToolType.ACTION && !toolResult.isError) {
                        // assistant/tool_calls 已写入历史。延迟注入在下一轮另加 assistant/tool 配对；
                        // OverlayService 关闭此选项，成功 ACTION 的 tool 结果只用于本轮响应。
                        if (config.injectDeferredActionResults) {
                            val assistantMsg = ChatMessage(
                                role = "assistant",
                                content = null,
                                toolCalls = listOf(toolCall)
                            )
                            val toolMsg = ChatMessage(
                                role = "tool",
                                content = toolResult.content,
                                toolCallId = toolCall.id
                            )
                            deferredResults.add(assistantMsg to toolMsg)
                        }

                        onEvent?.invoke(AgentEvent.ToolDone(toolName))
                        onEvent?.invoke(AgentEvent.Response(toolResult.content))
                        AgentLogger.normal(TAG, "Process", "ACTION tool, deferring result",
                            "tool" to toolName
                        )
                        return Result(
                            response = toolResult.content,
                            toolsUsed = toolsUsed,
                            toolCallDetails = toolCallDetails,
                            sessionTimeoutSeconds = maxRenewalSeconds
                        )
                    }

                    // INTERMEDIATE 工具：结果回到 ReAct 循环
                    chatContext.appendMessage(ChatMessage(
                        role = "tool",
                        content = toolResult.content,
                        toolCallId = toolCall.id
                    ))

                    onEvent?.invoke(AgentEvent.ToolDone(toolName))

                    // 如果 Combo 设置了待播报文本，发 Announce 事件（阻塞等 TTS）
                    toolClient?.consumeAnnounce()?.let { announce ->
                        onEvent?.invoke(AgentEvent.Announce(announce))
                    }
                }
            }

            onEvent?.invoke(AgentEvent.Error("Max loops exceeded"))
            AgentLogger.normal(TAG, "Process", "max loops exceeded",
                "loops" to loopCount
            )
            return Result(
                response = null,
                toolsUsed = toolsUsed,
                toolCallDetails = toolCallDetails,
                error = "Max loops exceeded",
                sessionTimeoutSeconds = maxRenewalSeconds
            )
        } finally {
            toolClient?.onSessionComplete()
        }
    }

    /**
     * 执行单个工具调用
     */
    private suspend fun executeToolCall(
        toolCall: ToolCall,
        timeoutMs: Long
    ): ToolResult {
        val toolName = toolCall.function.name
        val client = toolClient ?: return ToolResult(
            toolCallId = toolCall.id,
            name = toolName,
            content = "工具未找到: $toolName",
            isError = true,
            toolType = ToolType.INTERMEDIATE
        )

        // 解析参数
        val args = try {
            val element = json.parseToJsonElement(toolCall.function.arguments)
            element.jsonObject.mapValues { (_, v) ->
                when {
                    v is kotlinx.serialization.json.JsonPrimitive && v.isString -> v.content
                    else -> v.toString()
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }

        // 查找工具类型
        val toolInfo = client.listTools().find { it.function.name == toolName }
        val toolType = toolInfo?.toolType ?: ToolType.INTERMEDIATE

        // 执行工具
        val result = withTimeoutOrNull(timeoutMs) {
            client.callTool(toolName, args, chatContext.getHistory())
        }

        return if (result == null) {
            ToolResult(
                toolCallId = toolCall.id,
                name = toolName,
                content = "工具执行超时",
                isError = true,
                toolType = toolType
            )
        } else {
            result.fold(
                onSuccess = { content ->
                    ToolResult(
                        toolCallId = toolCall.id,
                        name = toolName,
                        content = content,
                        isError = false,
                        toolType = toolType
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        toolCallId = toolCall.id,
                        name = toolName,
                        content = "工具执行失败: ${error.message}",
                        isError = true,
                        toolType = toolType
                    )
                }
            )
        }
    }

    /**
     * 清空历史
     */
    fun clearHistory() {
        chatContext.clearHistory()
        LocationStore.clear()
        AgentLogger.normal(TAG, "ClearHistory", "history cleared")
    }

    /**
     * 释放资源
     */
    fun release() {
        AgentLogger.normal(TAG, "Release", "releasing router")
        LlmEngine.unload()
        deferredResults.clear()
        LocationStore.clear()
    }

    /**
     * 工具执行结果（内部使用）
     */
    private data class ToolResult(
        val toolCallId: String,
        val name: String,
        val content: String,
        val isError: Boolean,
        val toolType: ToolType
    )
}
