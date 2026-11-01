package com.nihildigit.agentcore.hub

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutputDistillerTest {
    
    // === extract tests ===
    
    @Test
    fun `extract simple field`() {
        val json = """{"name": "test", "value": 123}"""
        val result = OutputDistiller.extract(json, listOf("name"))
        
        assertEquals("test", result["name"]?.jsonPrimitive?.content)
    }
    
    @Test
    fun `extract nested field`() {
        val json = """{"result": {"location": "116.397428,39.90923"}}"""
        val result = OutputDistiller.extract(json, listOf("result.location"))
        
        assertEquals("116.397428,39.90923", result["location"]?.jsonPrimitive?.content)
    }
    
    @Test
    fun `extract multiple fields`() {
        val json = """{"a": 1, "b": 2, "c": 3}"""
        val result = OutputDistiller.extract(json, listOf("a", "b"))
        
        assertEquals(1, result["a"]?.jsonPrimitive?.int)
        assertEquals(2, result["b"]?.jsonPrimitive?.int)
        assertNull(result["c"])
    }
    
    @Test
    fun `extract with array index`() {
        val json = """{"pois": [{"name": "first"}, {"name": "second"}]}"""
        val result = OutputDistiller.extract(json, listOf("pois.0.name"))
        
        assertEquals("first", result["name"]?.jsonPrimitive?.content)
    }
    
    @Test
    fun `extract missing field returns empty`() {
        val json = """{"name": "test"}"""
        val result = OutputDistiller.extract(json, listOf("missing"))
        
        assertNull(result["missing"])
    }
    
    @Test
    fun `extract invalid json returns error`() {
        val json = """not valid json"""
        val result = OutputDistiller.extract(json, listOf("field"))
        
        assertNotNull(result["error"])
    }
    
    // === truncate tests ===
    
    @Test
    fun `truncate short text unchanged`() {
        val text = "short"
        val result = OutputDistiller.truncate(text, 10)
        
        assertEquals("short", result)
    }
    
    @Test
    fun `truncate long text with ellipsis`() {
        val text = "this is a very long text"
        val result = OutputDistiller.truncate(text, 10)
        
        assertEquals("this is...", result)
        assertEquals(10, result.length)
    }
    
    @Test
    fun `truncate with custom ellipsis`() {
        val text = "this is a very long text"
        val result = OutputDistiller.truncate(text, 10, "…")
        
        assertEquals("this is a…", result)
    }
    
    @Test
    fun `truncate exact length unchanged`() {
        val text = "exactly10c"
        val result = OutputDistiller.truncate(text, 10)
        
        assertEquals("exactly10c", result)
    }
    
    // === topN tests ===
    
    @Test
    fun `topN extracts correct number of elements`() {
        val json = """{"pois": [{"name": "A"}, {"name": "B"}, {"name": "C"}, {"name": "D"}]}"""
        val result = OutputDistiller.topN(json, "pois", 2, listOf("name"))
        
        assertEquals(2, result.size)
    }
    
    @Test
    fun `topN filters fields`() {
        val json = """{"pois": [{"name": "A", "address": "addr1", "distance": 100}]}"""
        val result = OutputDistiller.topN(json, "pois", 1, listOf("name", "distance"))
        
        val first = result[0].jsonObject
        assertEquals("A", first["name"]?.jsonPrimitive?.content)
        assertEquals(100, first["distance"]?.jsonPrimitive?.int)
        assertNull(first["address"])
    }
    
    @Test
    fun `topN with missing array returns empty`() {
        val json = """{"data": "not an array"}"""
        val result = OutputDistiller.topN(json, "pois", 3, listOf("name"))
        
        assertEquals(0, result.size)
    }
    
    @Test
    fun `topN with fewer elements than n`() {
        val json = """{"pois": [{"name": "A"}]}"""
        val result = OutputDistiller.topN(json, "pois", 10, listOf("name"))
        
        assertEquals(1, result.size)
    }
    
    // === friendlyError tests ===
    
    @Test
    fun `friendlyError DNS failure`() {
        val result = OutputDistiller.friendlyError("java.net.UnknownHostException: example.com")
        
        assertEquals("网络连接失败，请检查网络设置", result)
    }
    
    @Test
    fun `friendlyError connection refused`() {
        val result = OutputDistiller.friendlyError("Connection refused to localhost:8080")
        
        assertEquals("服务暂时不可用，请稍后再试", result)
    }
    
    @Test
    fun `friendlyError timeout`() {
        val result = OutputDistiller.friendlyError("SocketTimeoutException: Read timed out")
        
        assertEquals("服务响应超时，请稍后再试", result)
    }
    
    @Test
    fun `friendlyError 401`() {
        val result = OutputDistiller.friendlyError("HTTP 401 Unauthorized")
        
        assertEquals("服务认证失败，请检查配置", result)
    }
    
    @Test
    fun `friendlyError 404`() {
        val result = OutputDistiller.friendlyError("HTTP 404 Not Found")
        
        assertEquals("请求的资源不存在", result)
    }
    
    @Test
    fun `friendlyError unknown`() {
        val result = OutputDistiller.friendlyError("Some random error")
        
        assertEquals("操作失败：Some random error", result)
    }
    
    // === formatDistance tests ===
    
    @Test
    fun `formatDistance less than 100m`() {
        assertEquals("50米", OutputDistiller.formatDistance(50))
        assertEquals("99米", OutputDistiller.formatDistance(99))
    }
    
    @Test
    fun `formatDistance 100m to 1000m rounds to 10m`() {
        assertEquals("120米", OutputDistiller.formatDistance(123))
        assertEquals("500米", OutputDistiller.formatDistance(505))
        assertEquals("990米", OutputDistiller.formatDistance(999))
    }
    
    @Test
    fun `formatDistance 1000m and above shows km`() {
        assertEquals("1.0公里", OutputDistiller.formatDistance(1000))
        assertEquals("1.5公里", OutputDistiller.formatDistance(1500))
        assertEquals("10.0公里", OutputDistiller.formatDistance(10000))
    }
    
    // === formatDuration tests ===
    
    @Test
    fun `formatDuration less than 60s`() {
        assertEquals("不到1分钟", OutputDistiller.formatDuration(30))
        assertEquals("不到1分钟", OutputDistiller.formatDuration(59))
    }
    
    @Test
    fun `formatDuration minutes`() {
        assertEquals("1分钟", OutputDistiller.formatDuration(60))
        assertEquals("5分钟", OutputDistiller.formatDuration(300))
        assertEquals("59分钟", OutputDistiller.formatDuration(3599))
    }
    
    @Test
    fun `formatDuration hours`() {
        assertEquals("1小时", OutputDistiller.formatDuration(3600))
        assertEquals("1小时30分钟", OutputDistiller.formatDuration(5400))
        assertEquals("2小时", OutputDistiller.formatDuration(7200))
    }
    
    // === formatPrice tests ===
    
    @Test
    fun `formatPrice integer`() {
        assertEquals("25元", OutputDistiller.formatPrice(25.0))
        assertEquals("100元", OutputDistiller.formatPrice(100.0))
    }
    
    @Test
    fun `formatPrice decimal`() {
        assertEquals("25.5元", OutputDistiller.formatPrice(25.5))
        assertEquals("99.9元", OutputDistiller.formatPrice(99.9))
    }
    
    // === parseSemanticDate tests ===
    
    @Test
    fun `parseSemanticDate null or blank returns today`() {
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        
        assertEquals(today, OutputDistiller.parseSemanticDate(null))
        assertEquals(today, OutputDistiller.parseSemanticDate(""))
        assertEquals(today, OutputDistiller.parseSemanticDate("   "))
    }
    
    @Test
    fun `parseSemanticDate already formatted passes through`() {
        assertEquals("2024-12-25", OutputDistiller.parseSemanticDate("2024-12-25"))
        assertEquals("2025-01-01", OutputDistiller.parseSemanticDate("2025-01-01"))
    }
    
    @Test
    fun `parseSemanticDate relative days`() {
        val today = java.time.LocalDate.now()
        val format = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        
        assertEquals(today.format(format), OutputDistiller.parseSemanticDate("今天"))
        assertEquals(today.format(format), OutputDistiller.parseSemanticDate("今日"))
        assertEquals(today.plusDays(1).format(format), OutputDistiller.parseSemanticDate("明天"))
        assertEquals(today.plusDays(1).format(format), OutputDistiller.parseSemanticDate("明日"))
        assertEquals(today.plusDays(2).format(format), OutputDistiller.parseSemanticDate("后天"))
        assertEquals(today.plusDays(3).format(format), OutputDistiller.parseSemanticDate("大后天"))
    }
    
    @Test
    fun `parseSemanticDate weekdays`() {
        // 测试周一到周日都能解析
        assertNotNull(OutputDistiller.parseSemanticDate("周一"))
        assertNotNull(OutputDistiller.parseSemanticDate("周二"))
        assertNotNull(OutputDistiller.parseSemanticDate("周三"))
        assertNotNull(OutputDistiller.parseSemanticDate("周四"))
        assertNotNull(OutputDistiller.parseSemanticDate("周五"))
        assertNotNull(OutputDistiller.parseSemanticDate("周六"))
        assertNotNull(OutputDistiller.parseSemanticDate("周日"))
        assertNotNull(OutputDistiller.parseSemanticDate("周天"))
        assertNotNull(OutputDistiller.parseSemanticDate("星期一"))
        assertNotNull(OutputDistiller.parseSemanticDate("星期日"))
        assertNotNull(OutputDistiller.parseSemanticDate("下周一"))
        assertNotNull(OutputDistiller.parseSemanticDate("下个星期五"))
    }
    
    @Test
    fun `parseSemanticDate specific date`() {
        // X月X日 格式
        val result1 = OutputDistiller.parseSemanticDate("12月25日")
        assertNotNull(result1)
        assertTrue(result1!!.endsWith("-12-25"))
        
        val result2 = OutputDistiller.parseSemanticDate("1月1号")
        assertNotNull(result2)
        assertTrue(result2!!.endsWith("-01-01"))
    }
    
    @Test
    fun `parseSemanticDate short format`() {
        // MM-DD 和 MM/DD 格式
        val result1 = OutputDistiller.parseSemanticDate("12-25")
        assertNotNull(result1)
        assertTrue(result1!!.endsWith("-12-25"))
        
        val result2 = OutputDistiller.parseSemanticDate("1/15")
        assertNotNull(result2)
        assertTrue(result2!!.contains("-01-15"))
    }
    
    @Test
    fun `parseSemanticDate full format`() {
        assertEquals("2024-12-25", OutputDistiller.parseSemanticDate("2024年12月25日"))
        assertEquals("2025-01-01", OutputDistiller.parseSemanticDate("2025年1月1号"))
    }
    
    @Test
    fun `parseSemanticDate invalid returns null`() {
        assertNull(OutputDistiller.parseSemanticDate("随便写的"))
        assertNull(OutputDistiller.parseSemanticDate("abc123"))
        assertNull(OutputDistiller.parseSemanticDate("不知道"))
    }
    
    // === formatDateFriendly tests ===
    
    @Test
    fun `formatDateFriendly today tomorrow`() {
        val today = java.time.LocalDate.now()
        val format = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
        
        assertEquals("今天", OutputDistiller.formatDateFriendly(today.format(format)))
        assertEquals("明天", OutputDistiller.formatDateFriendly(today.plusDays(1).format(format)))
        assertEquals("后天", OutputDistiller.formatDateFriendly(today.plusDays(2).format(format)))
    }
    
    @Test
    fun `formatDateFriendly other dates show weekday`() {
        val date = java.time.LocalDate.now().plusDays(10)
        val result = OutputDistiller.formatDateFriendly(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))
        
        // 应该包含 "月"、"日" 和 "周"
        assertTrue(result.contains("月"))
        assertTrue(result.contains("日"))
        assertTrue(result.contains("周"))
    }
}
