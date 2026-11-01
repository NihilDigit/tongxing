package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import com.nihildigit.agentcore.model.ToolType
import org.junit.Test
import org.junit.Assert.*

class MCPClientWrapperFilterTest {

    @Test
    fun testListToolsWithFilter() {
        // Create a mock ToolClient (we'll test the filtering logic)
        val client = TestToolClient()
        
        // Initially, all tools should be returned
        val allTools = client.listTools()
        assertEquals(3, allTools.size)
        
        // Apply a filter that only enables "tool1"
        val filter = ToolFilterConfig(enabledTools = setOf("tool1"))
        val filteredTools = client.listTools(filter)
        
        assertEquals(1, filteredTools.size)
        assertEquals("tool1", filteredTools[0].function.name)
    }

    @Test
    fun testSetToolFilter() {
        val client = TestToolClient()
        
        // Set a filter
        val filter = ToolFilterConfig(
            enabledTools = setOf("tool1", "tool2"),
            disabledTools = setOf("tool2") // tool2 should be disabled due to blacklist priority
        )
        
        client.setToolFilter(filter)
        assertEquals(filter, client.getToolFilter())
        
        // Test that the filter is applied
        val filteredTools = client.listTools()
        assertEquals(1, filteredTools.size)
        assertEquals("tool1", filteredTools[0].function.name)
    }

    // Test implementation of ToolClient for testing purposes
    private class TestToolClient : ToolClient {
        private var currentFilter: ToolFilterConfig = ToolFilterConfig()
        
        override fun listTools(): List<Tool> {
            // Apply current filter if set, otherwise return all tools
            val allTools = listOf(
                createTestTool("tool1"),
                createTestTool("tool2"),
                createTestTool("tool3")
            )
            
            return if (currentFilter != ToolFilterConfig()) {
                allTools.filter { 
                    currentFilter.isToolEnabled(it.function.name, ToolProvider.MCP_SERVICE)
                }
            } else {
                allTools
            }
        }
        
        override fun listTools(filter: ToolFilterConfig): List<Tool> {
            return listTools().filter { 
                filter.isToolEnabled(it.function.name, ToolProvider.MCP_SERVICE)
            }
        }
        
        override fun setToolFilter(filter: ToolFilterConfig) {
            currentFilter = filter
        }
        
        override fun getToolFilter(): ToolFilterConfig {
            return currentFilter
        }
        
        override suspend fun callTool(
            name: String,
            arguments: Map<String, Any?>,
            history: List<com.nihildigit.agentcore.model.ChatMessage>
        ): Result<String> {
            return Result.success("Test result for $name")
        }
        
        private fun createTestTool(name: String): Tool {
            return Tool(
                function = ToolFunction(
                    name = name,
                    description = "Test tool $name",
                    parameters = null
                ),
                toolType = ToolType.INTERMEDIATE
            )
        }
    }
}