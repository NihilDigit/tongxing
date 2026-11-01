package com.nihildigit.agentcore.hub

import kotlinx.serialization.json.*

/**
 * 输出精简工具
 * 
 * 用于从 MCP 工具的原始响应中提取关键字段，减少 context token 消耗。
 */
object OutputDistiller {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    /**
     * 从 JSON 对象中提取指定字段
     * 
     * @param jsonString 原始 JSON 字符串
     * @param paths 要提取的字段路径（支持点分隔，如 "result.location"）
     * @return 精简后的 JSON 对象
     */
    fun extract(jsonString: String, paths: List<String>): JsonObject {
        return try {
            val root = json.parseToJsonElement(jsonString)
            val result = mutableMapOf<String, JsonElement>()
            
            for (path in paths) {
                val value = extractPath(root, path)
                if (value != null) {
                    // 使用路径最后一段作为 key
                    val key = path.substringAfterLast('.')
                    result[key] = value
                }
            }
            
            JsonObject(result)
        } catch (e: Exception) {
            JsonObject(mapOf("error" to JsonPrimitive("Failed to parse JSON: ${e.message}")))
        }
    }
    
    /**
     * 从 JSON 中按路径提取值
     */
    private fun extractPath(element: JsonElement, path: String): JsonElement? {
        val parts = path.split('.')
        var current: JsonElement? = element
        
        for (part in parts) {
            current = when (current) {
                is JsonObject -> current[part]
                is JsonArray -> {
                    // 支持数组索引，如 "pois.0.name"
                    part.toIntOrNull()?.let { index ->
                        current.getOrNull(index)
                    }
                }
                else -> null
            }
            if (current == null) break
        }
        
        return current
    }
    
    /**
     * 截断文本到指定长度
     * 
     * @param text 原始文本
     * @param maxLength 最大长度
     * @param ellipsis 截断后缀（默认 "..."）
     * @return 截断后的文本
     */
    fun truncate(text: String, maxLength: Int, ellipsis: String = "..."): String {
        return if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength - ellipsis.length) + ellipsis
        }
    }
    
    /**
     * 从列表中提取前 N 个元素，并只保留指定字段
     * 
     * @param jsonString 包含数组的 JSON 字符串
     * @param arrayPath 数组的路径
     * @param n 保留数量
     * @param fields 每个元素要保留的字段
     * @return 精简后的数组
     */
    fun topN(jsonString: String, arrayPath: String, n: Int, fields: List<String>): JsonArray {
        return try {
            val root = json.parseToJsonElement(jsonString)
            val array = extractPath(root, arrayPath) as? JsonArray ?: return JsonArray(emptyList())
            
            val result = array.take(n).map { element ->
                if (element is JsonObject) {
                    val filtered = element.filterKeys { it in fields }
                    JsonObject(filtered)
                } else {
                    element
                }
            }
            
            JsonArray(result)
        } catch (e: Exception) {
            JsonArray(emptyList())
        }
    }
    
    /**
     * 将技术错误转换为用户友好描述
     */
    fun friendlyError(error: String): String {
        return when {
            error.contains("UnknownHostException") || error.contains("DNS") -> 
                "网络连接失败，请检查网络设置"
            error.contains("ConnectException") || error.contains("Connection refused") -> 
                "服务暂时不可用，请稍后再试"
            error.contains("SocketTimeoutException") || error.contains("timeout", ignoreCase = true) -> 
                "服务响应超时，请稍后再试"
            error.contains("401") || error.contains("Unauthorized") -> 
                "服务认证失败，请检查配置"
            error.contains("403") || error.contains("Forbidden") -> 
                "没有访问权限"
            error.contains("404") || error.contains("Not Found") -> 
                "请求的资源不存在"
            error.contains("500") || error.contains("Internal Server Error") -> 
                "服务器内部错误，请稍后再试"
            error.contains("503") || error.contains("Service Unavailable") -> 
                "服务暂时不可用，请稍后再试"
            else -> "操作失败：$error"
        }
    }
    
    /**
     * 格式化距离（米转可读格式）
     */
    fun formatDistance(meters: Int): String {
        return when {
            meters < 100 -> "${meters}米"
            meters < 1000 -> "${(meters / 10) * 10}米"
            else -> String.format("%.1f公里", meters / 1000.0)
        }
    }
    
    /**
     * 格式化距离（TTS 自然语言版本）
     * 
     * 例如：
     * - 350米 → "350米"
     * - 1200米 → "1公里"（四舍五入到整数）
     * - 4300米 → "4公里"
     */
    fun formatDistanceNatural(meters: Int): String {
        return when {
            meters < 100 -> "${meters}米"
            meters < 1000 -> "${(meters / 100) * 100}米"  // 四舍五入到百米
            else -> {
                val km = (meters + 500) / 1000  // 四舍五入到公里
                "${km}公里"
            }
        }
    }
    
    /**
     * 格式化时间（秒转可读格式）
     */
    fun formatDuration(seconds: Int): String {
        return when {
            seconds < 60 -> "不到1分钟"
            seconds < 3600 -> "${seconds / 60}分钟"
            else -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                if (minutes > 0) "${hours}小时${minutes}分钟" else "${hours}小时"
            }
        }
    }
    
    /**
     * 格式化时间（TTS 自然语言版本）
     * 
     * 例如：
     * - 30秒 → "1分钟左右"
     * - 300秒 → "5分钟左右"
     * - 3900秒 → "1小时左右"
     */
    fun formatDurationNatural(seconds: Int): String {
        return when {
            seconds < 60 -> "1分钟左右"
            seconds < 3600 -> {
                val minutes = (seconds + 30) / 60  // 四舍五入
                "${minutes}分钟左右"
            }
            else -> {
                val hours = seconds / 3600
                val remainingMinutes = (seconds % 3600) / 60
                when {
                    remainingMinutes < 15 -> "${hours}小时左右"
                    remainingMinutes < 45 -> "${hours}个半小时"
                    else -> "${hours + 1}小时左右"
                }
            }
        }
    }
    
    /**
     * 格式化价格
     */
    fun formatPrice(price: Double): String {
        return if (price == price.toLong().toDouble()) {
            "${price.toLong()}元"
        } else {
            String.format("%.1f元", price)
        }
    }
    
    /**
     * 解析语义化日期为 YYYY-MM-DD 格式
     * 
     * 支持的输入：
     * - 相对日期：今天、明天、后天、大后天
     * - 星期：周一、周二、下周一、下周日、这周六、本周日
     * - 具体日期：12月25日、12月25号、12-25、2024年12月25日
     * - 已格式化：2024-12-25（直接返回）
     * - 空或null：返回今天
     * 
     * @param input 用户输入的日期描述
     * @return YYYY-MM-DD 格式的日期字符串，解析失败返回 null
     */
    fun parseSemanticDate(input: String?): String? {
        if (input.isNullOrBlank()) {
            return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
        
        val trimmed = input.trim()
        val today = java.time.LocalDate.now()
        
        // 1. 已经是 YYYY-MM-DD 格式，直接返回
        if (trimmed.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            return trimmed
        }
        
        // 2. 相对日期
        val relativeDays = mapOf(
            "今天" to 0L,
            "今日" to 0L,
            "明天" to 1L,
            "明日" to 1L,
            "后天" to 2L,
            "后日" to 2L,
            "大后天" to 3L
        )
        relativeDays[trimmed]?.let { days ->
            return today.plusDays(days).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
        
        // 3. 星期解析
        val weekdayMap = mapOf(
            "一" to java.time.DayOfWeek.MONDAY,
            "二" to java.time.DayOfWeek.TUESDAY,
            "三" to java.time.DayOfWeek.WEDNESDAY,
            "四" to java.time.DayOfWeek.THURSDAY,
            "五" to java.time.DayOfWeek.FRIDAY,
            "六" to java.time.DayOfWeek.SATURDAY,
            "日" to java.time.DayOfWeek.SUNDAY,
            "天" to java.time.DayOfWeek.SUNDAY
        )
        
        // 匹配：周X、星期X、礼拜X、本周X、这周X、下周X、下个星期X
        val weekdayRegex = Regex("""(下个?|本|这)?(周|星期|礼拜)([一二三四五六日天])""")
        weekdayRegex.find(trimmed)?.let { match ->
            val prefix = match.groupValues[1]
            val dayChar = match.groupValues[3]
            val targetDay = weekdayMap[dayChar] ?: return@let
            
            val isNextWeek = prefix == "下" || prefix == "下个"
            var targetDate = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(targetDay))
            
            // 如果是"下周"，确保是下一周
            if (isNextWeek && !targetDate.isAfter(today.plusDays(7))) {
                targetDate = targetDate.plusWeeks(1)
            }
            // 如果是本周但目标日已过，取下周
            if (!isNextWeek && targetDate.isBefore(today)) {
                targetDate = targetDate.plusWeeks(1)
            }
            
            return targetDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        }
        
        // 4. 完整日期：YYYY年MM月DD日（必须在 X月X日 之前匹配）
        val fullDateRegex = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})[日号]?""")
        fullDateRegex.find(trimmed)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            
            return try {
                java.time.LocalDate.of(year, month, day)
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                null
            }
        }
        
        // 5. 具体日期：X月X日/号（无年份，需推断）
        val mdRegex = Regex("""(\d{1,2})月(\d{1,2})[日号]?""")
        mdRegex.find(trimmed)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            
            // 推断年份：如果日期已过，取明年
            var year = today.year
            if (month < today.monthValue || (month == today.monthValue && day < today.dayOfMonth)) {
                year++
            }
            
            return try {
                java.time.LocalDate.of(year, month, day)
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                null
            }
        }
        
        // 6. MM-DD 或 MM/DD 格式
        val shortDateRegex = Regex("""(\d{1,2})[-/](\d{1,2})""")
        shortDateRegex.find(trimmed)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            
            var year = today.year
            if (month < today.monthValue || (month == today.monthValue && day < today.dayOfMonth)) {
                year++
            }
            
            return try {
                java.time.LocalDate.of(year, month, day)
                    .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                null
            }
        }
        
        // 无法解析
        return null
    }
    
    /**
     * 将日期格式化为用户友好的描述
     * 
     * @param dateStr YYYY-MM-DD 格式的日期
     * @return 用户友好的描述，如 "今天"、"明天"、"12月25日(周三)"
     */
    fun formatDateFriendly(dateStr: String): String {
        return try {
            val date = java.time.LocalDate.parse(dateStr)
            val today = java.time.LocalDate.now()
            val daysDiff = java.time.temporal.ChronoUnit.DAYS.between(today, date)
            
            when (daysDiff) {
                0L -> "今天"
                1L -> "明天"
                2L -> "后天"
                else -> {
                    val weekday = when (date.dayOfWeek) {
                        java.time.DayOfWeek.MONDAY -> "周一"
                        java.time.DayOfWeek.TUESDAY -> "周二"
                        java.time.DayOfWeek.WEDNESDAY -> "周三"
                        java.time.DayOfWeek.THURSDAY -> "周四"
                        java.time.DayOfWeek.FRIDAY -> "周五"
                        java.time.DayOfWeek.SATURDAY -> "周六"
                        java.time.DayOfWeek.SUNDAY -> "周日"
                    }
                    "${date.monthValue}月${date.dayOfMonth}日($weekday)"
                }
            }
        } catch (e: Exception) {
            dateStr
        }
    }
}
