package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.serialization.json.*

/**
 * 火车票查询 Combo
 * 
 * 输入：from (出发城市), to (到达城市), date (出发日期，可选)
 * 依赖服务：12306-mcp
 * 流程：get-station-code-of-citys x2 → get-tickets
 * 输出：车次、出发/到达时间、历时、票价（精简）
 * 
 * 日期支持语义化输入：今天、明天、后天、周一~周日、下周X、X月X日
 */
class SearchTrainCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "SearchTrainCombo"
    }
    
    override val name = "search_train"
    
    override val description = "查询火车票"
    
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
    
    override val requiredServices = listOf("12306-mcp")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        val from = arguments["from"]?.toString()
            ?: return ComboToolResult.failure("请提供出发城市")
        val to = arguments["to"]?.toString()
            ?: return ComboToolResult.failure("请提供到达城市")
        
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
        
        val client = ensureServiceConnected("12306-mcp")
            ?: return ComboToolResult.failure("12306 服务未连接")
        
        try {
            // Step 1: 获取出发城市的车站代码
            AgentLogger.verbose(TAG, "Execute", "getting station code for from city",
                "city" to from
            )
            
            val fromCodeResult = client.callTool(
                "get-station-code-of-citys",
                mapOf("citys" to CityCodeMappings.normalizeCity(from)),
                history
            )
            
            if (fromCodeResult.isFailure) {
                return ComboToolResult.failure("无法获取 $from 的车站代码: ${fromCodeResult.exceptionOrNull()?.message}")
            }
            
            val fromCodeRaw = fromCodeResult.getOrNull() ?: ""
            AgentLogger.normal(TAG, "Execute", "from station raw response",
                "city" to from,
                "response" to fromCodeRaw.take(500)
            )
            
            val fromCode = extractStationCode(fromCodeRaw)
                ?: return ComboToolResult.failure("未找到 \"$from\" 的车站，API返回: ${fromCodeRaw.take(200)}")
            
            // Step 2: 获取到达城市的车站代码
            AgentLogger.verbose(TAG, "Execute", "getting station code for to city",
                "city" to to
            )
            
            val toCodeResult = client.callTool(
                "get-station-code-of-citys",
                mapOf("citys" to CityCodeMappings.normalizeCity(to)),
                history
            )
            
            if (toCodeResult.isFailure) {
                return ComboToolResult.failure("无法获取 $to 的车站代码: ${toCodeResult.exceptionOrNull()?.message}")
            }
            
            val toCodeRaw = toCodeResult.getOrNull() ?: ""
            AgentLogger.normal(TAG, "Execute", "to station raw response",
                "city" to to,
                "response" to toCodeRaw.take(500)
            )
            
            val toCode = extractStationCode(toCodeRaw)
                ?: return ComboToolResult.failure("未找到 \"$to\" 的车站，API返回: ${toCodeRaw.take(200)}")
            
            // Step 3: 查询车票
            AgentLogger.normal(TAG, "Execute", "querying tickets with codes",
                "fromCode" to fromCode,
                "toCode" to toCode,
                "date" to date
            )
            
            val ticketsResult = client.callTool(
                "get-tickets",
                mapOf(
                    "fromStation" to fromCode,
                    "toStation" to toCode,
                    "date" to date
                ),
                history
            )
            
            if (ticketsResult.isFailure) {
                return ComboToolResult.failure("查询车票失败: ${ticketsResult.exceptionOrNull()?.message}")
            }
            
            val ticketsJson = ticketsResult.getOrNull() ?: return ComboToolResult.failure("查询返回空结果")
            val distilled = distillTicketsResult(ticketsJson, from, to, dateFriendly)
            
            AgentLogger.normal(TAG, "Execute", "train search complete",
                "from" to from,
                "to" to to,
                "date" to date
            )
            
            return ComboToolResult.success(distilled, ticketsJson)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "train search failed", e)
            return ComboToolResult.failure(e.message ?: "火车票查询失败")
        }
    }
    
    private fun extractStationCode(json: String): String? {
        if (json.isBlank()) return null
        
        // 首先尝试直接作为车站代码（3字母格式如 BJP, SHH）
        val trimmed = json.trim()
        if (trimmed.matches(Regex("^[A-Z]{3}$"))) {
            return trimmed
        }
        
        return try {
            val element = Json.parseToJsonElement(json)
            
            // get-station-code-of-citys 实际返回格式:
            // {"北京":{"station_code":"BJP","station_name":"北京"}}
            // 即：以城市名为 key 的嵌套对象
            //
            // 其他可能格式:
            // {"station_code": "BJP"}
            // [{"station_code": "BJP", ...}]
            // "BJP"
            
            when {
                element is JsonPrimitive && element.isString -> element.content
                element is JsonArray && element.isNotEmpty() -> {
                    val first = element[0]
                    if (first is JsonPrimitive) {
                        first.content
                    } else {
                        extractCodeFromObject(first.jsonObject)
                    }
                }
                element is JsonObject -> {
                    // 首先尝试直接提取 station_code 等字段
                    extractCodeFromObject(element)?.let { return it }
                    
                    // 如果没有，可能是 {"城市名": {...}} 格式
                    // 取第一个 value（应该是嵌套对象）
                    element.entries.firstOrNull()?.let { (_, value) ->
                        when (value) {
                            is JsonObject -> extractCodeFromObject(value)
                            is JsonPrimitive -> value.content
                            else -> null
                        }
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            // JSON 解析失败，尝试从文本中提取 3 字母代码
            AgentLogger.verbose(TAG, "ExtractStationCode", "JSON parse failed, trying regex",
                "input" to json.take(200)
            )
            extractCodeFromText(json)
        }
    }
    
    private fun extractCodeFromObject(obj: JsonObject): String? {
        return obj["station_code"]?.jsonPrimitive?.content
            ?: obj["code"]?.jsonPrimitive?.content
            ?: obj["telecode"]?.jsonPrimitive?.content
            ?: obj["stationCode"]?.jsonPrimitive?.content
    }
    
    /**
     * 从文本中提取 3 字母火车站代码
     * 12306 电报码格式：3位大写字母
     */
    private fun extractCodeFromText(text: String): String? {
        // 匹配 3 个大写字母（火车站电报码格式）
        val regex = Regex("\\b([A-Z]{3})\\b")
        return regex.find(text)?.groupValues?.get(1)
    }
    
    private fun distillTicketsResult(ticketsText: String, from: String, to: String, date: String): String {
        // MCP 返回的是人类可读的纯文本格式:
        // G507 北京西(telecode:BXP) -> 石家庄(telecode:SJP) 16:30 -> 18:07 历时：01:37
        // - 商务座: 剩余11张票 407元
        // - 二等座: 有票 129元
        
        if (ticketsText.isBlank()) {
            return "${from}到${to}没有查到直达车次。"
        }
        
        // 解析并过滤有票的车次
        val allTrains = parseTextTickets(ticketsText)
        val availableTrains = allTrains.filter { it.hasTickets }
        
        if (availableTrains.isEmpty()) {
            return "${date}${from}到${to}共${allTrains.size}趟车，票都卖完了，换个日期试试吧。"
        }
        
        // 找出最快和最便宜的车次
        val fastest = availableTrains.minByOrNull { it.durationMinutes }
        val cheapest = availableTrains.minByOrNull { it.lowestPrice }
        
        // 直接生成 TTS 友好的最终回复
        return buildString {
            append("${date}${from}到${to}有${availableTrains.size}趟车。")
            
            fastest?.let {
                val durationSpoken = formatDurationSpoken(it.durationMinutes)
                append("推荐${it.trainNo}，${formatTimeSpoken(it.departTime)}出发，${formatTimeSpoken(it.arriveTime)}到达")
                if (durationSpoken.isNotBlank()) {
                    append("，路上约$durationSpoken")
                }
                if (it.prices.isNotEmpty()) {
                    append("，${it.prices.first()}")
                }
                append("。")
            }
            
            // 如果最便宜和最快不是同一趟，也推荐
            cheapest?.let {
                if (it.trainNo != fastest?.trainNo) {
                    append("最便宜的是${it.trainNo}")
                    if (it.prices.isNotEmpty()) {
                        append("，${it.prices.first()}")
                    }
                    append("。")
                }
            }
            
        }
    }
    
    /**
     * 解析 MCP 返回的纯文本车票信息
     */
    private fun parseTextTickets(text: String): List<TrainInfo> {
        val trains = mutableListOf<TrainInfo>()
        val lines = text.lines()
        
        // 跳过第一行（标题行）
        var i = if (lines.firstOrNull()?.contains("车次|") == true) 1 else 0
        
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // 匹配车次行: G507 北京西(telecode:BXP) -> 石家庄(telecode:SJP) 16:30 -> 18:07 历时：01:37
            val trainRegex = Regex("""^([A-Z]\d+)\s+.+\s+(\d{2}:\d{2})\s*->\s*(\d{2}:\d{2})\s+历时[：:](\d{2}:\d{2})""")
            val match = trainRegex.find(line)
            
            if (match != null) {
                val trainNo = match.groupValues[1]
                val departTime = match.groupValues[2]
                val arriveTime = match.groupValues[3]
                val durationRaw = match.groupValues[4]
                
                // 收集座位信息
                val seatLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim().startsWith("-")) {
                    seatLines.add(lines[i].trim())
                    i++
                }
                
                val seatInfo = parseSeatInfo(seatLines)
                trains.add(TrainInfo(
                    trainNo = trainNo,
                    departTime = departTime,
                    arriveTime = arriveTime,
                    durationShort = formatDurationShort(durationRaw),
                    durationMinutes = parseDurationMinutes(durationRaw),
                    prices = seatInfo.prices,
                    lowestPrice = seatInfo.lowestPrice,
                    hasTickets = seatInfo.hasTickets
                ))
            } else {
                i++
            }
        }
        
        return trains
    }
    
    private data class TrainInfo(
        val trainNo: String,
        val departTime: String,
        val arriveTime: String,
        val durationShort: String,  // 1h27m 格式
        val durationMinutes: Int,   // 总分钟数，用于排序
        val prices: List<String>,   // ["二等座129元", "一等座206元"]
        val lowestPrice: Double,    // 最低价格，用于排序
        val hasTickets: Boolean
    )
    
    private data class SeatParseResult(
        val prices: List<String>,
        val lowestPrice: Double,  // 最低价格数值
        val hasTickets: Boolean
    )
    
    /**
     * 解析座位信息，返回有票座位的价格列表
     */
    private fun parseSeatInfo(seatLines: List<String>): SeatParseResult {
        val prices = mutableListOf<String>()
        var hasTickets = false
        var lowestPrice = Double.MAX_VALUE
        
        // 座位显示优先级（经济实惠优先）
        val seatPriority = listOf("二等座", "硬座", "一等座", "硬卧", "软卧", "商务座")
        
        data class SeatPrice(val type: String, val price: String, val priceValue: Double, val priority: Int)
        val availableSeats = mutableListOf<SeatPrice>()
        
        for (line in seatLines) {
            // - 二等座: 有票 129元
            // - 商务座: 剩余11张票 407元
            // - 硬座: 无票 43.5元
            val seatRegex = Regex("""^-\s*(.+?):\s*(有票|剩余\d+张票|无票)\s*([\d.]+)元""")
            val match = seatRegex.find(line) ?: continue
            
            val seatType = match.groupValues[1]
            val availability = match.groupValues[2]
            val price = match.groupValues[3]
            val priceValue = price.toDoubleOrNull() ?: Double.MAX_VALUE
            
            if (availability != "无票" && seatType != "无座") {
                hasTickets = true
                if (priceValue < lowestPrice) {
                    lowestPrice = priceValue
                }
                val priority = seatPriority.indexOf(seatType).let { if (it < 0) 99 else it }
                availableSeats.add(SeatPrice(seatType, price, priceValue, priority))
            }
        }
        
        // 按优先级排序，取前2个
        availableSeats.sortBy { it.priority }
        prices.addAll(availableSeats.take(2).map { "${it.type}${it.price}元" })
        
        return SeatParseResult(prices, if (lowestPrice == Double.MAX_VALUE) 0.0 else lowestPrice, hasTickets)
    }
    
    /**
     * 解析时长为分钟数：01:37 → 97
     */
    private fun parseDurationMinutes(duration: String): Int {
        return try {
            val parts = duration.split(":")
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            hours * 60 + minutes
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }
    
    /**
     * 格式化时长：01:37 → 1h37m
     */
    private fun formatDurationShort(duration: String): String {
        return try {
            val parts = duration.split(":")
            val hours = parts[0].toInt()
            val minutes = parts[1].toInt()
            if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
        } catch (e: Exception) {
            duration
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

    /**
     * 格式化历时为口语：97分钟 → 1小时37分钟
     */
    private fun formatDurationSpoken(totalMinutes: Int): String {
        if (totalMinutes <= 0 || totalMinutes == Int.MAX_VALUE) return ""
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours <= 0 -> "${minutes}分钟"
            minutes == 0 -> "${hours}小时"
            else -> "${hours}小时${minutes}分钟"
        }
    }
    
    /**
     * 如果解析失败，直接精简原文
     */
    private fun distillRawText(text: String, from: String, to: String, date: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        
        // 提取车次行（以字母+数字开头的行）
        val trainLines = lines.filter { line ->
            line.trim().matches(Regex("^[A-Z]\\d+\\s+.+"))
        }.take(config.maxTrainResults)
        
        if (trainLines.isEmpty()) {
            return "$date $from→$to 查询完成"
        }
        
        return buildString {
            append("$date $from→$to:\n")
            trainLines.forEach { line ->
                val simplified = line
                    .replace(Regex("""\(telecode:[A-Z]+\)"""), "")
                    .replace("->", "→")
                    .trim()
                append("$simplified\n")
            }
        }.trimEnd()
    }
}
