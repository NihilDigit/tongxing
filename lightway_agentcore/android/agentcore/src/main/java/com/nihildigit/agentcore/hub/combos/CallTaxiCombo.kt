package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.model.ToolType
import kotlinx.serialization.json.*

/**
 * 叫车 Combo
 *
 * 输入：poi_id (POI ID，如 B0K30STOXP), car_type? (车型)
 * 流程：
 *   1. maps_search_detail 获取 POI 详情和坐标
 *   2. taxi_estimate 获取预估信息和 trace_id
 *   3. taxi_create_order 使用 trace_id 创建订单
 * 输出：订单信息
 *
 * 注意：此为沙箱环境，不产生真实订单
 *
 * 示例：
 * - 用户："找附近咖啡馆" (find_nearby 返回 POI 列表)
 * - 用户："叫车去第一个" (从 poi_map 提取 ID，调用 call_taxi)
 */
class CallTaxiCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "CallTaxiCombo"
        
        // 车型名称到 product_category 的映射
        private val CAR_TYPE_MAP = mapOf(
            "特惠快车" to "201",
            "特惠" to "201",
            "快车" to "1",
            "专车" to "8",
            "豪华车" to "17",
            "豪华" to "17"
        )
        
        // 默认车型
        private const val DEFAULT_PRODUCT_CATEGORY = "1"  // 快车
    }
    
    override val name = "call_taxi"
    
    override val description = "打车到find_nearby返回的地点。不能直接调用，先调find_nearby"
    
    override val group = ComboGroup.TAXI

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 300
    
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
    
    override val requiredServices = listOf("amap-maps", "didi-sandbox")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val lid = arguments["location_id"]?.toString()
            ?: return ComboToolResult.failure("缺少location_id参数")

        val carTypeInput = "快车"

        if (LocationStore.isEmpty()) {
            return ComboToolResult.failure("请先搜索附近地点")
        }
        val entry = LocationStore.resolve(lid)
            ?: return ComboToolResult.failure("请选择搜索结果中的地点（如L1）")
        val resolvedPoiId = entry.poiId

        // 检查位置权限
        checkLocationPermission()?.let { return it }

        val currentLocation = getCurrentLocationString()
            ?: return ComboToolResult.failure("无法获取当前位置")
        
        val currentLoc = contextProvider.getCurrentLocation()!!
        
        val amapClient = ensureServiceConnected("amap-maps")
            ?: return ComboToolResult.failure("高德地图服务未连接")
        
        val didiClient = ensureServiceConnected("didi-sandbox")
            ?: return ComboToolResult.failure("滴滴服务未连接")
        
        try {
            // Step 1: 调用 maps_search_detail 获取POI详情和坐标
            AgentLogger.verbose(TAG, "Execute", "fetching POI details", "poiId" to resolvedPoiId)

            val detailResult = amapClient.callTool(
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

            // 拆分坐标：destLocation 格式是 "lng,lat"
            val coords = destLocation.split(",")
            val destLng = coords.getOrNull(0) ?: return ComboToolResult.failure("坐标格式错误")
            val destLat = coords.getOrNull(1) ?: return ComboToolResult.failure("坐标格式错误")

            // Step 3: 调用 taxi_estimate 获取 trace_id
            AgentLogger.verbose(TAG, "Execute", "getting estimate for trace_id",
                "from" to currentLocation,
                "to" to destination
            )

            val estimateResult = didiClient.callTool(
                "taxi_estimate",
                mapOf(
                    "from_lng" to currentLoc.longitude.toString(),
                    "from_lat" to currentLoc.latitude.toString(),
                    "from_name" to "当前位置",
                    "to_lng" to destLng,
                    "to_lat" to destLat,
                    "to_name" to destination
                ),
                history
            )
            
            if (estimateResult.isFailure) {
                return ComboToolResult.failure("获取打车信息失败: ${estimateResult.exceptionOrNull()?.message}")
            }
            
            val estimateJson = estimateResult.getOrNull() 
                ?: return ComboToolResult.failure("获取打车信息返回空结果")
            
            // DEBUG: 打印原始响应
            AgentLogger.normal(TAG, "Execute", "taxi_estimate raw response",
                "length" to estimateJson.length.toString(),
                "content" to estimateJson.take(500)
            )
            
            // 解析 estimate 结果获取 trace_id 和可用车型
            val estimateInfo = parseEstimateResult(estimateJson)
            
            // DEBUG: 打印解析结果
            AgentLogger.normal(TAG, "Execute", "parsed estimate info",
                "traceId" to (estimateInfo.traceId ?: "NULL"),
                "productsCount" to estimateInfo.products.size.toString()
            )
            
            if (estimateInfo.traceId == null) {
                return ComboToolResult.failure("无法获取预估信息，请稍后重试")
            }
            
            // 确定要叫的车型
            val productCategory = resolveProductCategory(carTypeInput, estimateInfo.products)
            val carTypeName = estimateInfo.products.find { it.category == productCategory }?.name ?: carTypeInput
            
            // Step 4: 创建订单
            AgentLogger.verbose(TAG, "Execute", "creating taxi order",
                "traceId" to estimateInfo.traceId,
                "productCategory" to productCategory
            )

            val orderResult = didiClient.callTool(
                "taxi_create_order",
                mapOf(
                    "estimate_trace_id" to estimateInfo.traceId,
                    "product_category" to productCategory
                ),
                history
            )

            if (orderResult.isFailure) {
                return ComboToolResult.failure("叫车失败: ${orderResult.exceptionOrNull()?.message}")
            }

            val orderJson = orderResult.getOrNull() ?: return ComboToolResult.failure("叫车返回空结果")
            val (distilled, orderId) = distillOrderResult(orderJson, destination, carTypeName)
            com.nihildigit.agentcore.hub.TaxiOrderStore.setLastOrderId(orderId)
            val tts = buildString {
                append("已叫车")
                append("，目的地")
                append(destination)
                if (carTypeName.isNotBlank()) {
                    append("，车型")
                    append(carTypeName)
                }
                append("。正在为您持续追踪车辆状态。")
            }

            AgentLogger.normal(TAG, "Execute", "taxi called",
                "poiId" to resolvedPoiId,
                "destination" to destination,
                "carType" to carTypeName,
                "result" to tts.take(200)
            )
            return ComboToolResult.success(tts, orderJson)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "call taxi failed", e)
            return ComboToolResult.failure(e.message ?: "叫车失败")
        }
    }
    
    private data class ProductInfo(val name: String, val category: String)
    
    private data class EstimateInfo(
        val traceId: String?,
        val products: List<ProductInfo>
    )
    
    private fun parseEstimateResult(estimateText: String): EstimateInfo {
        // MCPClientWrapper 返回的是纯文本，不是 JSON
        // DiDi 返回格式: "...预估流程ID: xxx\n..."
        
        // 提取 traceId
        val traceIdMatch = Regex("""预估流程ID[：:]\s*([a-f0-9]+)""").find(estimateText)
        val traceId = traceIdMatch?.groupValues?.get(1)
        
        // 提取车型列表: "1. 特惠快车 (品类代码: 201)" 或 "1. 特惠快车 (product_category: 201)"
        val products = mutableListOf<ProductInfo>()
        val productPattern = Regex("""(\d+)\.\s*(.+?)\s*\((?:品类代码|product_category)[：:]\s*(\d+)\)""")
        productPattern.findAll(estimateText).forEach { match ->
            val name = match.groupValues[2].trim()
            val category = match.groupValues[3]
            products.add(ProductInfo(name, category))
        }
        
        AgentLogger.verbose(TAG, "ParseEstimate", "parsed from text",
            "traceId" to (traceId ?: "null"),
            "products" to products.size.toString()
        )
        
        return EstimateInfo(traceId, products)
    }
    
    private fun resolveProductCategory(userInput: String, availableProducts: List<ProductInfo>): String {
        // 1. 先用静态映射
        val mappedCategory = CAR_TYPE_MAP[userInput]
        if (mappedCategory != null) {
            // 检查是否在可用车型中
            if (availableProducts.any { it.category == mappedCategory }) {
                return mappedCategory
            }
        }
        
        // 2. 模糊匹配用户输入
        for (product in availableProducts) {
            if (product.name.contains(userInput) || userInput.contains(product.name)) {
                return product.category
            }
        }
        
        // 3. 默认返回快车，或第一个可用车型
        return availableProducts.find { it.category == DEFAULT_PRODUCT_CATEGORY }?.category
            ?: availableProducts.firstOrNull()?.category
            ?: DEFAULT_PRODUCT_CATEGORY
    }
    
    /**
     * 从POI详情JSON中提取坐标（"lng,lat"格式）
     */
    private fun extractLocation(detailJson: String): String? {
        val trimmed = detailJson.trim()
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
            AgentLogger.error(TAG, "ExtractLocation", "failed to parse detail response", e)
            null
        }
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
    
    /**
     * 解析订单创建结果
     * 
     * DiDi taxi_create_order 返回格式:
     * {
     *   "structuredContent": {
     *     "orderId": "xxx",
     *     "status": "waiting",
     *     ...
     *   },
     *   "content": [{"type": "text", "text": "..."}]
     * }
     */
    private fun distillOrderResult(orderJson: String, destName: String, carType: String): Pair<String, String?> {
        val trimmed = orderJson.trim()
        if (!looksLikeJson(trimmed)) {
            return buildOrderResultFromText(trimmed, destName, carType, parseFailed = false)
        }
        return try {
            val json = Json.parseToJsonElement(trimmed)
            
            // 尝试从 structuredContent 解析
            val structured = json.jsonObject["structuredContent"]?.jsonObject
            val orderId = structured?.get("orderId")?.jsonPrimitive?.contentOrNull
                ?: structured?.get("order_id")?.jsonPrimitive?.contentOrNull
            val status = structured?.get("status")?.jsonPrimitive?.contentOrNull
            
            // Full ReAct: return facts only; user-facing output is generated later.
            buildJsonObject {
                put("event", "taxi_order_created")
                put("order_id", orderId ?: "")
                put("destination", destName)
                put("car_type", carType)
                put(
                    "status",
                    when (status?.lowercase()) {
                        "pending", "waiting" -> "waiting_driver_accept"
                        "accepted" -> "accepted"
                        "arriving" -> "arriving"
                        else -> status ?: ""
                    }
                )
                put("sandbox", true)
            }.toString() to orderId
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillOrder", "failed to parse order as JSON", e)
            buildOrderResultFromText(trimmed, destName, carType, parseFailed = true)
        }
    }

    private fun looksLikeJson(text: String): Boolean {
        if (text.isBlank()) return false
        val first = text.first()
        return first == '{' || first == '['
    }

    private fun buildOrderResultFromText(
        text: String,
        destName: String,
        carType: String,
        parseFailed: Boolean
    ): Pair<String, String?> {
        val orderId = extractOrderIdFromText(text)
        val statusRaw = extractOrderStatusFromText(text)
        val normalizedStatus = when (statusRaw?.lowercase()) {
            "pending", "waiting", "created" -> "waiting_driver_accept"
            "accepted" -> "accepted"
            "arriving" -> "arriving"
            else -> "submitted"
        }
        return buildJsonObject {
            put("event", "taxi_order_created")
            put("order_id", orderId ?: "")
            put("destination", destName)
            put("car_type", carType)
            put("status", normalizedStatus)
            put("sandbox", true)
            if (parseFailed) {
                put("error", if (orderId == null) "missing_order_id" else "parse_failed")
            }
        }.toString() to orderId
    }

    private fun extractOrderIdFromText(text: String): String? {
        val candidates = listOf(
            Regex("""orderId["']?\s*[:=]\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""order_id["']?\s*[:=]\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""订单ID[：:]\s*([A-Za-z0-9_-]+)"""),
            Regex("""订单号[：:]\s*([A-Za-z0-9_-]+)""")
        )
        for (re in candidates) {
            val m = re.find(text) ?: continue
            val id = m.groupValues.getOrNull(1)?.trim()
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun extractOrderStatusFromText(text: String): String? {
        val candidates = listOf(
            Regex("""status["']?\s*[:=]\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE),
            Regex("""状态[：:]\s*([A-Za-z0-9_-]+)""")
        )
        for (re in candidates) {
            val m = re.find(text) ?: continue
            val status = m.groupValues.getOrNull(1)?.trim()
            if (!status.isNullOrBlank()) return status
        }
        return null
    }
}
