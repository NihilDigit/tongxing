package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.headers
import kotlin.time.Duration.Companion.seconds
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.InitializeRequest
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.util.concurrent.ConcurrentHashMap

// Simple debug flag - in real implementation this would come from BuildConfig
private const val DEBUG_BUILD = true

/**
 * MCP Client Wrapper using official MCP Kotlin SDK.
 * Supports stdio, WebSocket, and Streamable HTTP (SSE) transports.
 */
class MCPClientWrapper(
    private val clientName: String = "agentcore-client",
    private val clientVersion: String = "1.0.0"
): ToolClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var client: Client? = null
    private var process: Process? = null
    private var ktorClient: HttpClient? = null
    // 标记是否使用共享 HttpClient，disconnect 时不应关闭共享实例
    private var usesSharedClient: Boolean = false
    private val tools = ConcurrentHashMap<String, MCPTool>()
    private val resources = ConcurrentHashMap<String, MCPResource>()
    private var toolFilter: ToolFilterConfig = ToolFilterConfig()
    private var serviceId: String? = null
    private var serviceName: String? = null
    // 工具调用超时时间（毫秒），由 McpConnectionManager 设置
    private var toolCallTimeoutMs: Long = 30_000

    companion object {
        private const val TAG = "MCPClientWrapper"
        private const val DEFAULT_TOOL_CALL_TIMEOUT_MS = 30_000L
    }

    /**
     * Connect to an MCP server.
     */
    suspend fun connect(config: MCPConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            logI("[MCP] 连接: type=${config.javaClass.simpleName}")
            client = Client(
                clientInfo = Implementation(
                    name = clientName,
                    version = clientVersion
                )
            )

            when (config) {
                is MCPConfig.Stdio -> {
                    if (DEBUG_BUILD) {
                        logD("[MCP] 传输方式: STDIO")
                    }
                    connectStdio(config)
                }
                is MCPConfig.WebSocket -> {
                    if (DEBUG_BUILD) {
                        logD("[MCP] 传输方式: WebSocket")
                    }
                    connectWebSocket(config)
                }
                is MCPConfig.StreamableHttp -> {
                    if (DEBUG_BUILD) {
                        logD("[MCP] 传输方式: Streamable HTTP (SSE)")
                    }
                    connectStreamableHttp(config)
                }
            }

            // Refresh tools and resources after connection
            if (DEBUG_BUILD) {
                logD("[MCP] 刷新工具和资源...")
            }
            refreshTools()
            refreshResources()
            logI("[MCP] 连接成功: ${tools.size} 个工具, ${resources.size} 个资源")

            Result.success(Unit)
        } catch (e: Exception) {
            val safeDetail = redactUrlsInMessage(e.message)
            logE("[MCP] 连接失败: ${e.javaClass.simpleName}")
            Result.failure(MCPException("连接失败: $safeDetail"))
        }
    }

    private suspend fun connectStdio(config: MCPConfig.Stdio) {
        val pb = ProcessBuilder(listOf(config.command) + config.args)
        pb.environment().putAll(config.env)
        pb.redirectErrorStream(false)

        process = pb.start()

        val transport = StdioClientTransport(
            input = process!!.inputStream.asSource().buffered(),
            output = process!!.outputStream.asSink().buffered()
        )

        client?.connect(transport)
    }

    private suspend fun connectWebSocket(config: MCPConfig.WebSocket) {
        // 使用共享的 WebSocket HttpClient
        ktorClient = SharedHttpClientProvider.getWebSocketClient()
        usesSharedClient = true

        val transport = WebSocketClientTransport(
            client = ktorClient!!,
            urlString = config.url
        )

        client?.connect(transport)
    }

    private suspend fun connectStreamableHttp(config: MCPConfig.StreamableHttp) {
        try {
            logI("[MCP] Streamable HTTP URL: ${redactMcpUrl(config.url)}")
            if (DEBUG_BUILD) {
                logD("[MCP] Header keys: ${config.headers.keys}")
                logD("[MCP] 重连间隔: ${config.reconnectionSeconds}s")
                logD("[MCP] ModelScope 兼容模式: ${config.modelScopeCompat}")
                logD("[MCP] Plain HTTP 模式: ${config.plainHttpMode}")
            }

            // 使用共享的 SSE HttpClient（优化连接池复用）
            ktorClient = SharedHttpClientProvider.getSseClient()
            usesSharedClient = true
            if (DEBUG_BUILD) {
                logD("[MCP] HttpClient: 使用共享 SSE HttpClient")
            }

            val transport = when {
                config.plainHttpMode -> {
                    // Plain HTTP JSON-RPC mode for servers like DiDi that don't use SSE
                    logI("[MCP] 使用 Plain HTTP JSON-RPC 传输 (同步响应模式)")
                    PlainHttpTransport(
                        client = ktorClient!!,
                        url = config.url,
                        customHeaders = config.headers
                    )
                }
                config.modelScopeCompat -> {
                    logI("[MCP] 使用 ModelScope 兼容传输 (分读写通道)")
                    @Suppress("DEPRECATION")
                    ModelScopeStreamableHttpTransport(
                        client = ktorClient!!,
                        sseUrl = config.url,
                        customHeaders = config.headers,
                        reconnectionTime = config.reconnectionSeconds.seconds
                    )
                }
                else -> {
                    if (DEBUG_BUILD) {
                        logD("[MCP] 使用标准 Streamable HTTP 传输")
                    }
                    StreamableHttpClientTransport(
                        client = ktorClient!!,
                        url = config.url,
                        reconnectionTime = config.reconnectionSeconds.seconds,
                        requestBuilder = {
                            config.headers.forEach { (key, value) ->
                                headers.append(key, value)
                            }
                            if (DEBUG_BUILD) {
                                logD("[MCP] 请求头: ${headers.names()}")
                            }
                        }
                    )
                }
            }
            if (DEBUG_BUILD) {
                logD("[MCP] 传输层创建完成，正在连接...")
            }

            try {
                logI("[MCP] 调用 client.connect(transport)...")
                client?.connect(transport)
                logI("[MCP] Streamable HTTP 连接成功")
            } catch (connectError: Exception) {
                logE("[MCP] client.connect() 失败: ${connectError.javaClass.simpleName}")
                throw connectError
            }
        } catch (e: Exception) {
            logE("[MCP] Streamable HTTP 连接错误: ${e.javaClass.simpleName}")
            if (DEBUG_BUILD) {
                logE("[MCP] 异常类型: ${e.javaClass.simpleName}")
                logE("[MCP] 消息: <redacted>")
                e.cause?.let { cause ->
                    logE("[MCP] 原因: ${cause.javaClass.simpleName}")
                }
            }

            // Wrap connection errors with detailed information
            val safeDetail = redactUrlsInMessage(e.message)
            val errorMessage = when (e) {
                is java.net.UnknownHostException -> "DNS 解析失败: $safeDetail"
                is java.net.ConnectException -> "连接被拒绝: $safeDetail"
                is java.net.SocketTimeoutException -> "连接超时: $safeDetail"
                is ResponseException -> "HTTP ${e.response.status.value}: ${getHttpStatusMessage(e.response.status.value)}"
                else -> extractHttpStatusCode(e)?.let { "HTTP $it: ${getHttpStatusMessage(it)}" }
                    ?: "Streamable HTTP 连接失败: $safeDetail"
            }
            logE("[MCP] 包装错误: ${e.javaClass.simpleName}")
            throw MCPException(errorMessage)
        }
    }

    private fun extractHttpStatusCode(exception: Exception): Int? {
        // Extract HTTP status code from exception if available
        val message = exception.message ?: return null
        val regex = Regex("""HTTP/\d\.\d\s+(\d{3})""")
        return regex.find(message)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun getHttpStatusMessage(code: Int): String = when (code) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Unknown Error"
    }

    /**
     * Disconnect from the MCP server.
     * 
     * Note: 共享的 HttpClient 不会被关闭，由 SharedHttpClientProvider.shutdown() 统一管理。
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        // 只关闭非共享的 HttpClient
        if (!usesSharedClient) {
            try {
                ktorClient?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }

        try {
                process?.let { p ->
                    p.destroy()
                    // Wait briefly for graceful shutdown, then force kill
                    if (p.isAlive) {
                        delay(100)
                        if (p.isAlive) {
                            p.destroyForcibly()
                        }
                    }
                }
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        client = null
        ktorClient = null
        usesSharedClient = false
        process = null
        tools.clear()
        resources.clear()
    }

    /**
     * List available tools as OpenAI-compatible format.
     */
    override fun listTools(): List<Tool> {
        return tools.values.map { mcpTool ->
            val toolType = when (mcpTool.name) {
                "describe_camera", "describe_screen" -> ToolType.ACTION
                else -> ToolType.INTERMEDIATE  // Default for all other tools
            }

            Tool(
                type = "function",
                function = ToolFunction(
                    name = mcpTool.name,
                    description = mcpTool.description,
                    parameters = mcpTool.inputSchema
                ),
                toolType = toolType,
                renewalSeconds = mcpTool.renewalSeconds ?: -1
            )
        }
    }
    
    /**
     * List available tools with filtering.
     */
    override fun listTools(filter: ToolFilterConfig): List<Tool> {
        val allTools = listTools()
        return allTools.filter { filter.isToolEnabled(it.function.name, ToolProvider.MCP_SERVICE) }
    }
    
    /**
     * Set tool filter configuration.
     */
    override fun setToolFilter(filter: ToolFilterConfig) {
        this.toolFilter = filter
    }
    
    /**
     * Get current tool filter configuration.
     */
    override fun getToolFilter(): ToolFilterConfig = toolFilter
    
    /**
     * Set service identification for UI display.
     */
    fun setServiceInfo(id: String, name: String) {
        this.serviceId = id
        this.serviceName = name
    }
    
    /**
     * 设置工具调用超时时间
     * 
     * @param timeoutMs 超时时间（毫秒），用于 callTool() 的 withTimeout 保护
     */
    fun setToolCallTimeoutMs(timeoutMs: Long) {
        this.toolCallTimeoutMs = timeoutMs
    }
    
    override fun getServiceId(): String? = serviceId
    
    override fun getServiceName(): String? = serviceName

    /**
     * Call a tool by name with arguments.
     * 
     * 工具调用会受到 toolCallTimeoutMs 的超时保护，
     * 超时后返回 "工具调用超时（X 秒）" 错误。
     */
    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val c = client ?: return@withContext Result.failure(
                    MCPException("未连接到 MCP 服务器")
                )

                if (!tools.containsKey(name)) {
                    return@withContext Result.failure(
                        MCPException("工具未找到: $name")
                    )
                }

                // 使用 withTimeout 保护工具调用
                val result = withTimeout(toolCallTimeoutMs) {
                    c.callTool(
                        name = name,
                        arguments = arguments,
                        options = RequestOptions()
                    )
                }

                // Extract text content from result
                val content = result.content.firstOrNull()?.let { contentItem ->
                    when {
                        contentItem is TextContent -> contentItem.text
                        else -> contentItem.toString()
                    }
                } ?: "无内容返回"

                Result.success(content)
            } catch (e: TimeoutCancellationException) {
                val timeoutSeconds = toolCallTimeoutMs / 1000
                Result.failure(MCPException("工具调用超时（$timeoutSeconds 秒）"))
            } catch (e: Exception) {
                Result.failure(MCPException("工具调用失败: ${redactUrlsInMessage(e.message)}"))
            }
        }

    /**
     * List available resources.
     */
    fun listResources(): List<MCPResource> {
        return resources.values.toList()
    }

    /**
     * Read a resource by URI.
     */
    suspend fun readResource(uri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val c = client ?: return@withContext Result.failure(
                MCPException("未连接到 MCP 服务器")
            )

            val result = c.readResource(
                request = ReadResourceRequest(
                    params = ReadResourceRequestParams(uri = uri)
                )
            )

            val content = result.contents.firstOrNull()?.let { resourceContent ->
                when (resourceContent) {
                    is TextResourceContents -> resourceContent.text
                    is BlobResourceContents -> "[二进制:${resourceContent.blob.length} 字符]"
                    else -> resourceContent.toString()
                }
            } ?: "无内容返回"

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(MCPException("资源读取失败: ${e.message}", e))
        }
    }

    /**
     * Refresh the tools list from the server.
     */
    private suspend fun refreshTools() {
        val c = client ?: return

        try {
            val result = c.listTools()

            tools.clear()
            for (tool in result.tools) {
                val inputSchemaJson = tool.inputSchema?.let { schema ->
                    runCatching { json.encodeToJsonElement(schema) }
                        .getOrElse { error ->
                            AgentLogger.verbose("MCP", "ClientWrapper", "tool schema encoding failed", "tool" to tool.name, "error" to (error.message ?: "unknown"))
                            null
                        }
                }
                
                val renewalSeconds = inputSchemaJson
                    ?.jsonObject
                    ?.get("x-renewal-seconds")
                    ?.jsonPrimitive
                    ?.intOrNull

                val compacted = SchemaCompactor.compactTool(
                    description = tool.description ?: "",
                    schema = inputSchemaJson
                )

                tools[tool.name] = MCPTool(
                    name = tool.name,
                    description = compacted.description,
                    inputSchema = compacted.schema,
                    renewalSeconds = renewalSeconds
                )
                
                // DEBUG: 打印每个工具的详细信息
                if (DEBUG_BUILD) {
                    logI("┌─ Tool: ${tool.name}")
                    logI("│  Description: ${compacted.description.ifBlank { "(none)" }}")
                    logI("│  Schema: ${compacted.schema?.toString()?.take(500) ?: "(none)"}")
                    logI("└─")
                }
            }
        } catch (e: Exception) {
            // Tools list may not be supported by this server
            AgentLogger.error("MCP", "ClientWrapper", "failed to list tools (${e.javaClass.simpleName})")
        }
    }

    /**
     * Refresh the resources list from the server.
     */
    private suspend fun refreshResources() {
        val c = client ?: return

        try {
            val result = c.listResources(
                request = ListResourcesRequest(
                    params = PaginatedRequestParams(cursor = null)
                )
            )

            resources.clear()
            for (resource in result.resources) {
                resources[resource.uri] = MCPResource(
                    uri = resource.uri,
                    name = resource.name,
                    description = resource.description,
                    mimeType = resource.mimeType
                )
            }
        } catch (e: Exception) {
            // Many MCP servers do not implement resources; do not treat as an error.
            AgentLogger.verbose("MCP", "ClientWrapper", "resources not supported", "error" to redactUrlsInMessage(e.message))
        }
    }

    /**
     * Get server capabilities after connection.
     */
    fun getServerCapabilities() = client?.serverCapabilities

    /**
     * Get server version info.
     */
    fun getServerVersion() = client?.serverVersion

    private fun logD(message: String) = AgentLogger.verbose("MCP", "ClientWrapper", message)
    private fun logI(message: String) = AgentLogger.normal("MCP", "ClientWrapper", message)
    private fun logE(message: String, throwable: Throwable? = null) = AgentLogger.error("MCP", "ClientWrapper", message, throwable)

    private inline fun safeLog(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Fallback for JVM unit tests where android.util.Log is not mocked
        }
    }
}

/**
 * MCP connection configuration.
 */
sealed class MCPConfig {
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap()
    ) : MCPConfig()

    data class WebSocket(
        val url: String
    ) : MCPConfig()

    data class StreamableHttp(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val reconnectionSeconds: Long = 5,
        val modelScopeCompat: Boolean = false,
        /**
         * Use Plain HTTP JSON-RPC mode instead of SSE streaming.
         * 
         * Some MCP servers (like DiDi) return synchronous JSON responses
         * instead of Server-Sent Events streams. When this is true,
         * PlainHttpTransport is used instead of StreamableHttpClientTransport.
         * 
         * Set to true for servers that:
         * - Return Content-Type: application/json instead of text/event-stream
         * - Don't use SSE streaming for responses
         */
        val plainHttpMode: Boolean = false
    ) : MCPConfig()
}

/**
 * MCP tool definition.
 */
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JsonElement?,
    val renewalSeconds: Int?
)

/**
 * MCP resource definition.
 */
data class MCPResource(
    val uri: String,
    val name: String,
    val description: String?,
    val mimeType: String?
)

/**
 * MCP-related exception.
 */
class MCPException(message: String, cause: Throwable? = null) : Exception(message, cause)
