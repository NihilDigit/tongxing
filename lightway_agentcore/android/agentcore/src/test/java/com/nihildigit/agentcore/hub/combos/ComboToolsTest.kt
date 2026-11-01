package com.nihildigit.agentcore.hub.combos

import android.location.Location
import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.mcp.MCPConfig
import com.nihildigit.agentcore.model.ChatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ComboToolsTest {
    
    private lateinit var testScope: TestScope
    private lateinit var connectionManager: McpConnectionManager
    private lateinit var contextProvider: FakeContextProvider
    private lateinit var config: HubConfig
    
    @Before
    fun setUp() {
        testScope = TestScope()
        connectionManager = McpConnectionManager(testScope)
        contextProvider = FakeContextProvider()
        config = HubConfig()
        
        // Register amap-maps service as enabled by default
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "amap-maps",
                name = "amap",
                displayName = "高德地图",
                description = "Amap maps service",
                tier = McpServiceTier.CORE,
                config = MCPConfig.StreamableHttp("http://localhost:8080/amap"),
                defaultEnabled = true
            )
        ))
        
    }
    
    // === FindNearbyCombo Tests ===
    
    @Test
    fun `FindNearbyCombo has correct metadata`() {
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        assertEquals("find_nearby", combo.name)
        assertEquals("[快捷] find_nearby", combo.displayName)
        assertEquals(listOf("amap-maps"), combo.requiredServices)
        assertEquals(config.comboPriority, combo.priority)
        assertNotNull(combo.parametersSchema)
    }
    
    @Test
    fun `FindNearbyCombo isAvailable when amap-maps enabled`() {
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        assertTrue(combo.isAvailable())
    }
    
    @Test
    fun `FindNearbyCombo not available when amap-maps disabled`() {
        connectionManager.setServiceEnabled("amap-maps", false)
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        assertFalse(combo.isAvailable())
    }
    
    @Test
    fun `FindNearbyCombo fails without keyword`() = runTest {
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
        assertTrue(result.content.contains("关键词"))
    }
    
    @Test
    fun `FindNearbyCombo fails without location permission`() = runTest {
        contextProvider.mockHasPermission = false
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(mapOf("keyword" to "咖啡店"))
        
        assertFalse(result.success)
        assertTrue(result.content.contains("位置权限"))
    }
    
    @Test
    fun `FindNearbyCombo fails without current location`() = runTest {
        contextProvider.mockLocation = null
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(mapOf("keyword" to "咖啡店"))
        
        assertFalse(result.success)
        assertTrue(result.content.contains("位置"))
    }
    
    // === NavigateToCombo Tests ===
    
    @Test
    fun `NavigateToCombo has correct metadata`() {
        val combo = NavigateToCombo(connectionManager, contextProvider, config)
        
        assertEquals("navigate_to", combo.name)
        assertEquals(listOf("amap-maps"), combo.requiredServices)
        assertNotNull(combo.parametersSchema)
    }
    
    @Test
    fun `NavigateToCombo fails without location id`() = runTest {
        val combo = NavigateToCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
        assertTrue(result.content.contains("location_id"))
    }
    
    // === WeatherHereCombo Tests ===
    
    @Test
    fun `WeatherHereCombo has correct metadata`() {
        val combo = WeatherHereCombo(connectionManager, contextProvider, config)
        
        assertEquals("weather_here", combo.name)
        assertEquals(listOf("amap-maps"), combo.requiredServices)
        // Weather combo has no required parameters
    }
    
    @Test
    fun `WeatherHereCombo fails without location`() = runTest {
        contextProvider.mockLocation = null
        val combo = WeatherHereCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
    }
    
    // === SearchTrainCombo Tests ===
    
    @Test
    fun `SearchTrainCombo has correct metadata`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "12306-mcp",
                name = "12306",
                displayName = "12306火车票",
                description = "Train ticket service",
                tier = McpServiceTier.STANDARD,
                config = MCPConfig.StreamableHttp("http://localhost:8080/12306"),
                defaultEnabled = true
            )
        ))
        
        val combo = SearchTrainCombo(connectionManager, contextProvider, config)
        
        assertEquals("search_train", combo.name)
        assertEquals(listOf("12306-mcp"), combo.requiredServices)
    }
    
    @Test
    fun `SearchTrainCombo fails without required parameters`() = runTest {
        val combo = SearchTrainCombo(connectionManager, contextProvider, config)
        
        // Missing from
        var result = combo.execute(mapOf("to" to "上海"))
        assertFalse(result.success)
        
        // Missing to
        result = combo.execute(mapOf("from" to "北京"))
        assertFalse(result.success)
    }
    
    // === SearchFlightCombo Tests ===
    
    @Test
    fun `SearchFlightCombo has correct metadata`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "variflight-mcp",
                name = "variflight",
                displayName = "飞常准航班",
                description = "Flight service",
                tier = McpServiceTier.STANDARD,
                config = MCPConfig.StreamableHttp("http://localhost:8080/variflight"),
                defaultEnabled = true
            )
        ))
        
        val combo = SearchFlightCombo(connectionManager, contextProvider, config)
        
        assertEquals("search_flight", combo.name)
        assertEquals(listOf("variflight-mcp"), combo.requiredServices)
    }
    
    @Test
    fun `SearchFlightCombo fails without required parameters`() = runTest {
        val combo = SearchFlightCombo(connectionManager, contextProvider, config)
        
        // Missing from
        var result = combo.execute(mapOf("to" to "上海"))
        assertFalse(result.success)
        
        // Missing to
        result = combo.execute(mapOf("from" to "北京"))
        assertFalse(result.success)
    }
    
    // === TaxiStatusCombo Tests ===
    
    @Test
    fun `TaxiStatusCombo has correct metadata`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "didi-sandbox",
                name = "didi",
                displayName = "滴滴打车",
                description = "DiDi taxi service",
                tier = McpServiceTier.STANDARD,
                config = MCPConfig.StreamableHttp("http://localhost:8080/didi"),
                defaultEnabled = true
            )
        ))
        
        val combo = TaxiStatusCombo(connectionManager, contextProvider, config)
        
        assertEquals("taxi_status", combo.name)
        assertEquals(listOf("didi-sandbox"), combo.requiredServices)
    }
    
    @Test
    fun `TaxiStatusCombo fails without last order`() = runTest {
        val combo = TaxiStatusCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
        assertTrue(result.content.contains("先叫车"))
    }
    
    // === CallTaxiCombo Tests ===
    
    @Test
    fun `CallTaxiCombo has correct metadata`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "didi-sandbox",
                name = "didi",
                displayName = "滴滴打车",
                description = "DiDi taxi service",
                tier = McpServiceTier.STANDARD,
                config = MCPConfig.StreamableHttp("http://localhost:8080/didi"),
                defaultEnabled = true
            )
        ))
        
        val combo = CallTaxiCombo(connectionManager, contextProvider, config)
        
        assertEquals("call_taxi", combo.name)
        assertTrue(combo.requiredServices.contains("amap-maps"))
        assertTrue(combo.requiredServices.contains("didi-sandbox"))
    }
    
    @Test
    fun `CallTaxiCombo fails without destination`() = runTest {
        val combo = CallTaxiCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
    }
    
    // === CancelTaxiCombo Tests ===
    
    @Test
    fun `CancelTaxiCombo has correct metadata`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "didi-sandbox",
                name = "didi",
                displayName = "滴滴打车",
                description = "DiDi taxi service",
                tier = McpServiceTier.STANDARD,
                config = MCPConfig.StreamableHttp("http://localhost:8080/didi"),
                defaultEnabled = true
            )
        ))
        
        val combo = CancelTaxiCombo(connectionManager, contextProvider, config)
        
        assertEquals("cancel_taxi", combo.name)
        assertEquals(listOf("didi-sandbox"), combo.requiredServices)
    }
    
    @Test
    fun `CancelTaxiCombo fails without last order`() = runTest {
        val combo = CancelTaxiCombo(connectionManager, contextProvider, config)
        
        val result = combo.execute(emptyMap())
        
        assertFalse(result.success)
        assertTrue(result.content.contains("先叫车"))
    }
    
    // === ComboTool base class Tests ===
    
    @Test
    fun `ComboTool toTool creates correct Tool object`() {
        val combo = FindNearbyCombo(connectionManager, contextProvider, config)
        
        val tool = combo.toTool()
        
        assertEquals("find_nearby", tool.function.name)
        assertEquals(combo.description, tool.function.description)
        assertEquals(config.comboPriority, tool.priority)
    }
    
    @Test
    fun `ComboToolResult success creates correct result`() {
        val result = ComboToolResult.success("test content", "raw json")
        
        assertTrue(result.success)
        assertEquals("test content", result.content)
        assertEquals("raw json", result.rawResponse)
    }
    
    @Test
    fun `ComboToolResult failure creates correct result with friendly error`() {
        val result = ComboToolResult.failure("Connection refused")
        
        assertFalse(result.success)
        // OutputDistiller should convert to friendly message
        assertTrue(result.content.contains("服务"))
    }
}

// === Fake Implementation ===

private class FakeContextProvider : ContextProvider {
    var mockLocation: Location? = createMockLocation()
    var mockHasPermission: Boolean = true
    var mockPreferences = UserPreferences()
    
    override fun getCurrentLocation(): Location? = mockLocation
    override fun getCurrentTime(): Instant = Instant.parse("2025-01-01T12:00:00Z")
    override fun getUserPreferences(): UserPreferences = mockPreferences
    override fun hasLocationPermission(): Boolean = mockHasPermission
    
    private fun createMockLocation(): Location {
        return Location("mock").apply {
            latitude = 39.9087
            longitude = 116.3975
        }
    }
}
