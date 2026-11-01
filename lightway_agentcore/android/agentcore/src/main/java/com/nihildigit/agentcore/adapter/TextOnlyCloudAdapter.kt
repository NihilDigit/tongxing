package com.nihildigit.agentcore.adapter

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.exception.ApiException
import com.nihildigit.agentcore.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Text-only cloud adapter using DashScope Generation API.
 *
 * For pure text reasoning models (e.g., qwen3-max), use GenerationParam instead of
 * MultiModalConversationParam to avoid 400 InvalidParameter (url error).
 *
 * Unlike CloudChatAdapter (which handles multimodal with images), this adapter:
 * - Only supports text messages (no images)
 * - Uses the text-optimized Generation API
 * - Better for reasoning and web search tasks (ask_cloud tool)
 */
@Deprecated("Dead code: never instantiated anywhere. ask_cloud was never implemented as a ComboTool.")
class TextOnlyCloudAdapter(
    private val apiKey: String,
    private val config: com.nihildigit.agentcore.model.AgentConfig = com.nihildigit.agentcore.model.AgentConfig(),
    private val model: String = "qwen3-max"
) {

    companion object {
        private const val TAG = "TextOnlyCloudAdapter"
    }

    /**
     * Call text-only reasoning API (non-streaming).
     */
    suspend fun call(
        messages: List<ChatMessage>,
        imageUrls: List<String> = emptyList()
    ): CloudChatResult = withContext(Dispatchers.IO) {
        try {
            callTextOnly(messages)
        } catch (e: ApiException) {
            throw CloudException("DashScope API error: ${e.message}", e)
        } catch (e: Exception) {
            throw CloudException("Cloud chat failed: ${e.message}", e)
        }
    }

    private suspend fun callTextOnly(messages: List<ChatMessage>): CloudChatResult {
        val generation = Generation()
        val dashscopeMessages = buildDashScopeMessages(messages)

        val param = GenerationParam.builder()
            .apiKey(apiKey)
            .model(model)
            .messages(dashscopeMessages)
            .resultFormat("message")
            .temperature(0.7f)
            .topP(0.8)
            .topK(20)
            .maxTokens(2048)
            .enableSearch(true)  // 启用网络搜索获取最新信息
            .build()

        val result = generation.call(param)

        val content = result.output?.choices?.firstOrNull()?.message?.content
            ?: result.output?.text
            ?: ""

        val detectedMarker = content.contains(config.cloudCompletionMarker)

        return CloudChatResult(
            content = content,
            shouldDowngrade = detectedMarker,
            usage = result.usage?.let { usage ->
                com.nihildigit.agentcore.model.Usage(
                    promptTokens = usage.inputTokens ?: 0,
                    completionTokens = usage.outputTokens ?: 0,
                    totalTokens = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)
                )
            }
        )
    }

    private fun buildDashScopeMessages(messages: List<ChatMessage>): List<Message> {
        return messages.mapNotNull { msg ->
            val content = msg.content ?: return@mapNotNull null
            Message.builder()
                .role(msg.role)
                .content(content)
                .build()
        }
    }
}
