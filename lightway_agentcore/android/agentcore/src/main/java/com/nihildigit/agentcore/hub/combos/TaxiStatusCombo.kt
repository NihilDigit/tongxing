package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 查询打车状态 Combo
 * 
 * 输入：无（使用最近订单）
 * 流程：taxi_query_order(last_order_id)
 * 输出：订单状态、司机信息、预计到达时间
 * 
 * 示例：
 * - "我的打车订单怎么样了" → "司机张师傅正在赶来，预计3分钟到达"
 */
class TaxiStatusCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "TaxiStatusCombo"
    }
    
    override val name = "taxi_status"
    
    override val description = "查询最新打车订单状态，车到哪了、还有多久到"
    
    override val group = ComboGroup.TAXI

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 60
    
    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
        }
    }
    
    override val requiredServices = listOf("didi-sandbox")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val orderId = com.nihildigit.agentcore.hub.TaxiOrderStore.getLastOrderId().orEmpty()
        if (orderId.isBlank()) {
            return ComboToolResult.failure("当前没有可查询的订单，请先叫车")
        }
        
        val client = ensureServiceConnected("didi-sandbox")
            ?: return ComboToolResult.failure("滴滴服务未连接")
        
        try {
            AgentLogger.verbose(TAG, "Execute", "querying order", "orderId" to orderId)
            
            val result = client.callTool(
                "taxi_query_order",
                mapOf("order_id" to orderId),
                history
            )
            
            if (result.isFailure) {
                return ComboToolResult.failure("查询订单失败: ${result.exceptionOrNull()?.message}")
            }
            
            val rawJson = result.getOrNull() ?: return ComboToolResult.failure("订单查询返回空结果")
            AgentLogger.normal(TAG, "Execute", "taxi_query_order raw response",
                "length" to rawJson.length.toString(),
                "content" to rawJson.take(500)
            )
            val statusInfo = com.nihildigit.agentcore.hub.TaxiStatusParser.parse(rawJson)
            val tts = statusInfo?.let { com.nihildigit.agentcore.hub.TaxiStatusParser.formatTts(it) }
                ?: "订单状态更新中"
            
            AgentLogger.normal(TAG, "Execute", "order status fetched",
                "orderId" to orderId,
                "result" to tts.take(100)
            )
            
            if (statusInfo?.status == "completed" || statusInfo?.status == "cancelled") {
                com.nihildigit.agentcore.hub.TaxiOrderStore.clear()
            }

            return ComboToolResult.success(tts, rawJson)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "query order failed", e)
            return ComboToolResult.failure(e.message ?: "查询订单失败")
        }
    }
}
