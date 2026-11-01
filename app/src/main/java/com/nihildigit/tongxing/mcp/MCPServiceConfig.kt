package com.nihildigit.tongxing.mcp

import com.nihildigit.agentcore.mcp.MCPClientWrapper
import com.nihildigit.agentcore.mcp.MCPConfig
import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.mcp.ToolFilterConfig
import com.nihildigit.tongxing.BuildConfig
import java.net.URI

/**
 * MCP transport types
 */
enum class TransportType {
    STDIO,
    WEBSOCKET,
    STREAMABLE_HTTP
}

/**
 * MCP 精选服务配置
 *
 * 预置服务和工具过滤规则。默认构建的服务 URL 为空；本机调试可使用
 * -PembedLocalServiceConfig=true，将 local.properties 配置写入 debug APK。
 * 在项目根目录 local.properties 中添加：
 * ```
 * mcp.amap.url=https://mcp.api-inference.modelscope.net/YOUR_ID/mcp
 * mcp.12306.url=https://mcp.api-inference.modelscope.net/YOUR_ID/mcp
 * mcp.variflight.url=https://mcp.api-inference.modelscope.net/YOUR_ID/mcp
 * ```
 */
object MCPServiceConfig {

    /**
     * 预置 MCP 服务列表
     */
    val PRESET_SERVICES = listOf(
        // ============================================================
        // 核心服务 (Core Services) - 默认启用
        // ============================================================
        
        // ModelScope 高德地图 - 核心导航服务
        MCPServiceDefinition(
            id = "amap-maps",
            name = "高德地图",
            displayName = "导航",
            description = "地图导航、POI搜索、天气查询（12个工具）",
            transportType = TransportType.STREAMABLE_HTTP,
            streamableHttpUrl = BuildConfig.MCP_AMAP_URL,
            streamableHttpHeaders = mapOf("User-Agent" to "TongXing/1.0"),
            streamableHttpReconnectionSeconds = 5,
            trusted = true,
            enabled = true,
            toolFilter = ToolFilterConfig(
                enabledTools = setOf(
                    "maps_geo",
                    "maps_regeocode",
                    "maps_search_detail",
                    "maps_direction_walking",
                    "maps_around_search",
                    "maps_weather"
                )
            )
        ),
        
        // ============================================================
        // 甜点服务 (Dessert Services) - 用户按需启用，默认禁用
        // ============================================================
        
        // ModelScope 12306 - 火车票查询
        MCPServiceDefinition(
            id = "12306-mcp",
            name = "12306 火车票",
            displayName = "火车",
            description = "火车票查询、车次路线、换乘方案",
            transportType = TransportType.STREAMABLE_HTTP,
            streamableHttpUrl = BuildConfig.MCP_12306_URL,
            streamableHttpHeaders = mapOf("User-Agent" to "TongXing/1.0"),
            streamableHttpReconnectionSeconds = 5,
            trusted = true,
            enabled = false,
            toolFilter = ToolFilterConfig(
                enabledTools = setOf(
                    "get-current-date",
                    "get-station-code-of-citys",
                    "get-station-code-by-names",
                    "get-tickets",
                    "get-train-route-stations"
                )
            )
        ),
        // ModelScope 飞常准 - 航班查询
        MCPServiceDefinition(
            id = "variflight-mcp",
            name = "飞常准航班",
            displayName = "航班",
            description = "航班查询、机场天气、航班舒适度指数",
            transportType = TransportType.STREAMABLE_HTTP,
            streamableHttpUrl = BuildConfig.MCP_VARIFLIGHT_URL,
            streamableHttpHeaders = mapOf("User-Agent" to "TongXing/1.0"),
            streamableHttpReconnectionSeconds = 5,
            trusted = true,
            enabled = false,
            toolFilter = ToolFilterConfig(
                enabledTools = setOf(
                    "searchFlightsByDepArr",
                    "searchFlightsByNumber",
                    "getTodayDate",
                    "getFutureWeatherByAirport"
                )
            )
        ),
        // 滴滴打车沙箱 - 打车估价（注意：仅沙箱环境，不产生真实订单）
        MCPServiceDefinition(
            id = "didi-sandbox",
            name = "滴滴打车",
            displayName = "打车",
            description = "打车估价、等待时间预估（沙箱环境）",
            transportType = TransportType.STREAMABLE_HTTP,
            streamableHttpUrl = BuildConfig.MCP_DIDI_SANDBOX_URL,
            streamableHttpHeaders = mapOf("User-Agent" to "TongXing/1.0"),
            streamableHttpReconnectionSeconds = 5,
            plainHttpMode = true,  // 滴滴使用同步 JSON 响应而非 SSE
            trusted = true,
            enabled = false,
            toolFilter = ToolFilterConfig(
                enabledTools = setOf(
                    "taxi_estimate",
                    "taxi_create_order",
                    "taxi_query_order",
                    "taxi_cancel_order",
                    "taxi_get_driver_location"
                )
            )
        )
    )

    /**
     * 根据服务定义构建 MCP 配置
     */
    fun buildMCPConfig(definition: MCPServiceDefinition): MCPConfig {
        return when (definition.transportType) {
            TransportType.STDIO -> {
                definition.stdioConfig ?: definition.config as? MCPConfig.Stdio
                ?: throw IllegalArgumentException("STDIO transport requires stdioConfig or config of type Stdio")
            }
            TransportType.WEBSOCKET -> {
                val url = definition.webSocketUrl ?: throw IllegalArgumentException("WebSocket transport requires webSocketUrl")
                MCPConfig.WebSocket(url = url)
            }
            TransportType.STREAMABLE_HTTP -> {
                val url = definition.streamableHttpUrl
                if (url.isNullOrBlank()) {
                    throw IllegalArgumentException("Streamable HTTP transport requires streamableHttpUrl (check local.properties)")
                }
                MCPConfig.StreamableHttp(
                    url = url,
                    headers = definition.streamableHttpHeaders,
                    reconnectionSeconds = definition.streamableHttpReconnectionSeconds,
                    modelScopeCompat = definition.modelScopeCompat
                )
            }
        }
    }

    /**
     * MCP 服务定义
     */
    data class MCPServiceDefinition(
        val id: String,
        val name: String,
        val description: String,
        val transportType: TransportType,
        val config: MCPConfig? = null,                    // 传统配置，保持向后兼容
        val stdioConfig: MCPConfig.Stdio? = null,         // Stdio 配置
        val webSocketUrl: String? = null,                 // WebSocket URL
        val streamableHttpUrl: String? = null,            // Streamable HTTP URL
        val streamableHttpHeaders: Map<String, String> = emptyMap(), // Streamable HTTP 自定义头
        val streamableHttpReconnectionSeconds: Long = 5,  // Streamable HTTP 重连间隔
        val modelScopeCompat: Boolean = false,            // 是否启用 ModelScope 兼容模式
        /**
         * 使用 Plain HTTP JSON-RPC 模式代替 SSE 流。
         * 
         * 某些 MCP 服务器（如滴滴）返回同步 JSON 响应而不是 Server-Sent Events 流。
         * 设置为 true 时使用 PlainHttpTransport 而不是 StreamableHttpClientTransport。
         */
        val plainHttpMode: Boolean = false,
        val trusted: Boolean,       // 是否信任（不需要确认）
        val enabled: Boolean,       // 是否启用
        val rateLimit: Int = 60,    // 每分钟调用限制
        val timeout: Long = 30000,  // 超时时间（毫秒）
        /** 工具级别的过滤配置 */
        val toolFilter: ToolFilterConfig = ToolFilterConfig(),
        /** 服务显示名称（用于 UI 分组） */
        val displayName: String = name
    )

    /**
     * 创建 MCP 客户端列表
     * 
     * 注意：如果 local.properties 中未配置对应的 URL，服务将被跳过
     */
    suspend fun createClients(): List<ToolClient> {
        val clients = mutableListOf<ToolClient>()

        PRESET_SERVICES
            .filter { it.enabled }
            .filter { service ->
                // 跳过未配置 URL 的服务
                val hasUrl = !service.streamableHttpUrl.isNullOrBlank()
                if (!hasUrl) {
                    System.err.println("[MCP] 跳过服务 ${service.id}: 未在 local.properties 配置 URL")
                }
                hasUrl
            }
            .forEach { service ->
                val client = MCPClientWrapper(
                    clientName = "lightway-${service.id}",
                    clientVersion = "1.0.0"
                )

                val config = try {
                    buildMCPConfig(service)
                } catch (e: Exception) {
                    System.err.println("[MCP] 配置错误 ${service.id}: ${e.message}")
                    return@forEach
                }

                val result = client.connect(config)
                if (result.isSuccess) {
                    // 设置服务信息用于 UI 显示
                    client.setServiceInfo(service.id, service.displayName)
                    
                    // 应用工具过滤配置
                    if (service.toolFilter != ToolFilterConfig()) {
                        client.setToolFilter(service.toolFilter)
                        System.err.println("[MCP] 服务 ${service.id} 应用工具过滤: ${service.toolFilter}")
                    }
                    clients.add(client)
                    System.err.println("[MCP] ✓ 连接成功: ${service.name}")
                } else {
                    System.err.println("[MCP] ✗ 连接失败 ${service.id}: ${result.exceptionOrNull()?.message}")
                }
            }

        return clients
    }

    /**
     * 限流策略
     */
    class RateLimiter(private val maxCallsPerMinute: Int) {
        private val callTimes = mutableListOf<Long>()

        @Synchronized
        fun canCall(): Boolean {
            val now = System.currentTimeMillis()
            val oneMinuteAgo = now - 60_000

            // 清除过期记录
            callTimes.removeIf { it < oneMinuteAgo }

            return callTimes.size < maxCallsPerMinute
        }

        @Synchronized
        fun recordCall() {
            callTimes.add(System.currentTimeMillis())
        }
    }

    /**
     * 第三方服务验证（预留接口）
     */
    fun validateThirdPartyService(serviceUrl: String): Boolean {
        // TODO: 实现签名验证、白名单检查等
        // 当前阶段不接入第三方服务
        return false
    }
    
    /**
     * 将 PRESET_SERVICES 转换为 Hub 需要的 McpServiceConfigDefinition 格式
     * 
     * 用于 HubFactory.convertServiceDefinitions() 的输入
     * 
     * 注意：包含所有服务（包括默认禁用的 OPTIONAL 服务），
     * 服务的实际启用状态由 Combo 配置驱动
     */
    fun isValidServiceUrl(value: String): Boolean {
        if (value.isBlank()) return true
        return runCatching {
            val uri = URI(value.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null
        }.getOrDefault(false)
    }

    fun toHubDefinitions(runtimeUrls: Map<String, String> = emptyMap()): List<com.nihildigit.agentcore.hub.McpServiceConfigDefinition> {
        return PRESET_SERVICES
            .map { service ->
                service.copy(streamableHttpUrl = runtimeUrls[service.id]?.trim() ?: service.streamableHttpUrl)
            }
            // 不再过滤 enabled 状态，所有服务都需要注册
            .filter { !it.streamableHttpUrl.isNullOrBlank() }
            .map { def ->
                com.nihildigit.agentcore.hub.McpServiceConfigDefinition(
                    id = def.id,
                    name = def.name,
                    displayName = def.displayName,
                    description = def.description,
                    streamableHttpUrl = def.streamableHttpUrl,
                    streamableHttpHeaders = def.streamableHttpHeaders,
                    streamableHttpReconnectionSeconds = def.streamableHttpReconnectionSeconds,
                    modelScopeCompat = def.modelScopeCompat,
                    plainHttpMode = def.plainHttpMode,
                    enabled = def.enabled,
                    toolFilter = def.toolFilter,
                    connectionTimeoutMs = def.timeout
                )
            }
    }
}
