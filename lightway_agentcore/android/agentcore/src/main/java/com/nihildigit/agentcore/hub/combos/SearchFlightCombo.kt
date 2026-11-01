package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.serialization.json.*

/**
 * 航班查询 Combo
 * 
 * 输入：from (出发城市), to (到达城市), date (出发日期，可选)
 * 依赖服务：Variflight
 * 流程：searchFlightsByDepArr
 * 输出：航班号、出发/到达时间、准点率（TTS友好格式）
 * 
 * 日期支持语义化输入：今天、明天、后天、周一~周日、下周X、X月X日
 */
class SearchFlightCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "SearchFlightCombo"
    }
    
    override val name = "search_flight"
    
    override val description = "查询航班"
    
    override val group = ComboGroup.TRAVEL

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 60
    
    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("from") {
                put("type", "string")
                put("description", "出发城市")
            }
            putJsonObject("to") {
                put("type", "string")
                put("description", "到达城市")
            }
            putJsonObject("date") {
                put("type", "string")
                put("description", "出发日期")
            }
        }
        putJsonArray("required") {
            add("from")
            add("to")
        }
    }
    
    override val requiredServices = listOf("variflight-mcp")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val from = arguments["from"]?.toString()
            ?: return ComboToolResult.failure("请提供出发城市")
        val to = arguments["to"]?.toString()
            ?: return ComboToolResult.failure("请提供到达城市")
        
        // 城市代码转换
        val fromCode = CityCodeMappings.getIataCode(from)
            ?: return ComboToolResult.failure("不支持的出发城市 \"$from\"，请使用如北京、上海等城市名")
        val toCode = CityCodeMappings.getIataCode(to)
            ?: return ComboToolResult.failure("不支持的到达城市 \"$to\"，请使用如北京、上海等城市名")
        
        // 使用语义日期解析器，支持 "今天"、"明天"、"周一"、"12月25日" 等
        val rawDate = arguments["date"]?.toString()
        val date = OutputDistiller.parseSemanticDate(rawDate)
            ?: return ComboToolResult.failure("无法理解日期 \"$rawDate\"，请使用：今天、明天、后天、周一~周日、下周X、X月X日")
        
        // 日期友好显示（用于输出）
        val dateFriendly = OutputDistiller.formatDateFriendly(date)
        
        AgentLogger.verbose(TAG, "Execute", "parsed date",
            "raw" to (rawDate ?: "null"),
            "parsed" to date,
            "friendly" to dateFriendly
        )
        
        val client = ensureServiceConnected("variflight-mcp")
            ?: return ComboToolResult.failure("飞常准服务未连接")
        
        try {
            // 搜索航班
            AgentLogger.verbose(TAG, "Execute", "searching flights",
                "from" to "$from($fromCode)",
                "to" to "$to($toCode)",
                "date" to date
            )
            
            val flightResult = client.callTool(
                "searchFlightsByDepArr",
                mapOf(
                    "depcity" to fromCode,
                    "arrcity" to toCode,
                    "date" to date
                ),
                history
            )
            
            if (flightResult.isFailure) {
                val errorMessage = flightResult.exceptionOrNull()?.message.orEmpty()
                mapAuthErrorMessage(errorMessage)?.let { friendly ->
                    return ComboToolResult.failure(friendly)
                }
                return ComboToolResult.failure("航班查询失败: $errorMessage")
            }
            
            val flightJson = flightResult.getOrNull() ?: return ComboToolResult.failure("查询返回空结果")
            val trimmed = flightJson.trim()
            val errorText = extractErrorFromText(trimmed)
            if (errorText != null) {
                mapAuthErrorMessage(errorText)?.let { friendly ->
                    return ComboToolResult.failure(friendly)
                }
                return ComboToolResult.failure("航班查询失败: $errorText")
            }
            if (!looksLikeJson(trimmed)) {
                return ComboToolResult.failure("航班查询失败，请稍后再试")
            }
            val distilled = distillFlightsResult(flightJson, from, to, dateFriendly)
            
            AgentLogger.normal(TAG, "Execute", "flight search complete",
                "from" to from,
                "to" to to,
                "date" to date
            )
            
            return ComboToolResult.success(distilled, flightJson)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "flight search failed", e)
            return ComboToolResult.failure(e.message ?: "航班查询失败")
        }
    }
    
    private fun distillFlightsResult(flightJson: String, from: String, to: String, dateFriendly: String): String {
        return try {
            val trimmed = flightJson.trim()
            if (!looksLikeJson(trimmed)) {
                return "${dateFriendly}${from}到${to}的航班查询完成。"
            }
            val json = Json.parseToJsonElement(trimmed)
            
            // 飞常准返回格式可能为:
            // {"flights": [...]} 或 {"data": [...]} 或直接数组
            val flights = json.jsonObject["flights"]?.jsonArray
                ?: json.jsonObject["data"]?.jsonArray
                ?: (if (json is JsonArray) json else null)
            
            if (flights == null || flights.isEmpty()) {
                return "${dateFriendly}${from}到${to}没有航班。"
            }
            
            val flightList = flights.take(config.maxFlightResults).mapNotNull { flight ->
                parseFlightInfo(flight.jsonObject)
            }
            
            if (flightList.isEmpty()) {
                return "${dateFriendly}${from}到${to}的航班信息暂不可用。"
            }
            
            // 找出最早和准点率最高的航班
            val earliest = flightList.minByOrNull { it.departTime }
            val mostPunctual = flightList.filter { it.punctualityValue > 0 }.maxByOrNull { it.punctualityValue }
            
            // 直接生成 TTS 友好的最终回复
            buildString {
                append("${dateFriendly}${from}到${to}有${flightList.size}个航班。")
                
                earliest?.let {
                    append("最早的是${it.flightNo}，${formatTimeSpoken(it.departTime)}出发")
                    if (it.duration.isNotBlank()) {
                        append("，飞行${it.duration}")
                    }
                    if (it.punctuality.isNotBlank()) {
                        append("，准点率${it.punctuality}")
                    }
                    append("。")
                }
                
                // 如果准点率最高的不是最早的，也推荐
                mostPunctual?.let {
                    if (it.flightNo != earliest?.flightNo && it.punctualityValue >= 90) {
                        append("准点率最高的是${it.flightNo}，${it.punctuality}。")
                    }
                }
                
            }
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillFlights", "failed to parse", e)
            "${dateFriendly}${from}到${to}的航班查询完成。"
        }
    }

    private fun looksLikeJson(text: String): Boolean {
        if (text.isBlank()) return false
        val first = text.first()
        return first == '{' || first == '['
    }

    private fun extractErrorFromText(text: String): String? {
        if (text.isBlank()) return null
        if (text.startsWith("Error:", ignoreCase = true)) {
            return text.removePrefix("Error:").trim()
        }
        return null
    }

    private fun mapAuthErrorMessage(raw: String): String? {
        if (raw.isBlank()) return null
        val normalized = raw.lowercase()
        val isForbidden = normalized.contains("403") || normalized.contains("forbidden")
        val isUnauthorized = normalized.contains("401") || normalized.contains("unauthorized")
        if (isForbidden || isUnauthorized) {
            return "航班服务权限不足或额度已用尽，请开通或充值后再试。"
        }
        return null
    }
    
    private data class FlightInfo(
        val flightNo: String,
        val departTime: String,
        val arriveTime: String,
        val duration: String,
        val aircraft: String,
        val punctuality: String,
        val punctualityValue: Int,  // 准点率数值，用于排序
        val status: String
    )
    
    private fun parseFlightInfo(obj: JsonObject): FlightInfo? {
        return try {
            // 飞常准实际返回格式:
            // FlightNo: "MU5100"
            // FlightDeptimePlanDate: "2025-12-11 07:00:00"
            // FlightArrtimePlanDate: "2025-12-11 09:15:00"
            // FlightDuration: "108" (分钟)
            // OntimeRate: "96.67%"
            // FlightState: "计划"
            // ftype: "A321"
            // FlightDepAirport: "北京首都"
            // FlightArrAirport: "上海浦东"
            
            val flightNo = obj["FlightNo"]?.jsonPrimitive?.content
                ?: obj["fnum"]?.jsonPrimitive?.content
                ?: return null
            
            // 出发时间：从 "2025-12-11 07:00:00" 提取 "07:00"
            val departTimeRaw = obj["FlightDeptimePlanDate"]?.jsonPrimitive?.content
                ?: obj["deptime"]?.jsonPrimitive?.content
                ?: "?"
            val departTime = extractTime(departTimeRaw)
            
            // 到达时间
            val arriveTimeRaw = obj["FlightArrtimePlanDate"]?.jsonPrimitive?.content
                ?: obj["arrtime"]?.jsonPrimitive?.content
                ?: "?"
            val arriveTime = extractTime(arriveTimeRaw)
            
            // 飞行时长：FlightDuration 是分钟数，如 "108"
            val durationMinutes = obj["FlightDuration"]?.jsonPrimitive?.content?.toIntOrNull()
            val duration = if (durationMinutes != null) {
                val hours = durationMinutes / 60
                val mins = durationMinutes % 60
                if (hours > 0) "${hours}小时${mins}分钟" else "${mins}分钟"
            } else {
                calculateFlightDuration(departTime, arriveTime)
            }
            
            // 机型
            val aircraft = obj["ftype"]?.jsonPrimitive?.content
                ?: obj["generic"]?.jsonPrimitive?.content
                ?: ""
            
            // 准点率
            val punctuality = obj["OntimeRate"]?.jsonPrimitive?.content
                ?: obj["punctuality"]?.jsonPrimitive?.content
                ?: ""
            
            // 航班状态
            val state = obj["FlightState"]?.jsonPrimitive?.content
                ?: obj["state"]?.jsonPrimitive?.content
                ?: ""
            
            val statusText = when {
                state.contains("计划") -> ""
                state.contains("延误") -> "延误"
                state.contains("取消") -> "取消"
                state.contains("起飞") -> "已起飞"
                state.contains("到达") -> "已到达"
                state.contains("备降") -> "备降"
                else -> ""
            }
            
            // 解析准点率数值
            val punctualityValue = punctuality.replace("%", "").toDoubleOrNull()?.toInt() ?: 0
            
            FlightInfo(flightNo, departTime, arriveTime, duration, aircraft, punctuality, punctualityValue, statusText)
        } catch (e: Exception) {
            AgentLogger.verbose(TAG, "ParseFlight", "failed to parse flight", "error" to (e.message ?: "unknown"))
            null
        }
    }
    
    /**
     * 从 "2025-12-11 07:00:00" 提取 "07:00"
     */
    private fun extractTime(dateTimeStr: String): String {
        return try {
            // 格式: "2025-12-11 07:00:00" -> "07:00"
            if (dateTimeStr.contains(" ")) {
                val timePart = dateTimeStr.split(" ").getOrNull(1) ?: return dateTimeStr
                timePart.substring(0, 5)  // "07:00:00" -> "07:00"
            } else if (dateTimeStr.length >= 5) {
                dateTimeStr.substring(0, 5)
            } else {
                dateTimeStr
            }
        } catch (e: Exception) {
            dateTimeStr
        }
    }
    
    private fun calculateFlightDuration(depart: String, arrive: String): String {
        return try {
            val departParts = depart.split(":").map { it.toInt() }
            val arriveParts = arrive.split(":").map { it.toInt() }
            
            if (departParts.size < 2 || arriveParts.size < 2) return ""
            
            val dh = departParts[0]
            val dm = departParts[1]
            val ah = arriveParts[0]
            val am = arriveParts[1]
            
            var hours = ah - dh
            var minutes = am - dm
            
            if (minutes < 0) {
                minutes += 60
                hours -= 1
            }
            if (hours < 0) {
                hours += 24  // 跨天航班
            }
            
            "${hours}h${minutes}m"
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * 格式化时间为口语：18:00 → 下午6点，08:30 → 早上8点半
     */
    private fun formatTimeSpoken(time: String): String {
        return try {
            val parts = time.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()
            
            val period = when {
                hour < 6 -> "凌晨"
                hour < 12 -> "上午"
                hour < 14 -> "中午"
                hour < 18 -> "下午"
                else -> "晚上"
            }
            val hourSpoken = if (hour > 12) hour - 12 else if (hour == 0) 12 else hour
            val minuteSpoken = when (minute) {
                0 -> ""
                30 -> "半"
                else -> "${minute}分"
            }
            "$period${hourSpoken}点$minuteSpoken"
        } catch (e: Exception) {
            time
        }
    }
}
