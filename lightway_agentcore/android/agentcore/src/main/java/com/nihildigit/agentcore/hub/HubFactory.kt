package com.nihildigit.agentcore.hub

import android.content.Context
import android.content.SharedPreferences
import com.nihildigit.agentcore.adapter.CloudChatAdapter
import com.nihildigit.agentcore.hub.combos.*
import com.nihildigit.agentcore.logging.AgentLogger
import com.nihildigit.agentcore.mcp.MCPConfig
import com.nihildigit.agentcore.mcp.ToolFilterConfig
import kotlinx.coroutines.CoroutineScope

/**
 * Hub 工厂类
 * 
 * 简化 Hub 的创建和初始化流程，包括：
 * - 创建 McpConnectionManager
 * - 注册 MCP 服务
 * - 创建 ContextProvider
 * - 注册所有 Combo 工具
 */
object HubFactory {
    
    private const val TAG = "HubFactory"
    private const val PREFS_NAME = "hub_settings"
    private const val KEY_ENABLED_SERVICES = "enabled_services"
    
    /**
     * 创建完整配置的 Hub 实例
     *
     * @param context Android Context
     * @param scope CoroutineScope for async operations
     * @param serviceDefinitions MCP 服务定义列表
     * @param hubConfig Hub 配置
     * @param visionAdapterProvider 可选的云端视觉适配器提供者（用于 describe_camera / describe_screen）
     * @return 配置完成的 Hub 实例
     */
    fun create(
        context: Context,
        scope: CoroutineScope,
        serviceDefinitions: List<McpServiceDefinition>,
        hubConfig: HubConfig = HubConfig(),
        visionAdapterProvider: (() -> CloudChatAdapter?)? = null,
        contextProviderOverride: ContextProvider? = null
    ): Hub {
        AgentLogger.normal(TAG, "Create", "creating hub",
            "services" to serviceDefinitions.size
        )

        // 创建 SharedPreferences 用于持久化服务启用状态
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 创建连接管理器
        val connectionManager = McpConnectionManager(
            scope = scope,
            connectionTimeoutMs = hubConfig.connectionTimeoutMs,
            enabledServicesProvider = { loadEnabledServices(prefs) },
            onEnabledServicesChanged = { services -> saveEnabledServices(prefs, services) }
        )

        // 注册服务
        connectionManager.registerServices(serviceDefinitions)

        // 创建 Context Provider
        val contextProvider = contextProviderOverride ?: AndroidContextProvider(context)

        // 创建 Hub
        val hub = Hub(connectionManager, contextProvider, hubConfig)

        // 注册所有 Combo 工具
        registerCombos(hub, connectionManager, contextProvider, hubConfig, visionAdapterProvider)

        AgentLogger.normal(TAG, "Create", "hub created",
            "combos" to hub.getAvailableCombos().size
        )

        return hub
    }
    
    /**
     * 从 MCPServiceConfig 定义转换为 Hub 服务定义
     */
    fun convertServiceDefinitions(
        configDefinitions: List<McpServiceConfigDefinition>
    ): List<McpServiceDefinition> {
        return configDefinitions.mapNotNull { def ->
            try {
                McpServiceDefinition(
                    id = def.id,
                    name = def.name,
                    displayName = def.displayName,
                    description = def.description,
                    tier = getServiceTier(def.id),
                    config = buildMcpConfig(def),
                    defaultEnabled = def.enabled,
                    toolFilter = def.toolFilter,
                    connectionTimeoutMs = def.connectionTimeoutMs,
                    toolCallTimeoutMs = def.toolCallTimeoutMs
                )
            } catch (e: Exception) {
                AgentLogger.error(TAG, "ConvertDefinition", "failed to convert", e,
                    "serviceId" to def.id
                )
                null
            }
        }
    }
    
    /**
     * 注册所有 Combo 工具
     */
    private fun registerCombos(
        hub: Hub,
        connectionManager: McpConnectionManager,
        contextProvider: ContextProvider,
        config: HubConfig,
        visionAdapterProvider: (() -> CloudChatAdapter?)? = null
    ) {
        val combos = mutableListOf(
            // === 出行助手 (amap-maps) ===
            NavigateToCombo(connectionManager, contextProvider, config),
            StopNavigationCombo(connectionManager, contextProvider, config),
            FindNearbyCombo(connectionManager, contextProvider, config),
            WeatherHereCombo(connectionManager, contextProvider, config),

            // === 打车出行 (amap-maps + didi-sandbox) ===
            CallTaxiCombo(connectionManager, contextProvider, config),
            TaxiStatusCombo(connectionManager, contextProvider, config),
            CancelTaxiCombo(connectionManager, contextProvider, config),

            // === 交通查询 (12306-mcp, variflight-mcp) ===
            SearchTrainCombo(connectionManager, contextProvider, config),
            SearchFlightCombo(connectionManager, contextProvider, config)
        )

        // === 云端工具（不依赖 MCP 服务）===
        if (visionAdapterProvider != null) {
            combos.add(DescribeCameraCombo(connectionManager, contextProvider, config, visionAdapterProvider))
            combos.add(DescribeScreenCombo(connectionManager, contextProvider, config, visionAdapterProvider))
        }

        hub.registerCombos(combos)

        AgentLogger.normal(TAG, "RegisterCombos", "combos registered",
            "count" to combos.size
        )
    }
    
    /**
     * 根据服务 ID 确定服务级别
     * 
     * - CORE: 核心服务，启动时预连接（地图、搜索）
     * - STANDARD: 标准服务，默认启用时预连接（出行、热搜）
     * - OPTIONAL: 可选服务，懒加载（菜谱等低频服务）
     */
    private fun getServiceTier(serviceId: String): McpServiceTier {
        return when (serviceId) {
            // 核心服务 - 地图是基础功能
            "amap-maps" -> McpServiceTier.CORE
            // 标准服务 - 出行相关，默认启用时预连接
            "12306-mcp", "variflight-mcp", "didi-sandbox" -> McpServiceTier.STANDARD
            else -> McpServiceTier.STANDARD
        }
    }
    
    /**
     * 构建 MCP 配置
     */
    private fun buildMcpConfig(def: McpServiceConfigDefinition): MCPConfig {
        val url = def.streamableHttpUrl
        if (url.isNullOrBlank()) {
            throw IllegalArgumentException("Service ${def.id} missing streamableHttpUrl")
        }
        
        return MCPConfig.StreamableHttp(
            url = url,
            headers = def.streamableHttpHeaders,
            reconnectionSeconds = def.streamableHttpReconnectionSeconds,
            modelScopeCompat = def.modelScopeCompat,
            plainHttpMode = def.plainHttpMode
        )
    }
    
    /**
     * 从 SharedPreferences 加载启用的服务列表
     */
    private fun loadEnabledServices(prefs: SharedPreferences): Set<String> {
        val saved = prefs.getStringSet(KEY_ENABLED_SERVICES, null)
        return saved ?: emptySet()
    }
    
    /**
     * 保存启用的服务列表到 SharedPreferences
     */
    private fun saveEnabledServices(prefs: SharedPreferences, services: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_SERVICES, services)
            .apply()
    }
}

/**
 * MCP 服务配置定义（与 MCPServiceConfig.MCPServiceDefinition 对应）
 * 
 * 用于 HubFactory.convertServiceDefinitions() 的输入
 */
data class McpServiceConfigDefinition(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val streamableHttpUrl: String?,
    val streamableHttpHeaders: Map<String, String> = emptyMap(),
    val streamableHttpReconnectionSeconds: Long = 5,
    val modelScopeCompat: Boolean = false,
    /**
     * Use Plain HTTP JSON-RPC mode instead of SSE streaming.
     * 
     * Set to true for servers like DiDi that return synchronous JSON responses
     * instead of Server-Sent Events streams.
     */
    val plainHttpMode: Boolean = false,
    val enabled: Boolean = true,
    val toolFilter: ToolFilterConfig = ToolFilterConfig(),
    /**
     * 连接超时时间（毫秒）
     * 用于 McpConnectionManager.connectService() 的 withTimeout
     * 默认 10 秒
     */
    val connectionTimeoutMs: Long = 10_000,
    /**
     * 工具调用超时时间（毫秒）
     * 用于 MCPClientWrapper.callTool() 的 withTimeout
     * 默认 30 秒
     */
    val toolCallTimeoutMs: Long = 30_000
)
