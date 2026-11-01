package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import org.junit.Test
import org.junit.Assert.*

class ToolFilterTest {

    @Test
    fun testToolFilterConfig_defaultBehavior() {
        val filter = ToolFilterConfig()

        // Built-in tools should be enabled by default
        assertTrue(filter.isToolEnabled("describe_camera", ToolProvider.BUILT_IN))
        assertTrue(filter.isToolEnabled("ask_cloud", ToolProvider.BUILT_IN))

        // MCP tools should be disabled by default
        assertFalse(filter.isToolEnabled("get_weather", ToolProvider.MCP_SERVICE))
        assertFalse(filter.isToolEnabled("search_places", ToolProvider.MCP_SERVICE))
    }

    @Test
    fun testToolFilterConfig_whitelistMode() {
        val filter = ToolFilterConfig(
            enabledTools = setOf("get_weather", "search_places", "describe_camera")
        )

        // Only whitelisted tools should be enabled
        assertTrue(filter.isToolEnabled("get_weather", ToolProvider.MCP_SERVICE))
        assertTrue(filter.isToolEnabled("search_places", ToolProvider.MCP_SERVICE))
        assertFalse(filter.isToolEnabled("other_tool", ToolProvider.MCP_SERVICE))

        // Built-in tools should only be enabled if in whitelist when whitelist is non-empty
        assertTrue(filter.isToolEnabled("describe_camera", ToolProvider.BUILT_IN))
        assertFalse(filter.isToolEnabled("ask_cloud", ToolProvider.BUILT_IN)) // Not in whitelist
    }

    @Test
    fun testToolFilterConfig_blacklistPriority() {
        val filter = ToolFilterConfig(
            enabledTools = setOf("get_weather", "search_places"),
            disabledTools = setOf("get_weather") // Blacklist takes priority
        )
        
        // Blacklisted tool should be disabled even if in whitelist
        assertFalse(filter.isToolEnabled("get_weather", ToolProvider.MCP_SERVICE))
        
        // Other whitelisted tools should be enabled
        assertTrue(filter.isToolEnabled("search_places", ToolProvider.MCP_SERVICE))
    }

    @Test
    fun testToolWithProvider_creation() {
        val tool = Tool(
            function = ToolFunction(
                name = "test_tool",
                description = "Test tool",
                parameters = null
            )
        )
        
        val toolWithProvider = ToolWithProvider(
            tool = tool,
            provider = ToolProvider.MCP_SERVICE,
            serviceId = "test-service",
            serviceName = "Test Service"
        )
        
        assertEquals("test_tool", toolWithProvider.tool.function.name)
        assertEquals(ToolProvider.MCP_SERVICE, toolWithProvider.provider)
        assertEquals("test-service", toolWithProvider.serviceId)
        assertEquals("Test Service", toolWithProvider.serviceName)
    }

    @Test
    fun testToolProvider_defaultStrategy() {
        // Test that BUILT_IN tools default to enabled
        assertTrue(ToolFilterConfig().isToolEnabled("any_builtin", ToolProvider.BUILT_IN))
        
        // Test that MCP_SERVICE tools default to disabled
        assertFalse(ToolFilterConfig().isToolEnabled("any_mcp", ToolProvider.MCP_SERVICE))
    }
}
