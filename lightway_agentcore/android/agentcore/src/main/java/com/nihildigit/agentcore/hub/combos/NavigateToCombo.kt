package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import android.net.Uri
import kotlinx.serialization.json.*

/**
 * 一键导航 Combo
 * 
 * 输入：find_nearby 返回的 location_id，由 LocationStore 解析为 POI ID。
 * 流程：maps_search_detail → maps_direction_walking。
 * 输出：路线摘要和启动高德步行导航的 Action。
 */
class NavigateToCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "NavigateToCombo"
        private const val APP_NAME = "LightWay"  // 应用名称
        private const val AMAP_PACKAGE = "com.autonavi.minimap"  // 高德地图包名
    }
    
    override val name = "navigate_to"

    override val description = "步行导航到find_nearby返回的地点。不能直接调用，先调find_nearby"

    override val group = ComboGroup.TRAVEL

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 45

    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("location_id") {
                put("type", "string")
                put("description", "地点ID如L1")
            }
        }
        putJsonArray("required") {
            add("location_id")
        }
    }
    
    override val requiredServices = listOf("amap-maps")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val lid = arguments["location_id"]?.toString()
            ?: return ComboToolResult.failure("缺少location_id参数")

        if (LocationStore.isEmpty()) {
            return ComboToolResult.failure("请先搜索附近地点")
        }
        val entry = LocationStore.resolve(lid)
            ?: return ComboToolResult.failure("请选择搜索结果中的地点（如L1）")
        val resolvedPoiId = entry.poiId

        AgentLogger.normal(TAG, "Execute", "navigating to POI", "poiId" to resolvedPoiId)

        // 检查位置权限
        checkLocationPermission()?.let { return it }

        val currentLocation = getCurrentLocationString()
            ?: return ComboToolResult.failure("无法获取当前位置")

        val client = ensureServiceConnected("amap-maps")
            ?: return ComboToolResult.failure("高德地图服务未连接")

        try {
            // Step 1: 调用 maps_search_detail 获取POI详情和坐标
            AgentLogger.verbose(TAG, "Execute", "fetching POI details", "poiId" to resolvedPoiId)
            val detailResult = client.callTool(
                "maps_search_detail",
                mapOf("id" to resolvedPoiId),
                history
            )

            if (detailResult.isFailure) {
                return ComboToolResult.failure("无法获取 POI 详情: ${detailResult.exceptionOrNull()?.message}")
            }

            val detailJson = detailResult.getOrNull() ?: return ComboToolResult.failure("POI详情返回空结果")

            AgentLogger.verbose(TAG, "Execute", "POI detail response",
                "json" to detailJson.take(500))

            // Step 2: 提取坐标和名称
            val destLocation = extractLocation(detailJson)
            if (destLocation == null) {
                AgentLogger.normal(TAG, "Execute", "无法从POI详情解析坐标",
                    "poiId" to resolvedPoiId,
                    "detailJsonPreview" to detailJson.take(200))
                return ComboToolResult.failure("无法从 POI 详情解析坐标，POI可能不包含位置信息")
            }

            val destination = extractPoiName(detailJson) ?: resolvedPoiId
            
            // Step 3: 路径规划 (使用步行导航工具)
            AgentLogger.verbose(TAG, "Execute", "planning route",
                "from" to currentLocation,
                "to" to destLocation
            )
            
            // 注意：对于视障人士，默认使用步行路径规划
            val routeResult = client.callTool(
                "maps_direction_walking",
                mapOf("origin" to currentLocation, "destination" to destLocation),
                history
            )
            
            if (routeResult.isFailure) {
                return ComboToolResult.failure("路径规划失败: ${routeResult.exceptionOrNull()?.message}")
            }
            
            val routeJson = routeResult.getOrNull() ?: return ComboToolResult.failure("路径规划返回空结果")
            
            // Step 4: 精简输出并生成高德导航 Action
            val naviAction = buildAmapNaviAction(destLocation, destination)
            val distilled = distillRouteResult(routeJson, destination)
            
            AgentLogger.normal(TAG, "Execute", "navigation complete",
                "destination" to destination,
                "result" to distilled.take(100)
            )
            
            // 返回结果并附带启动高德导航的 Action
            return ComboToolResult.success(
                content = distilled,
                rawResponse = routeJson,
                action = naviAction
            )
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "navigation failed", e)
            return ComboToolResult.failure(e.message ?: "导航失败")
        }
    }
    
    private fun extractCity(json: String): String? {
        return try {
            val element = Json.parseToJsonElement(json)
            val addressComp = element.jsonObject["regeocode"]?.jsonObject?.get("addressComponent")?.jsonObject
            addressComp?.get("city")?.jsonPrimitive?.contentOrNull?.takeIf { it != "[]" }
                ?: addressComp?.get("province")?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }
    
    private fun extractLocation(geoJson: String): String? {
        val trimmed = geoJson.trim()
        if (!looksLikeJson(trimmed)) {
            AgentLogger.normal(TAG, "ExtractLocation", "non-json detail response",
                "preview" to trimmed.take(200))
            return null
        }
        return try {
            val json = Json.parseToJsonElement(trimmed)

            // 格式1: 直接的JSON对象 {"location": "116.397428,39.909230", ...}
            val directLocation = json.jsonObject["location"]?.jsonPrimitive?.content
            if (directLocation != null) {
                AgentLogger.verbose(TAG, "ExtractLocation", "parsed location from direct JSON",
                    "result" to directLocation)
                return directLocation
            }

            // 格式2: MCP包装格式 {"root": {"text": "{...}"}}
            val rootText = json.jsonObject["root"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            if (rootText != null) {
                // 二次解析内层JSON
                val innerJson = Json.parseToJsonElement(rootText).jsonObject
                val location = innerJson["location"]?.jsonPrimitive?.content
                if (location != null) {
                    AgentLogger.verbose(TAG, "ExtractLocation", "parsed location from MCP wrapper",
                        "result" to location)
                    return location
                }
            }

            // 格式3: MCP返回数组格式 {"return": [{"location": "..."}]}
            val items = json.jsonObject["return"]?.jsonArray
                ?: json.jsonObject["geocodes"]?.jsonArray

            val firstItem = items?.firstOrNull()
            val location = firstItem?.jsonObject?.let { obj ->
                when (val locElem = obj["location"]) {
                    is JsonPrimitive -> locElem.content
                    is JsonObject -> {
                        val lng = locElem["lng"]?.jsonPrimitive?.contentOrNull
                        val lat = locElem["lat"]?.jsonPrimitive?.contentOrNull
                        if (lng != null && lat != null) "$lng,$lat" else null
                    }
                    else -> null
                }
            }

            AgentLogger.verbose(TAG, "ExtractLocation", "parsed location",
                "result" to (location ?: "null")
            )

            location
        } catch (e: Exception) {
            AgentLogger.error(TAG, "ExtractLocation", "failed to parse geo response", e)
            null
        }
    }
    
    private fun distillRouteResult(routeJson: String, destination: String): String {
        return try {
            val json = Json.parseToJsonElement(routeJson)
            val route = json.jsonObject["route"]?.jsonObject
            val path = route?.get("paths")?.jsonArray?.firstOrNull()?.jsonObject

            if (path == null) {
                return "正在为您导航到$destination"
            }

            val distance = path["distance"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val duration = path["duration"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

            // 提取途经道路
            val roads = path["steps"]?.jsonArray
                ?.mapNotNull { step ->
                    val roadElem = step.jsonObject["road"]
                    when (roadElem) {
                        is JsonPrimitive -> roadElem.contentOrNull
                        is JsonArray -> (roadElem.firstOrNull() as? JsonPrimitive)?.contentOrNull
                        is JsonObject -> (roadElem["name"] as? JsonPrimitive)?.contentOrNull
                        else -> null
                    }
                }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(3)
                ?: emptyList()

            buildNavigateText(destination, distance, duration, roads)
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillRoute", "failed to parse route", e)
            "正在为您导航到$destination"
        }
    }

    private fun buildNavigateText(
        destination: String,
        distanceMeters: Int,
        durationSeconds: Int,
        roads: List<String>
    ): String = "正在为您导航到$destination"
    
    /**
     * 构建高德地图步行导航 Action
     * 
     * 使用高德官方 Android 路径规划 URI scheme：
     * amapuri://route/plan?sid=起点名称&slat=起点纬度&slon=起点经度&did=终点名称&dlat=终点纬度&dlon=终点经度&dev=0&t=2
     * 
     * 参数说明：
     * - sid: 起点名称（不填则使用当前位置）
     * - slat/slon: 起点纬度/经度（不填则使用当前位置）
     * - did: 终点名称
     * - dlat/dlon: 终点纬度/经度
     * - dev: 坐标系，0=GCJ-02（高德/腾讯系，不偏移），1=WGS84（GPS原始坐标，需偏移）
     * - t: 路线类型，0=驾车，1=公交，2=步行，3=骑行
     * 
     * 对于视障用户，默认使用步行导航 (t=2)
     * 
     * @param destLocation 终点坐标 (lng,lat) - 高德格式
     * @param destName 目的地名称
     * @return ComboAction.LaunchUrl 包含主 URL 和备用 URL
     */
    private fun buildAmapNaviAction(destLocation: String, destName: String): ComboAction.LaunchUrl {
        // 解析坐标：高德格式是 "lng,lat"，需要拆分
        val coords = destLocation.split(",")
        val lon = coords.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val lat = coords.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        
        // 构建高德 App 步行导航 URI - 直接启动导航，无需手动点击
        // 使用 openFeature 直接启动步行导航
        val amapUri = StringBuilder()
            .append("amapuri://openFeature?featureName=OnFootNavi")
            .append("&sourceApplication=").append(Uri.encode(APP_NAME))
            .append("&lat=").append(lat)
            .append("&lon=").append(lon)
            .append("&dev=0")  // 0: 已是 GCJ-02 坐标，不需要偏移
            .append("&style=2")  // 导航风格
        
        // 备用 URL：高德网页版步行导航（未安装高德 App 时使用）
        val fallbackUrl = "https://uri.amap.com/navigation?" +
            "to=$lon,$lat,${Uri.encode(destName)}" +
            "&mode=walk" +  // 步行模式
            "&src=$APP_NAME" +
            "&coordinate=gaode"
        
        AgentLogger.verbose(TAG, "BuildNaviAction", "generated URIs",
            "amapUri" to amapUri.toString(),
            "fallbackUrl" to fallbackUrl
        )
        
        return ComboAction.LaunchUrl(
            url = amapUri.toString(),
            fallbackUrl = fallbackUrl,
            packageName = AMAP_PACKAGE  // 指定包名确保唤起正确的 App
        )
    }

    /**
     * 从POI详情JSON中提取POI名称
     */
    private fun extractPoiName(detailJson: String): String? {
        val trimmed = detailJson.trim()
        if (!looksLikeJson(trimmed)) {
            return null
        }
        return try {
            val json = Json.parseToJsonElement(trimmed)

            // 格式1: 直接的JSON对象 {"name": "...", ...}
            val directName = json.jsonObject["name"]?.jsonPrimitive?.content
            if (directName != null) {
                return directName
            }

            // 格式2: MCP包装格式 {"root": {"text": "{...}"}}
            val rootText = json.jsonObject["root"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            if (rootText != null) {
                val innerJson = Json.parseToJsonElement(rootText).jsonObject
                return innerJson["name"]?.jsonPrimitive?.content
            }

            // 格式3: 数组格式 {"pois": [{...}]}
            val pois = json.jsonObject["pois"]?.jsonArray
            pois?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content
        } catch (e: Exception) {
            AgentLogger.error(TAG, "ExtractPoiName", "failed to parse POI name", e)
            null
        }
    }

    private fun looksLikeJson(text: String): Boolean {
        if (text.isBlank()) return false
        val first = text.first()
        return first == '{' || first == '['
    }

}
