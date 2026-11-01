package com.nihildigit.agentcore.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compact MCP tool descriptions and schemas to reduce prompt tokens.
 */
object SchemaCompactor {
    private const val TOKEN_CHAR_RATIO = 4
    private const val MAX_TOOL_DESCRIPTION_TOKENS = 20
    private const val MAX_PARAM_DESCRIPTION_TOKENS = 10

    data class CompactResult(
        val description: String,
        val schema: JsonElement?
    )

    fun compactTool(description: String, schema: JsonElement?): CompactResult {
        val compactDescription = compactText(description, MAX_TOOL_DESCRIPTION_TOKENS)
        val compactSchema = schema?.let { compactSchema(it) }
        return CompactResult(description = compactDescription, schema = compactSchema)
    }

    private fun compactSchema(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> compactObject(element)
            is JsonArray -> JsonArray(element.map { compactSchema(it) })
            else -> element
        }
    }

    private fun compactObject(obj: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(obj.size)
        for ((key, value) in obj) {
            when (key) {
                "title", "examples", "default" -> {
                    // Drop verbose, non-essential fields.
                }
                "description" -> {
                    val text = (value as? JsonPrimitive)?.content.orEmpty()
                    val compact = compactText(text, MAX_PARAM_DESCRIPTION_TOKENS)
                    if (compact.isNotBlank()) {
                        result[key] = JsonPrimitive(compact)
                    }
                }
                else -> result[key] = compactSchema(value)
            }
        }
        return JsonObject(result)
    }

    private fun compactText(text: String, maxTokens: Int): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ""
        val maxChars = maxTokens * TOKEN_CHAR_RATIO
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars).trimEnd()
    }
}
