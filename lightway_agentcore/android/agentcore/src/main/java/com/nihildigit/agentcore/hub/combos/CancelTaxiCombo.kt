package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.*

/**
 * 取消打车 Combo
 * 
 * 输入：无（使用最近订单）
 * 流程：taxi_cancel_order(last_order_id)
 * 输出：取消结果
 * 
 * 示例：
 * - "取消我的打车订单" → "订单已取消"
 */
class CancelTaxiCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "CancelTaxiCombo"
    }
    
    override val name = "cancel_taxi"
    
    override val description = "取消最新打车订单"
    
    override val group = ComboGroup.TAXI

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 30
    
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
            return ComboToolResult.failure("当前没有可取消的订单，请先叫车")
        }
        
        val client = ensureServiceConnected("didi-sandbox")
            ?: return ComboToolResult.failure("滴滴服务未连接")
        
        try {
            AgentLogger.verbose(TAG, "Execute", "cancelling order", "orderId" to orderId)
            
            val result = client.callTool(
                "taxi_cancel_order",
                mapOf("order_id" to orderId),
                history
            )
            
            if (result.isFailure) {
                return ComboToolResult.failure("取消订单失败: ${result.exceptionOrNull()?.message}")
            }
            
            val rawJson = result.getOrNull() ?: return ComboToolResult.failure("取消订单返回空结果")
            val distilled = distillCancelResult(rawJson)
            val isCancelled = distilled.contains("\"success\":true") || distilled.contains("\"status\":\"cancelled\"")
            if (isCancelled) com.nihildigit.agentcore.hub.TaxiOrderStore.clear()
            
            AgentLogger.normal(TAG, "Execute", "order cancelled",
                "orderId" to orderId,
                "result" to distilled.take(100)
            )
            return ComboToolResult.success(
                if (isCancelled) "订单已取消" else "已提交取消请求，请稍后确认",
                rawJson
            )
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "cancel order failed", e)
            return ComboToolResult.failure(e.message ?: "取消订单失败")
        }
    }
    
    /**
     * 解析取消订单结果
     * 
     * DiDi taxi_cancel_order 返回格式:
     * {
     *   "content": [{"type": "text", "text": "订单取消成功。..."}],
     *   "structuredContent": {
     *     "success": true
     *   }
     * }
     */
    private fun distillCancelResult(rawJson: String): String {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{")) {
            return distillCancelText(trimmed)
        }
        return try {
            val json = Json.parseToJsonElement(rawJson)
            
            // 从 structuredContent 检查成功标志
            val structured = json.jsonObject["structuredContent"]?.jsonObject
            val success = structured?.get("success")?.jsonPrimitive?.booleanOrNull ?: false
            
            if (success) {
                return buildJsonObject {
                    put("event", "taxi_cancel")
                    put("success", true)
                    put("status", "cancelled")
                    put("sandbox", true)
                }.toString()
            }
            
            // 降级：从 content[0].text 提取
            val contentArray = json.jsonObject["content"]?.jsonArray
            val textContent = contentArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            
            if (!textContent.isNullOrBlank()) {
                // 检查文本内容判断是否成功
                if (textContent.contains("成功") || textContent.contains("已取消")) {
                    return buildJsonObject {
                        put("event", "taxi_cancel")
                        put("success", true)
                        put("status", "cancelled")
                        put("sandbox", true)
                    }.toString()
                }
                // 返回简化的错误信息
                val firstLine = textContent.lines().firstOrNull()?.trim() ?: "取消失败"
                return buildJsonObject {
                    put("event", "taxi_cancel")
                    put("success", false)
                    put("status", "unknown")
                    put("sandbox", true)
                    put("error", firstLine.take(80))
                }.toString()
            }
            
            buildJsonObject {
                put("event", "taxi_cancel")
                put("success", true)
                put("status", "submitted")
                put("sandbox", true)
            }.toString()
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillCancel", "failed to parse cancel result", e)
            distillCancelText(trimmed)
        }
    }

    private fun distillCancelText(textContent: String): String {
        if (textContent.isBlank()) {
            return buildJsonObject {
                put("event", "taxi_cancel")
                put("success", true)
                put("status", "submitted")
                put("sandbox", true)
            }.toString()
        }
        val isSuccess = textContent.contains("成功") || textContent.contains("已取消")
        return buildJsonObject {
            put("event", "taxi_cancel")
            put("success", isSuccess)
            put("status", if (isSuccess) "cancelled" else "unknown")
            put("sandbox", true)
            if (!isSuccess) {
                val firstLine = textContent.lines().firstOrNull()?.trim().orEmpty()
                if (firstLine.isNotBlank()) {
                    put("error", firstLine.take(80))
                }
            }
        }.toString()
    }
}
