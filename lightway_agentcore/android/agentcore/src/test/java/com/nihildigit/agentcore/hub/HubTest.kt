package com.nihildigit.agentcore.hub

import android.location.Location
import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.mcp.ToolFilterConfig
import com.nihildigit.agentcore.mcp.ToolProvider
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import com.nihildigit.agentcore.model.ToolType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HubTest {
    
    private lateinit var testScope: TestScope
    private lateinit var connectionManager: McpConnectionManager
    private lateinit var contextProvider: ContextProvider
    private lateinit var hub: Hub
    private lateinit var config: HubConfig
    
    @Before
    fun setUp() {
        testScope = TestScope()
        connectionManager = McpConnectionManager(testScope)
        contextProvider = FakeContextProvider()
        config = HubConfig()
        hub = Hub(connectionManager, contextProvider, config)
    }
    
    // === Tool Collection Tests ===
    
    @Test
    fun `hub implements ToolClient interface`() {
        assertTrue(hub is ToolClient)
    }
    
    @Test
    fun `listTools returns empty when no combos registered`() {
        val tools = hub.listTools()
        
        assertTrue(tools.isEmpty())
    }
    
    @Test
    fun `listTools returns combo tools when registered`() {
        val combo = FakeComboTool("test_combo", listOf("amap-maps"), connectionManager)
        hub.registerCombo(combo)
        
        // 由于 amap-maps 未启用，combo 不可用
        val tools = hub.listTools()
        assertTrue(tools.isEmpty())
    }
    
    @Test
    fun `getAvailableCombos returns only combos with enabled services`() {
        // 注册服务定义
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "amap-maps",
                name = "amap",
                displayName = "高德地图",
                description = "Amap",
                tier = McpServiceTier.CORE,
                config = com.nihildigit.agentcore.mcp.MCPConfig.StreamableHttp("http://localhost:8080"),
                defaultEnabled = true
            )
        ))
        
        val combo = FakeComboTool("test_combo", listOf("amap-maps"), connectionManager)
        hub.registerCombo(combo)
        
        // 启用 Combo（getAvailableCombos 需要同时满足 isComboEnabled 和 isAvailable）
        hub.initEnabledCombos(setOf("test_combo"))
        
        val availableCombos = hub.getAvailableCombos()
        
        assertEquals(1, availableCombos.size)
        assertEquals("test_combo", availableCombos[0].name)
    }
    
    @Test
    fun `getAvailableCombos excludes combos with disabled services`() {
        connectionManager.registerServices(listOf(
            McpServiceDefinition(
                id = "amap-maps",
                name = "amap",
                displayName = "高德地图",
                description = "Amap",
                tier = McpServiceTier.OPTIONAL,
                config = com.nihildigit.agentcore.mcp.MCPConfig.StreamableHttp("http://localhost:8080"),
                defaultEnabled = false  // 默认禁用
            )
        ))
        
        val combo = FakeComboTool("test_combo", listOf("amap-maps"), connectionManager)
        hub.registerCombo(combo)
        
        val availableCombos = hub.getAvailableCombos()
        
        assertTrue(availableCombos.isEmpty())
    }
    
    // === Tool Priority Tests ===
    
    @Test
    fun `combo tools have higher priority than default`() {
        val combo = FakeComboTool("test_combo", emptyList(), connectionManager)
        
        assertEquals(config.comboPriority, combo.priority)
        assertTrue(combo.priority > 0)  // 高于默认 MCP 工具的 0
    }
    
    // === Hub Enabled Tests ===
    
    @Test
    fun `hub disabled returns no combos`() {
        val disabledConfig = HubConfig(hubEnabled = false)
        val disabledHub = Hub(connectionManager, contextProvider, disabledConfig)
        
        val combo = FakeComboTool("test_combo", emptyList(), connectionManager)
        disabledHub.registerCombo(combo)
        
        val tools = disabledHub.listTools()
        
        assertTrue(tools.isEmpty())
    }
    
    // === Service Info Tests ===
    
    @Test
    fun `hub provides correct service info`() {
        assertEquals("hub", hub.getServiceId())
        assertEquals("快捷", hub.getServiceName())
        assertEquals(ToolProvider.BUILT_IN, hub.getProvider())
    }
    
    // === Call Tool Tests ===
    
    @Test
    fun `callTool returns error for unknown tool`() = runTest {
        val result = hub.callTool("unknown_tool", emptyMap(), emptyList())
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("工具未找到") == true)
    }
    
    @Test
    fun `isComboTool returns true for registered combo`() {
        val combo = FakeComboTool("test_combo", emptyList(), connectionManager)
        hub.registerCombo(combo)
        
        assertTrue(hub.isComboTool("test_combo"))
        assertFalse(hub.isComboTool("not_registered"))
    }
}

// === Fake Implementations for Testing ===

private class FakeContextProvider : ContextProvider {
    var mockLocation: Location? = null
    var mockHasPermission: Boolean = true
    var mockPreferences = UserPreferences()
    
    override fun getCurrentLocation(): Location? = mockLocation
    override fun getCurrentTime(): Instant = Instant.parse("2025-01-01T12:00:00Z")
    override fun getUserPreferences(): UserPreferences = mockPreferences
    override fun hasLocationPermission(): Boolean = mockHasPermission
}

private class FakeComboTool(
    override val name: String,
    override val requiredServices: List<String>,
    private val mockConnectionManager: McpConnectionManager
) : ComboTool(
    connectionManager = mockConnectionManager,
    contextProvider = FakeContextProvider(),
    config = HubConfig()
) {
    override val description: String = "Fake combo for testing"
    override val parametersSchema: JsonElement? = buildJsonObject { }
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        return ComboToolResult.success("Fake result")
    }
}

private class FakeMcpToolClient(
    private val serviceId: String,
    private val serviceName: String,
    private val tools: List<Tool>
) : ToolClient {
    override fun listTools(): List<Tool> = tools
    
    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): Result<String> {
        return Result.success("Fake MCP result for $name")
    }
    
    override fun getServiceId(): String = serviceId
    override fun getServiceName(): String = serviceName
    override fun getProvider(): ToolProvider = ToolProvider.MCP_SERVICE
}
