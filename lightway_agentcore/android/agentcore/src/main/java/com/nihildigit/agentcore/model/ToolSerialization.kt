package com.nihildigit.agentcore.model

import kotlinx.serialization.json.*

/**
 * Tool serialization utilities for Jinja template integration.
 *
 * Converts Tool list to JSON format expected by Qwen3 chat template's tools parameter.
 * Format: [{"type":"function","function":{"name":"...","description":"...","parameters":{...}}}]
 */
object ToolSerialization {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Convert tools list to JSON string for Jinja template.
     *
     * Output format matches OpenAI function calling schema:
     * ```json
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "tool_name",
     *       "description": "Tool description",
     *       "parameters": { ... }
     *     }
     *   }
     * ]
     * ```
     *
     * @param tools List of tools to serialize
     * @return JSON string, or null if tools list is empty
     */
    fun toTemplateToolsJson(tools: List<Tool>): String? {
        if (tools.isEmpty()) return null

        val sortedTools = tools.sortedWith(
            compareByDescending<Tool> { it.priority }.thenBy { it.function.name }
        )

        val toolsArray = buildJsonArray {
            for (tool in sortedTools) {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        tool.function.parameters?.let { params ->
                            put("parameters", params)
                        }
                    })
                })
            }
        }

        return json.encodeToString(JsonArray.serializer(), toolsArray)
    }
}
