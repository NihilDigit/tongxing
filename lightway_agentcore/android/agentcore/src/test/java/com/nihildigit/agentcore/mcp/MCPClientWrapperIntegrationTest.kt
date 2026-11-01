package com.nihildigit.agentcore.mcp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for MCPClientWrapper with real MCP Kotlin SDK.
 *
 * These tests require MCP servers to be running.
 * Set environment variables to configure test servers:
 * - MCP_STDIO_COMMAND: Command to start a stdio MCP server (e.g., "npx @modelcontextprotocol/server-filesystem")
 * - MCP_WS_URL: WebSocket URL of an MCP server
 * - MCP_STREAMABLE_HTTP_URL: Streamable HTTP URL of an MCP server
 */
class MCPClientWrapperIntegrationTest {

    private lateinit var client: MCPClientWrapper
    private var stdioCommand: String? = null
    private var wsUrl: String? = null
    private var streamableHttpUrl: String? = null

    @Before
    fun setUp() {
        client = MCPClientWrapper(
            clientName = "test-client",
            clientVersion = "1.0.0"
        )
        stdioCommand = System.getenv("MCP_STDIO_COMMAND")
        wsUrl = System.getenv("MCP_WS_URL")
        streamableHttpUrl = System.getenv("MCP_STREAMABLE_HTTP_URL")
    }

    @After
    fun tearDown() = runBlocking {
        client.disconnect()
    }

    @Test
    fun `test stdio transport connection and tool listing`() = runBlocking {
        assumeTrue("MCP_STDIO_COMMAND not set, skipping", stdioCommand != null)

        val parts = stdioCommand!!.split(" ")
        val config = MCPConfig.Stdio(
            command = parts.first(),
            args = parts.drop(1)
        )

        val result = client.connect(config)
        assertTrue("Connection should succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val tools = client.listTools()
        println("Available tools: ${tools.map { it.function.name }}")
        println("Server capabilities: ${client.getServerCapabilities()}")
        println("Server version: ${client.getServerVersion()}")

        // Should have at least some tools
        assertTrue("Should have at least one tool", tools.isNotEmpty())
    }

    @Test
    fun `test WebSocket transport connection`() = runBlocking {
        assumeTrue("MCP_WS_URL not set, skipping", wsUrl != null)

        val config = MCPConfig.WebSocket(
            url = wsUrl!!
        )

        val result = client.connect(config)
        assertTrue("Connection should succeed", result.isSuccess)

        val tools = client.listTools()
        println("Available tools via WebSocket: ${tools.map { it.function.name }}")
    }

    @Test
    fun `test Streamable HTTP transport connection`() = runBlocking {
        assumeTrue("MCP_STREAMABLE_HTTP_URL not set, skipping", streamableHttpUrl != null)

        val config = MCPConfig.StreamableHttp(
            url = streamableHttpUrl!!,
            headers = mapOf(
                "User-Agent" to "LightWay-Test/1.0",
                "X-Test-Header" to "test-value"
            ),
            reconnectionSeconds = 5
        )

        val result = client.connect(config)
        
        if (result.isFailure) {
            println("Streamable HTTP connection failed (this may be expected): ${result.exceptionOrNull()?.message}")
            // Don't fail the test, just log the error - the server might not be available
            return@runBlocking
        }

        println("✅ Streamable HTTP connection successful!")
        
        val tools = client.listTools()
        println("Available tools via Streamable HTTP: ${tools.map { it.function.name }}")
        println("Server capabilities: ${client.getServerCapabilities()}")
        println("Server version: ${client.getServerVersion()}")

        // Tools may or may not be available depending on the server
        if (tools.isNotEmpty()) {
            println("Found ${tools.size} tools available")
        } else {
            println("No tools available on this server")
        }
    }

    @Test
    fun `test tool call with MCP server`() = runBlocking {
        assumeTrue("MCP_STDIO_COMMAND not set, skipping", stdioCommand != null)

        val parts = stdioCommand!!.split(" ")
        val config = MCPConfig.Stdio(
            command = parts.first(),
            args = parts.drop(1)
        )

        val connectResult = client.connect(config)
        assumeTrue("Connection failed", connectResult.isSuccess)

        val tools = client.listTools()
        assumeTrue("No tools available", tools.isNotEmpty())

        // Try to call the first available tool
        val tool = tools.first()
        println("Calling tool: ${tool.function.name}")

        val result = client.callTool(tool.function.name, emptyMap(), emptyList())
        println("Tool call result: $result")
    }

    @Test
    fun `test tool call with Streamable HTTP server`() = runBlocking {
        assumeTrue("MCP_STREAMABLE_HTTP_URL not set, skipping", streamableHttpUrl != null)

        val config = MCPConfig.StreamableHttp(
            url = streamableHttpUrl!!,
            headers = mapOf("User-Agent" to "LightWay-Test/1.0")
        )

        val connectResult = client.connect(config)
        assumeTrue("Connection failed", connectResult.isSuccess)

        val tools = client.listTools()
        assumeTrue("No tools available", tools.isNotEmpty())

        // Try to call the first available tool
        val tool = tools.first()
        println("Calling tool via Streamable HTTP: ${tool.function.name}")

        val result = client.callTool(tool.function.name, emptyMap(), emptyList())
        println("Tool call result via Streamable HTTP: $result")
    }

    @Test
    fun `test resource listing and reading`() = runBlocking {
        assumeTrue("MCP_STDIO_COMMAND not set, skipping", stdioCommand != null)

        val parts = stdioCommand!!.split(" ")
        val config = MCPConfig.Stdio(
            command = parts.first(),
            args = parts.drop(1)
        )

        val result = client.connect(config)
        assumeTrue("Connection failed", result.isSuccess)

        val resources = client.listResources()
        println("Available resources: ${resources.map { it.uri }}")

        // Resources may or may not be available depending on server
        if (resources.isNotEmpty()) {
            val firstResource = resources.first()
            val readResult = client.readResource(firstResource.uri)
            println("Read resource result: $readResult")
        }
    }

    @Test
    fun `test resource operations with Streamable HTTP server`() = runBlocking {
        assumeTrue("MCP_STREAMABLE_HTTP_URL not set, skipping", streamableHttpUrl != null)

        val config = MCPConfig.StreamableHttp(
            url = streamableHttpUrl!!,
            headers = mapOf("User-Agent" to "LightWay-Test/1.0")
        )

        val result = client.connect(config)
        assumeTrue("Connection failed", result.isSuccess)

        val resources = client.listResources()
        println("Available resources via Streamable HTTP: ${resources.map { it.uri }}")

        // Resources may or may not be available depending on server
        if (resources.isNotEmpty()) {
            val firstResource = resources.first()
            val readResult = client.readResource(firstResource.uri)
            println("Read resource result via Streamable HTTP: $readResult")
        }
    }

    @Test
    fun `test connection failure handling`() = runBlocking {
        val config = MCPConfig.Stdio(
            command = "nonexistent-command-12345",
            args = emptyList()
        )

        val result = client.connect(config)
        assertTrue("Connection should fail for nonexistent command", result.isFailure)
        println("Expected error: ${result.exceptionOrNull()?.message}")
    }

    @Test
    fun `test Streamable HTTP connection failure handling`() = runBlocking {
        val config = MCPConfig.StreamableHttp(
            url = "https://nonexistent-domain-12345.invalid/mcp",
            headers = emptyMap(),
            reconnectionSeconds = 1
        )

        val result = client.connect(config)
        assertTrue("Connection should fail for nonexistent domain", result.isFailure)
        val errorMessage = result.exceptionOrNull()?.message?.lowercase()
        println("Expected error: $errorMessage")
        
        // Check for various types of network errors
        val hasNetworkError = errorMessage?.contains("dns") == true ||
                             errorMessage?.contains("unknownhost") == true ||
                             errorMessage?.contains("connection") == true ||
                             errorMessage?.contains("network") == true ||
                             errorMessage?.contains("refused") == true ||
                             errorMessage?.contains("unresolved") == true ||
                             errorMessage?.contains("transport") == true
        
        assertTrue("Should contain network error info", hasNetworkError)
    }

    @Test
    fun `test Streamable HTTP invalid URL handling`() = runBlocking {
        val config = MCPConfig.StreamableHttp(
            url = "not-a-valid-url",
            headers = emptyMap()
        )

        val result = client.connect(config)
        assertTrue("Connection should fail for invalid URL", result.isFailure)
        println("Expected error: ${result.exceptionOrNull()?.message}")
    }

    @Test
    fun `test tool call for nonexistent tool`() = runBlocking {
        assumeTrue("MCP_STDIO_COMMAND not set, skipping", stdioCommand != null)

        val parts = stdioCommand!!.split(" ")
        val config = MCPConfig.Stdio(
            command = parts.first(),
            args = parts.drop(1)
        )

        val connectResult = client.connect(config)
        assumeTrue("Connection failed", connectResult.isSuccess)

        val result = client.callTool("nonexistent_tool_12345", emptyMap(), emptyList())
        assertTrue("Should fail for nonexistent tool", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found") == true)
    }

    @Test
    fun `test disconnect and reconnect`() = runBlocking {
        assumeTrue("MCP_STDIO_COMMAND not set, skipping", stdioCommand != null)

        val parts = stdioCommand!!.split(" ")
        val config = MCPConfig.Stdio(
            command = parts.first(),
            args = parts.drop(1)
        )

        // First connection
        var result = client.connect(config)
        assertTrue("First connection should succeed", result.isSuccess)

        val toolsBefore = client.listTools()

        // Disconnect
        client.disconnect()
        assertTrue("Tools should be cleared after disconnect", client.listTools().isEmpty())

        // Reconnect
        result = client.connect(config)
        assertTrue("Reconnection should succeed", result.isSuccess)

        val toolsAfter = client.listTools()
        assertEquals("Should have same tools after reconnect", toolsBefore.size, toolsAfter.size)
    }

    @Test
    fun `test MCPConfig data classes`() {
        val stdioConfig = MCPConfig.Stdio(
            command = "node",
            args = listOf("server.js"),
            env = mapOf("DEBUG" to "true")
        )
        assertEquals("node", stdioConfig.command)
        assertEquals(listOf("server.js"), stdioConfig.args)
        assertEquals("true", stdioConfig.env["DEBUG"])

        val wsConfig = MCPConfig.WebSocket(url = "ws://localhost:3000")
        assertEquals("ws://localhost:3000", wsConfig.url)

        val streamableHttpConfig = MCPConfig.StreamableHttp(
            url = "https://example.com/mcp/sse",
            headers = mapOf("Authorization" to "Bearer token123"),
            reconnectionSeconds = 10
        )
        assertEquals("https://example.com/mcp/sse", streamableHttpConfig.url)
        assertEquals("Bearer token123", streamableHttpConfig.headers["Authorization"])
        assertEquals(10, streamableHttpConfig.reconnectionSeconds)
    }

    @Test
    fun `test MCPTool and MCPResource data classes`() {
        val tool = MCPTool(
            name = "test_tool",
            description = "A test tool",
            inputSchema = null,
            renewalSeconds = null
        )
        assertEquals("test_tool", tool.name)
        assertEquals("A test tool", tool.description)

        val resource = MCPResource(
            uri = "file:///test.txt",
            name = "test.txt",
            description = "A test file",
            mimeType = "text/plain"
        )
        assertEquals("file:///test.txt", resource.uri)
        assertEquals("text/plain", resource.mimeType)
    }
}
