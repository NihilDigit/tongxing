package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 停止导航 Combo
 *
 * 输入：无
 * 输出：已停止导航
 */
class StopNavigationCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {

    override val name = "stop_navigation"

    override val description = "停止当前导航"

    override val group = ComboGroup.TRAVEL

    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            // 无参数
        }
    }

    override val requiredServices: List<String> = emptyList()

    override val toolType: ToolType = ToolType.ACTION

    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        return ComboToolResult.success(
            content = "已停止导航。",
            action = ComboAction.StopNavigation()
        )
    }
}
