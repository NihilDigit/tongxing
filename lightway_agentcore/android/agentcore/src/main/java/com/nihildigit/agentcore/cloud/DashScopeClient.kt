package com.nihildigit.agentcore.cloud

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.common.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DashScopeClient（轻量封装）。
 *
 * 云端小抄为纯文本场景：使用 DashScope Generation（而非 multimodalconversation），
 * 避免因模型/入参不匹配导致的 400 InvalidParameter（url error）。
 */
class DashScopeClient(
    private val apiKey: String,
    model: String = "qwen3-max"
) {
    private val modelName = model

    suspend fun chat(systemPrompt: String, userPrompt: String): String {
        return withContext(Dispatchers.IO) {
            val gen = Generation()
            val messages = listOf(
                Message.builder().role("system").content(systemPrompt).build(),
                Message.builder().role("user").content(userPrompt).build()
            )

            val param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(modelName)
                .messages(messages)
                .resultFormat("message")
                .temperature(0.6f)
                .topP(0.85)
                .maxTokens(900)
                .enableSearch(true)  // 启用网络搜索
                .build()

            val result = gen.call(param)
            result.output?.choices?.firstOrNull()?.message?.content
                ?: result.output?.text
                ?: ""
        }
    }
}
