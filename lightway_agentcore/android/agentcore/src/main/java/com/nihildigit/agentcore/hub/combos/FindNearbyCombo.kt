package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.serialization.json.*

/**
 * 周边搜索 Combo
 *
 * 输入：keyword (关键词)
 * 流程：maps_around_search(keyword, current_location, radius=10km)
 * 输出：Top 3 POI 名称和 POI ID 映射
 */
class FindNearbyCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "FindNearbyCombo"
    }
    
    override val name = "find_nearby"

    override val description = "搜索附近地点，返回location_id列表"

    override val group = ComboGroup.LIFE

    override val toolType: ToolType = ToolType.INTERMEDIATE

    override val renewalSeconds: Int = 45
    
    
    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("keyword") {
                put("type", "string")
                put("description", "关键词")
            }
        }
        putJsonArray("required") {
            add("keyword")
        }
    }
    
    override val requiredServices = listOf("amap-maps")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val keyword = arguments["keyword"]?.toString()
            ?: return ComboToolResult.failure("缺少搜索关键词")
        
        val radius = config.defaultSearchRadius
        
        // 检查位置权限
        checkLocationPermission()?.let {
            AgentLogger.normal(TAG, "Execute", "location permission missing")
            return it
        }
        
        val currentLocation = getCurrentLocationString()
            ?: return ComboToolResult.failure("无法获取当前位置")
        
        val client = ensureServiceConnected("amap-maps")
            ?: return ComboToolResult.failure("高德地图服务未连接")
        
        try {
            AgentLogger.verbose(TAG, "Execute", "searching nearby",
                "keyword" to keyword,
                "location" to currentLocation,
                "radius" to radius
            )
            
            val result = client.callTool(
                "maps_around_search",
                mapOf(
                    "keywords" to keyword,
                    "location" to currentLocation,
                    "radius" to radius.toString()
                ),
                history
            )
            
            if (result.isFailure) {
                return ComboToolResult.failure("搜索失败: ${result.exceptionOrNull()?.message}")
            }
            
            val rawJson = result.getOrNull() ?: return ComboToolResult.failure("搜索返回空结果")
            AgentLogger.normal(TAG, "Execute", "raw search results", "json" to rawJson)
            val (llmText, ttsText) = distillSearchResult(rawJson, keyword)

            AgentLogger.normal(TAG, "Execute", "search complete",
                "keyword" to keyword,
                "result" to llmText.take(100)
            )

            return ComboToolResult.success(llmText, rawJson, announceText = ttsText)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "search failed", e)
            return ComboToolResult.failure(e.message ?: "搜索失败")
        }
    }
    
    private fun distillSearchResult(rawJson: String, keyword: String): Pair<String, String?> {
        return try {
            val jsonRoot = Json.parseToJsonElement(rawJson)
            val pois = jsonRoot.jsonObject["pois"]?.jsonArray

            if (pois.isNullOrEmpty()) {
                return "附近没有找到$keyword" to null
            }

            val entries = mutableListOf<LocationStore.PoiEntry>()
            val llmLines = mutableListOf<String>()
            val ttsLines = mutableListOf<String>()

            for (poi in pois.take(config.maxPOIResults)) {
                val obj = poi.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: "未知"
                val poiId = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: continue
                val distance = obj["distance"]?.jsonPrimitive?.content?.toIntOrNull()

                entries.add(LocationStore.PoiEntry(poiId, name, distance))

                val lid = "L${entries.size}"
                val distText = if (distance != null && distance > 0) {
                    " ${OutputDistiller.formatDistance(distance)}"
                } else ""
                llmLines.add("$lid $name$distText")
                ttsLines.add("${entries.size}. $name$distText")
            }

            if (entries.isEmpty()) {
                return "附近没有找到$keyword" to null
            }

            // 存入 LocationStore 供 navigate_to / call_taxi 使用
            LocationStore.update(entries)

            val llmText = llmLines.joinToString("\n")
            val ttsText = "找到${entries.size}个$keyword：${ttsLines.joinToString("，")}。"
            llmText to ttsText
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillSearch", "failed to parse search result", e)
            "找到了一些$keyword" to null
        }
    }
}
