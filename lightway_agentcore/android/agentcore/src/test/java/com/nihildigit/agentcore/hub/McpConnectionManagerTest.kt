package com.nihildigit.agentcore.hub

import com.nihildigit.agentcore.mcp.MCPConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class McpConnectionManagerTest {
    
    private lateinit var testScope: TestScope
    private lateinit var manager: McpConnectionManager
    private val savedEnabledServices = mutableSetOf<String>()
    
    @Before
    fun setUp() {
        testScope = TestScope()
        savedEnabledServices.clear()
        manager = McpConnectionManager(
            scope = testScope,
            connectionTimeoutMs = 1000,
            enabledServicesProvider = { savedEnabledServices.toSet() },
            onEnabledServicesChanged = { savedEnabledServices.clear(); savedEnabledServices.addAll(it) }
        )
    }
    
    // === Service Registration Tests ===
    
    @Test
    fun `registerServices stores service definitions`() {
        val services = createTestServices()
        
        manager.registerServices(services)
        
        val statuses = manager.getServiceStatuses()
        assertEquals(3, statuses.size)
        assertTrue(statuses.any { it.serviceId == "core-service" })
        assertTrue(statuses.any { it.serviceId == "standard-service" })
        assertTrue(statuses.any { it.serviceId == "optional-service" })
    }
    
    @Test
    fun `registerServices initializes all services as DISCONNECTED`() {
        manager.registerServices(createTestServices())
        
        val statuses = manager.getServiceStatuses()
        assertTrue(statuses.all { it.state == McpConnectionState.DISCONNECTED })
    }
    
    @Test
    fun `registerServices respects defaultEnabled settings`() {
        manager.registerServices(createTestServices())
        
        val statuses = manager.getServiceStatuses()
        assertTrue(statuses.first { it.serviceId == "core-service" }.enabled)
        assertTrue(statuses.first { it.serviceId == "standard-service" }.enabled)
        assertFalse(statuses.first { it.serviceId == "optional-service" }.enabled)
    }
    
    @Test
    fun `registerServices uses saved enabled services when available`() {
        savedEnabledServices.add("optional-service")
        
        manager.registerServices(createTestServices())
        
        val statuses = manager.getServiceStatuses()
        assertTrue(statuses.first { it.serviceId == "optional-service" }.enabled)
        // Only saved service should be enabled
        assertFalse(statuses.first { it.serviceId == "core-service" }.enabled)
    }
    
    // === Service Enable/Disable Tests ===
    
    @Test
    fun `setServiceEnabled true adds service to enabled set`() {
        manager.registerServices(createTestServices())
        
        manager.setServiceEnabled("optional-service", true)
        
        assertTrue(manager.isServiceEnabled("optional-service"))
        assertTrue(savedEnabledServices.contains("optional-service"))
    }
    
    @Test
    fun `setServiceEnabled false removes service from enabled set`() {
        manager.registerServices(createTestServices())
        
        manager.setServiceEnabled("core-service", false)
        testScope.advanceUntilIdle()
        
        assertFalse(manager.isServiceEnabled("core-service"))
        assertFalse(savedEnabledServices.contains("core-service"))
    }
    
    @Test
    fun `setServiceEnabled notifies state listener`() {
        manager.registerServices(createTestServices())
        
        var notifiedServiceId: String? = null
        var notifiedState: McpConnectionState? = null
        manager.setStateListener(object : McpConnectionStateListener {
            override fun onConnectionStateChanged(serviceId: String, state: McpConnectionState) {
                notifiedServiceId = serviceId
                notifiedState = state
            }
        })
        
        manager.setServiceEnabled("optional-service", true)
        
        assertEquals("optional-service", notifiedServiceId)
        assertEquals(McpConnectionState.DISCONNECTED, notifiedState)
    }
    
    // === Enabled Services Tests ===
    
    @Test
    fun `isServiceEnabled returns true for default enabled services`() {
        manager.registerServices(createTestServices())
        
        assertTrue(manager.isServiceEnabled("core-service"))
        assertTrue(manager.isServiceEnabled("standard-service"))
        assertFalse(manager.isServiceEnabled("optional-service"))
    }
    
    @Test
    fun `isServiceEnabled returns false for unknown service`() {
        manager.registerServices(createTestServices())
        
        assertFalse(manager.isServiceEnabled("unknown-service"))
    }
    
    // === Lazy Connection Tests ===
    
    @Test
    fun `ensureConnected returns null for disabled service`() = runTest {
        manager.registerServices(createTestServices())
        
        val client = manager.ensureConnected("optional-service")
        
        assertNull(client)
    }
    
    @Test
    fun `ensureConnected returns null for unknown service`() = runTest {
        manager.registerServices(createTestServices())
        
        val client = manager.ensureConnected("unknown-service")
        
        assertNull(client)
    }
    
    // === Status Flow Tests ===
    
    @Test
    fun `statusFlow updates when services registered`() {
        val initialStatuses = manager.statusFlow.value
        assertTrue(initialStatuses.isEmpty())
        
        manager.registerServices(createTestServices())
        
        val updatedStatuses = manager.statusFlow.value
        assertEquals(3, updatedStatuses.size)
    }
    
    @Test
    fun `statusFlow updates when service enabled state changes`() {
        manager.registerServices(createTestServices())
        val beforeChange = manager.statusFlow.value.first { it.serviceId == "optional-service" }
        assertFalse(beforeChange.enabled)
        
        manager.setServiceEnabled("optional-service", true)
        
        val afterChange = manager.statusFlow.value.first { it.serviceId == "optional-service" }
        assertTrue(afterChange.enabled)
    }
    
    // === Service Status Tests ===
    
    @Test
    fun `getServiceStatuses returns correct tier information`() {
        manager.registerServices(createTestServices())
        
        val statuses = manager.getServiceStatuses()
        
        assertEquals(McpServiceTier.CORE, statuses.first { it.serviceId == "core-service" }.tier)
        assertEquals(McpServiceTier.STANDARD, statuses.first { it.serviceId == "standard-service" }.tier)
        assertEquals(McpServiceTier.OPTIONAL, statuses.first { it.serviceId == "optional-service" }.tier)
    }
    
    @Test
    fun `getServiceStatuses returns correct display names`() {
        manager.registerServices(createTestServices())
        
        val statuses = manager.getServiceStatuses()
        
        assertEquals("Core Service", statuses.first { it.serviceId == "core-service" }.serviceName)
        assertEquals("Standard Service", statuses.first { it.serviceId == "standard-service" }.serviceName)
        assertEquals("Optional Service", statuses.first { it.serviceId == "optional-service" }.serviceName)
    }
    
    // === Connected Clients Tests ===
    
    @Test
    fun `getConnectedClients returns empty list initially`() {
        manager.registerServices(createTestServices())
        
        val clients = manager.getConnectedClients()
        
        assertTrue(clients.isEmpty())
    }
    
    // === State Listener Tests ===
    
    @Test
    fun `setStateListener can set and clear listener`() {
        manager.registerServices(createTestServices())
        
        var callCount = 0
        val listener = object : McpConnectionStateListener {
            override fun onConnectionStateChanged(serviceId: String, state: McpConnectionState) {
                callCount++
            }
        }
        
        manager.setStateListener(listener)
        manager.setServiceEnabled("optional-service", true)
        assertEquals(1, callCount)
        
        manager.setStateListener(null)
        manager.setServiceEnabled("optional-service", false)
        testScope.advanceUntilIdle()
        // Listener was cleared, count should not increase
        assertEquals(1, callCount)
    }
    
    // === Connection Status Emoji Tests ===
    
    @Test
    fun `McpConnectionStatus getStateEmoji returns correct emoji`() {
        val disconnectedEnabled = McpConnectionStatus(
            serviceId = "test",
            serviceName = "Test",
            tier = McpServiceTier.CORE,
            state = McpConnectionState.DISCONNECTED,
            enabled = true
        )
        assertEquals("🟡", disconnectedEnabled.getStateEmoji())
        
        val connectedEnabled = disconnectedEnabled.copy(state = McpConnectionState.CONNECTED)
        assertEquals("🟢", connectedEnabled.getStateEmoji())
        
        val connectingEnabled = disconnectedEnabled.copy(state = McpConnectionState.CONNECTING)
        assertEquals("🟡", connectingEnabled.getStateEmoji())
        
        val failedEnabled = disconnectedEnabled.copy(state = McpConnectionState.FAILED)
        assertEquals("🔴", failedEnabled.getStateEmoji())
        
        val disconnectedDisabled = disconnectedEnabled.copy(enabled = false)
        assertEquals("⚫", disconnectedDisabled.getStateEmoji())
    }
    
    // === Helper Methods ===
    
    private fun createTestServices(): List<McpServiceDefinition> = listOf(
        McpServiceDefinition(
            id = "core-service",
            name = "core",
            displayName = "Core Service",
            description = "A core service",
            tier = McpServiceTier.CORE,
            config = MCPConfig.StreamableHttp("http://localhost:8080/core"),
            defaultEnabled = true
        ),
        McpServiceDefinition(
            id = "standard-service",
            name = "standard",
            displayName = "Standard Service",
            description = "A standard service",
            tier = McpServiceTier.STANDARD,
            config = MCPConfig.StreamableHttp("http://localhost:8080/standard"),
            defaultEnabled = true
        ),
        McpServiceDefinition(
            id = "optional-service",
            name = "optional",
            displayName = "Optional Service",
            description = "An optional service",
            tier = McpServiceTier.OPTIONAL,
            config = MCPConfig.StreamableHttp("http://localhost:8080/optional"),
            defaultEnabled = false
        )
    )
}
