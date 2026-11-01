package com.nihildigit.agentcore.adapter

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.Role
import com.alibaba.dashscope.exception.ApiException
import com.nihildigit.agentcore.model.*
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Adapter for DashScope cloud API using official Java SDK.
 * Handles streaming responses and completion marker detection for auto-downgrade.
 */
class CloudChatAdapter(
    private val apiKey: String,
    private val config: AgentConfig = AgentConfig(),
    private val model: String = "qwen-vl-plus",
    private val baseSystemPrompt: String = CLOUD_SYSTEM_PROMPT
) {
    private val isCancelled = AtomicBoolean(false)
    private val activeDisposable = AtomicReference<Disposable?>()
    private val activeLatch = AtomicReference<CountDownLatch?>()
    private val streamLock = Any()  // Lock for atomic stream state updates

    companion object {
        // Cloud-specific system prompt for vision/complex reasoning
        private val CLOUD_SYSTEM_PROMPT = """
            |你是一个盲人助手AI的云端视觉处理模块。
            |
            |你的任务：
            |1. 分析用户提供的图片或复杂问题
            |2. 提供详细、准确的描述，适合语音播报
            |3. 完成任务后，在回复末尾添加："本轮已完成，可切回本地"
            |
            |重要规则：
            |1. 描述要详细但简洁，适合听觉理解
            |2. 如果图片中有文字，务必识别并朗读
            |3. 描述空间关系时使用方位词（左上、右下等）
            |4. 不要输出思维过程，直接给出结果
        """.trimMargin()

        // Vision-specific system prompt for TTS playback (no completion marker)
        val VISION_TTS_PROMPT = """
            |你是盲人助手的视觉识别模块，你的回答会直接通过语音播报给用户。
            |
            |播报规则：
            |1. 用简洁、口语化的1-3句话描述图片的核心信息
            |2. 优先描述用户关心的内容（障碍物、文字、位置、颜色等）
            |3. 使用自然的语音节奏，像朋友聊天一样，不要使用列表或序号
            |4. 如有文字内容，清晰朗读原文
            |5. 直接给出答案，不要说"这是..."、"图片中..."等开场白
            |6. 不要添加任何元信息或结束语，回答后立即停止
            |
            |示例：
            |❌ 不好："这是一个关于GLM-4模型的介绍页面，主要内容包括..."
            |✅ 很好："智谱AI发布GLM-4.7旗舰模型，在编码能力上提升了5.8%，LiveCodeBench得分84.8，超过Claude Sonnet 4.5"
        """.trimMargin()
    }

    /**
     * Stream a chat request to DashScope cloud.
     * Returns true if completion marker was detected (should downgrade to local).
     */
    suspend fun streamChat(
        messages: List<ChatMessage>,
        imageUrls: List<String> = emptyList(),
        sessionId: String? = null,
        onDelta: (String) -> Unit
    ): CloudChatResult = withContext(Dispatchers.IO) {
        isCancelled.set(false)

        try {
            streamMultimodalChat(messages, imageUrls, onDelta)
        } catch (e: ApiException) {
            throw CloudException("DashScope API error: ${e.message}", e)
        } catch (e: Exception) {
            throw CloudException("Cloud chat failed: ${e.message}", e)
        }
    }

    /**
     * Stream multimodal/text chat using DashScope SDK (MultiModalConversation).
     */
    private suspend fun streamMultimodalChat(
        messages: List<ChatMessage>,
        imageUrls: List<String>,
        onDelta: (String) -> Unit
    ): CloudChatResult = suspendCancellableCoroutine { continuation ->
        val conversation = MultiModalConversation()
        val multiModalMessages = buildMultiModalMessages(messages, imageUrls)
        val param = MultiModalConversationParam.builder()
            .apiKey(apiKey)
            .model(model)
            .messages(multiModalMessages)
            .incrementalOutput(true)
            .modalities(listOf("text"))
            .build()

        val responseBuilder = StringBuilder()
        var detectedCompletionMarker = false

        val flowable: Flowable<MultiModalConversationResult> = conversation.streamCall(param)

        val disposable = flowable.subscribe({ result ->
            if (continuation.isActive) {
                val output = result.output
                val choices = output?.choices
                if (!choices.isNullOrEmpty()) {
                    val message = choices[0].message
                    val content = message?.content
                    val text = extractTextContent(content)
                    if (text.isNotEmpty()) {
                        responseBuilder.append(text)
                        onDelta(text)
                        if (responseBuilder.contains(config.cloudCompletionMarker)) {
                            detectedCompletionMarker = true
                        }
                    }
                }
            }
        }, { error ->
            if (continuation.isActive) {
                continuation.resumeWith(Result.failure(error))
            }
        }, {
            if (continuation.isActive) {
                continuation.resumeWith(
                    Result.success(
                        CloudChatResult(
                            content = responseBuilder.toString(),
                            shouldDowngrade = detectedCompletionMarker,
                            usage = null
                        )
                    )
                )
            }
        })

        continuation.invokeOnCancellation {
            disposable.dispose()
            isCancelled.set(true)
        }
    }

    /**
     * Non-streaming call for simple requests (text or multimodal).
     */
    suspend fun call(
        messages: List<ChatMessage>,
        imageUrls: List<String> = emptyList()
    ): CloudChatResult = withContext(Dispatchers.IO) {
        try {
            callMultimodal(messages, imageUrls)
        } catch (e: ApiException) {
            throw CloudException("DashScope API error: ${e.message}", e)
        } catch (e: Exception) {
            throw CloudException("Cloud chat failed: ${e.message}", e)
        }
    }

    private suspend fun callMultimodal(
        messages: List<ChatMessage>,
        imageUrls: List<String>
    ): CloudChatResult {
        val conversation = MultiModalConversation()

        val multiModalMessages = buildMultiModalMessages(messages, imageUrls)

        val param = MultiModalConversationParam.builder()
            .apiKey(apiKey)
            .model(model)
            .messages(multiModalMessages)
            .modalities(listOf("text"))
            .incrementalOutput(false)
            .build()

        val result = conversation.call(param)

        val content = extractTextContent(
            result.output?.choices?.firstOrNull()?.message?.content
        )

        val detectedMarker = content.contains(config.cloudCompletionMarker)

        return CloudChatResult(
            content = content,
            shouldDowngrade = detectedMarker,
            usage = result.usage?.let { usage ->
                Usage(
                    promptTokens = usage.inputTokens ?: 0,
                    completionTokens = usage.outputTokens ?: 0,
                    totalTokens = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)
                )
            }
        )
    }

    /**
     * Cancel the current streaming request.
     */
    fun cancel() {
        isCancelled.set(true)
        synchronized(streamLock) {
            activeDisposable.getAndSet(null)?.dispose()
            activeLatch.getAndSet(null)?.countDown()
        }
    }

    private fun buildMultiModalMessages(
        messages: List<ChatMessage>,
        imageUrls: List<String>
    ): List<MultiModalMessage> {
        val list = mutableListOf<MultiModalMessage>()

        if (baseSystemPrompt.isNotBlank()) {
            list.add(
                MultiModalMessage.builder()
                    .role(Role.SYSTEM.value)
                    .content(
                        listOf(
                            mapOf(
                                "type" to "text",
                                "text" to baseSystemPrompt
                            )
                        )
                    )
                    .build()
            )
        }

        for (msg in messages) {
            val content = mutableListOf<Map<String, Any>>()

            if (msg.role == "user" && imageUrls.isNotEmpty()) {
                imageUrls.forEach { url ->
                    content.add(
                        mapOf(
                            "type" to "image",
                            "image" to url
                        )
                    )
                }
            }

            msg.content?.let { text ->
                content.add(
                    mapOf(
                        "type" to "text",
                        "text" to text
                    )
                )
            }

            list.add(
                MultiModalMessage.builder()
                    .role(msg.role)
                    .content(content)
                    .build()
            )
        }

        return list
    }

    private fun extractTextContent(content: List<Map<String, Any>>?): String {
        if (content.isNullOrEmpty()) return ""
        val builder = StringBuilder()
        content.forEach { item ->
            val type = item["type"] as? String
            val text = item["text"] as? String
            if (type != null && type != "text") {
                return@forEach
            }
            if (!text.isNullOrEmpty()) {
                builder.append(text)
            }
        }
        return builder.toString()
    }
}

/**
 * Result from cloud chat.
 */
data class CloudChatResult(
    val content: String,
    val shouldDowngrade: Boolean,
    val usage: Usage?
)

/**
 * Cloud-related exception.
 */
class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)
