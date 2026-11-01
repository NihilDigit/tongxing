package com.nihildigit.agentcore.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 云端小抄服务：选择模板、调用 DashScope、返回结构化 JSON 字符串。
 */
@Deprecated("Dead code: only referenced by other dead Cheatsheet classes. Feature was not integrated.")
class CloudCheatsheetService(
    private val dashScopeClient: DashScopeClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun getCheatsheet(question: String, type: String?): String {
        val template = when (type?.lowercase()) {
            "compare" -> CheatsheetTemplates.COMPARE_OPTIONS
            "analyze", "analysis", "deep" -> CheatsheetTemplates.DEEP_ANALYSIS
            else -> CheatsheetTemplates.SEARCH_SUMMARIZE
        }

        val prompt = template.replace("{{question}}", question)
        val raw = dashScopeClient.chat(
            systemPrompt = "你是云端小抄生成器，只输出 JSON，不要输出额外解释。",
            userPrompt = prompt
        )

        // 尝试解析成 JSON 以确保返回结构化；失败则原样返回。
        return runCatching {
            val parsed: JsonElement = json.parseToJsonElement(raw.trim())
            json.encodeToString(JsonElement.serializer(), parsed)
        }.getOrElse { raw.trim() }
    }
}

