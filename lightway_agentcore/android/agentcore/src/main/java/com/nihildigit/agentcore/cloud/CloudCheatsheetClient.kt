package com.nihildigit.agentcore.cloud

import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.mcp.ToolProvider
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * ToolClient: get_cloud_cheatsheet
 *
 * INTERMEDIATE 类型：本地 Agent 需要根据结构化小抄复述。
 */
@Deprecated("Dead code: never registered in Hub. Cheatsheet feature was not integrated.")
class CloudCheatsheetClient(
    apiKey: String,
    private val enabled: Boolean = true
) : ToolClient {

    private val service = CloudCheatsheetService(DashScopeClient(apiKey))

    override fun listTools(): List<Tool> = if (!enabled) emptyList() else listOf(
        Tool(
            function = ToolFunction(
                name = "get_cloud_cheatsheet",
                description = "当问题需要搜索/对比/深度分析时调用云端生成结构化小抄，本地再复述给用户",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("question", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("需要云端搜索/分析的问题"))
                        })
                        put("type", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("可选：search/compare/analyze"))
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("question")) })
                }
            ),
            toolType = ToolType.INTERMEDIATE
        )
    )

    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): Result<String> {
        if (!enabled) return Result.failure(IllegalStateException("cloud cheatsheet disabled"))
        if (name != "get_cloud_cheatsheet") {
            return Result.failure(IllegalArgumentException("未知工具: $name"))
        }

        val question = arguments["question"]?.toString()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("question 不能为空"))
        val type = arguments["type"]?.toString()

        return runCatching {
            service.getCheatsheet(question, type)
        }
    }

    override fun getProvider(): ToolProvider = ToolProvider.BUILT_IN
    override fun getServiceId(): String = "built-in"
    override fun getServiceName(): String = "内置工具"
}
