package com.nihildigit.agentcore.hub

import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.mcp.ToolProvider
import com.nihildigit.agentcore.mcp.ToolWithProvider
import com.nihildigit.agentcore.mcp.ToolFilterConfig
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hub - MCP 工具组合适配层
 * 
 * Hub 是一个轻量级适配层，通过"组合工具 + 上下文注入 + 输出精简"三板斧
 * 解决直接透传 MCP 工具的问题：
 * 
 * 1. **组合工具 (Combo)**：将多步 MCP 调用组合为单一操作
 * 2. **上下文注入**：自动注入 GPS 位置、当前时间等运行时上下文
 * 3. **输出精简**：从 MCP 响应中提取关键字段，减少 token 消耗
 * 
 * 架构：
 * ```
 * Router → Hub → [Combo Tools] → MCP Services
 *                  ↓
 *            ContextProvider (GPS, Time)
 * ```
 * 
 * 使用方式：
 * ```kotlin
 * val hub = Hub(connectionManager, contextProvider, config)
 * hub.registerCombos(listOf(NavigateToCombo(...), FindNearbyCombo(...)))
 * 
 * // 获取工具列表（Combo 在前，原始工具在后）
 * val tools = hub.getAllTools()
 * 
 * // 执行工具
 * val result = hub.executeTool("navigate_to", mapOf("destination" to "星巴克"))
 * ```
 */
class Hub(
    private val connectionManager: McpConnectionManager,
    private val contextProvider: ContextProvider,
    private val config: HubConfig = HubConfig()
) : ToolClient {
    
    companion object {
        private const val TAG = "Hub"
        private const val SERVICE_ID = "hub"
        private const val SERVICE_NAME = "快捷"
    }

    // 注册的 Combo 工具
    private val comboTools = mutableMapOf<String, ComboTool>()

    // 工具过滤配置
    private var toolFilter: ToolFilterConfig = ToolFilterConfig()
    
    // 工具列表变化回调（用于通知 Router 刷新缓存）
    private var onToolsChangedListener: (() -> Unit)? = null
    
    // Action 处理器（用于处理 Combo 返回的动作，如启动外部 App）
    private var actionHandler: ComboActionHandler? = null
    
    // 待执行的 Actions（由 onSessionComplete 在 Router.process 返回前自动消费）
    private val pendingActions = mutableListOf<ComboAction>()
    private val pendingActionsLock = Any()

    // 待播报文本（Combo 设置，Router 在 LLM 回复前通过 onDelta 播报）
    @Volatile
    private var pendingAnnounce: String? = null

    // 打车订单状态轮询器
    private var taxiPoller: TaxiStatusPoller? = null
    
    // Combo 启用状态（持久化由外部管理）
    private val _enabledCombos = MutableStateFlow<Set<String>>(emptySet())
    val enabledCombos: StateFlow<Set<String>> = _enabledCombos.asStateFlow()
    
    // 批量操作标志：applyComboConfig 期间为 true，抑制中间的工具变化通知
    @Volatile
    private var isApplyingConfig = false
    
    init {
        // 监听 MCP 连接状态变化，状态变化时通知工具列表变化
        connectionManager.setStateListener(object : McpConnectionStateListener {
            override fun onConnectionStateChanged(serviceId: String, state: McpConnectionState) {
                // 批量操作期间抑制通知，避免事件风暴
                if (isApplyingConfig) {
                    AgentLogger.verbose(TAG, "ConnectionState", "suppressed during batch apply",
                        "serviceId" to serviceId,
                        "state" to state.name
                    )
                    return
                }
                
                // 在以下情况通知工具列表变化：
                // 1. 服务连接成功（新工具可用）
                // 2. 服务断开连接（工具不可用或启用状态变化）
                if (state == McpConnectionState.CONNECTED || state == McpConnectionState.DISCONNECTED) {
                    AgentLogger.normal(TAG, "ConnectionState", "service state changed, notifying tools changed",
                        "serviceId" to serviceId,
                        "state" to state.name
                    )
                    onToolsChangedListener?.invoke()
                }
            }
        })
    }
    
    /**
     * 设置工具列表变化监听器
     * 当 MCP 服务连接/断开或启用状态变化时会调用此回调
     */
    fun setOnToolsChangedListener(listener: (() -> Unit)?) {
        this.onToolsChangedListener = listener
    }

    /**
     * 设置 Action 处理器
     * 用于处理 Combo 返回的动作（如启动外部 App）
     */
    fun setActionHandler(handler: ComboActionHandler?) {
        this.actionHandler = handler
    }

    fun setStatusBroadcastCallback(callback: StatusBroadcastCallback) {
        taxiPoller = TaxiStatusPoller(this, callback)
    }
    
    /**
     * 消费并执行所有待处理的 Actions
     *
     * 由 onSessionComplete() 自动调用（Router.process 返回前触发），确保：
     * 1. LLM 已经生成完整回复
     * 2. Session/Task 已结束
     * 3. 用户已看到助手的回复
     *
     * @return 执行的 Action 数量
     */
    fun consumePendingActions(): Int {
        val actions = synchronized(pendingActionsLock) {
            val copy = pendingActions.toList()
            pendingActions.clear()
            copy
        }
        
        if (actions.isEmpty()) return 0
        
        AgentLogger.normal(TAG, "ConsumeActions", "executing pending actions",
            "count" to actions.size
        )
        
        val handler = actionHandler
        if (handler == null) {
            AgentLogger.normal(TAG, "ConsumeActions", "no action handler set, skipping actions")
            return 0
        }
        
        var executed = 0
        for (action in actions) {
            try {
                if (handler.handleAction(action)) {
                    executed++
                }
            } catch (e: Exception) {
                AgentLogger.error(TAG, "ConsumeActions", "action execution failed", e)
            }
        }
        
        AgentLogger.normal(TAG, "ConsumeActions", "actions executed",
            "total" to actions.size,
            "successful" to executed
        )
        
        return executed
    }
    
    /**
     * 检查是否有待处理的 Actions
     */
    fun hasPendingActions(): Boolean {
        synchronized(pendingActionsLock) {
            return pendingActions.isNotEmpty()
        }
    }
    
    /**
     * 清除所有待处理的 Actions（用于取消/重置场景）
     */
    fun clearPendingActions() {
        synchronized(pendingActionsLock) {
            pendingActions.clear()
        }
    }
    
    /**
     * 注册 Combo 工具
     */
    fun registerCombo(combo: ComboTool) {
        comboTools[combo.name] = combo
        AgentLogger.normal(TAG, "Register", "combo registered",
            "name" to combo.name,
            "services" to combo.requiredServices.joinToString()
        )
    }
    
    /**
     * 批量注册 Combo 工具
     */
    fun registerCombos(combos: List<ComboTool>) {
        combos.forEach { registerCombo(it) }
    }
    
    /**
     * 初始化 Combo 启用状态（从持久化存储恢复）
     * 应在 registerCombos 之后调用
     */
    fun initEnabledCombos(enabledComboNames: Set<String>) {
        _enabledCombos.value = enabledComboNames
        AgentLogger.normal(TAG, "InitCombos", "enabled combos initialized",
            "count" to enabledComboNames.size,
            "names" to enabledComboNames.joinToString()
        )
    }
    
    /**
     * 应用 Combo 配置（批量启用/禁用）
     * 
     * 工作流程：
     * 1. 设置 isApplyingConfig 标志，抑制批量操作期间的中间通知
     * 2. 更新 enabledCombos 状态
     * 3. 计算所需 MCP 服务，启用它们、禁用不需要的服务
     * 4. 连接启用的服务，断开不需要的服务
     * 5. 清除标志后，单次通知工具列表变化（触发 Stem 重建）
     * 
     * @param enabledComboNames 要启用的 Combo 名称集合
     * @return 配置应用结果
     */
    suspend fun applyComboConfig(enabledComboNames: Set<String>): ComboConfigResult {
        val previousEnabled = _enabledCombos.value
        
        AgentLogger.normal(TAG, "ApplyConfig", "applying combo config",
            "previous" to previousEnabled.size,
            "new" to enabledComboNames.size
        )
        
        // 设置批量操作标志，抑制连接状态变化的中间通知
        isApplyingConfig = true
        
        try {
            _enabledCombos.value = enabledComboNames
            
            // 收集所有启用 Combo 依赖的 MCP 服务
            val requiredServices = enabledComboNames
                .mapNotNull { comboTools[it] }
                .flatMap { it.requiredServices }
                .toSet()
            
            // 获取所有已注册服务 ID
            val allServiceIds = connectionManager.getServiceStatuses().map { it.serviceId }.toSet()
            
            // 计算需要禁用的服务（之前启用但现在不需要的）
            val servicesToDisable = allServiceIds - requiredServices
            
            AgentLogger.normal(TAG, "ApplyConfig", "managing services",
                "required" to requiredServices.joinToString(),
                "toDisable" to servicesToDisable.joinToString()
            )
            
            // 先禁用不需要的服务（这会自动断开连接）
            for (serviceId in servicesToDisable) {
                connectionManager.setServiceEnabled(serviceId, false)
            }
            
            // 启用所需服务
            for (serviceId in requiredServices) {
                connectionManager.setServiceEnabled(serviceId, true)
            }
            
            // 并行连接所需服务（使用 async/awaitAll 优化连接时间）
            val connectionResults = coroutineScope {
                requiredServices.map { serviceId ->
                    async {
                        serviceId to (connectionManager.ensureConnected(serviceId) != null)
                    }
                }.awaitAll().toMap()
            }
            
            val failedServices = connectionResults.filter { !it.value }.keys
            return if (failedServices.isEmpty()) {
                ComboConfigResult.success(enabledComboNames.size)
            } else {
                ComboConfigResult.partialSuccess(
                    enabledCount = enabledComboNames.size,
                    failedServices = failedServices.toList()
                )
            }
        } finally {
            // 清除批量操作标志
            isApplyingConfig = false
            
            // 批量操作完成后，单次通知工具列表变化
            AgentLogger.normal(TAG, "ApplyConfig", "batch complete, notifying tools changed once")
            onToolsChangedListener?.invoke()
        }
    }
    
    /**
     * 检查 Combo 是否已启用
     */
    fun isComboEnabled(name: String): Boolean = name in _enabledCombos.value
    
    /**
     * 获取所有已注册的 Combo 信息（用于 UI 显示）
     */
    fun getRegisteredCombos(): List<ComboInfo> {
        return comboTools.values.map { combo ->
            ComboInfo(
                name = combo.name,
                displayName = combo.displayName,
                description = combo.description,
                group = combo.group,
                requiredServices = combo.requiredServices,
                isEnabled = isComboEnabled(combo.name),
                isAvailable = combo.isAvailable(),
                providerId = combo.requiredServices.firstOrNull()
            )
        }
    }
    
    /**
     * 获取可用的 Combo 工具列表
     * 只返回已启用且所有依赖服务都已启用的 Combo
     */
    fun getAvailableCombos(): List<ComboTool> {
        if (!config.hubEnabled) return emptyList()
        return comboTools.values.filter { 
            isComboEnabled(it.name) && it.isAvailable() 
        }
    }
    
    /**
     * 获取所有工具（Combo），按优先级排序
     *
     * 设计原则：
     * - 暴露已启用的 Combo 工具给 LLM
     * - 原始 MCP 工具对 LLM 不可见，由 Combo 内部调用
     * - MCP 服务连接由 Combo 启用状态驱动
     *
     * 排序规则：
     * 1. 按 priority 降序（Combo 默认 100）
     * 2. 同优先级内保持原顺序
     */
    fun getAllToolsWithProviders(): List<ToolWithProvider> {
        val result = mutableListOf<ToolWithProvider>()

        // 添加 Combo 工具
        if (config.hubEnabled) {
            result.addAll(getAvailableCombos().map { combo ->
                ToolWithProvider(
                    tool = combo.toTool(),
                    provider = ToolProvider.BUILT_IN,
                    serviceId = SERVICE_ID,
                    serviceName = SERVICE_NAME
                )
            })
        }

        return result.sortedByDescending { it.tool.priority }
    }
    
    // === ToolClient 接口实现 ===
    
    override fun listTools(): List<Tool> {
        return getAllToolsWithProviders().map { it.tool }
    }
    
    override fun listTools(filter: ToolFilterConfig): List<Tool> {
        return getAllToolsWithProviders()
            .filter { filter.isToolEnabled(it.tool.function.name, it.provider) }
            .map { it.tool }
    }
    
    override suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): Result<String> {
        // 1. 先检查 Combo 工具
        comboTools[name]?.let { combo ->
            // 检查 Combo 是否已启用
            if (!isComboEnabled(name)) {
                AgentLogger.normal(TAG, "CallTool", "combo not enabled",
                    "name" to name
                )
                return Result.failure(Exception("快捷工具 $name 未启用"))
            }

            // 检查依赖服务是否可用
            if (!combo.isAvailable()) {
                AgentLogger.normal(TAG, "CallTool", "combo services not available",
                    "name" to name,
                    "requiredServices" to combo.requiredServices.joinToString()
                )
                return Result.failure(Exception("快捷工具 $name 的依赖服务未就绪"))
            }

            AgentLogger.normal(TAG, "CallTool", "executing combo",
                "name" to name,
                "args" to arguments.keys.joinToString()
            )

            val result = combo.execute(arguments, history)

            // 如果有 Action，加入待执行队列（由 onSessionComplete 自动消费）
            result.action?.let { action ->
                AgentLogger.normal(TAG, "CallTool", "queueing combo action for later execution",
                    "name" to name,
                    "actionType" to (action::class.simpleName ?: "unknown")
                )
                synchronized(pendingActionsLock) {
                    pendingActions.add(action)
                }
            }

            // 如果有待播报文本，存入 pendingAnnounce
            result.announceText?.let { pendingAnnounce = it }

            // call_taxi 成功后启动订单状态轮询
            if (name == "call_taxi" && result.success) {
                TaxiOrderStore.getLastOrderId()?.let { orderId ->
                    taxiPoller?.start(orderId)
                }
            }

            return if (result.success) {
                Result.success(result.content)
            } else {
                Result.failure(Exception(result.content))
            }
        }

        // 工具未找到
        return Result.failure(Exception("工具未找到: $name"))
    }
    
    override fun setToolFilter(filter: ToolFilterConfig) {
        this.toolFilter = filter
    }
    
    override fun getToolFilter(): ToolFilterConfig = toolFilter
    
    override fun getProvider(): ToolProvider = ToolProvider.BUILT_IN
    
    override fun getServiceId(): String = SERVICE_ID
    
    override fun getServiceName(): String = SERVICE_NAME

    override fun onSessionComplete() {
        consumePendingActions()
    }

    override fun consumeAnnounce(): String? {
        val text = pendingAnnounce
        pendingAnnounce = null
        return text
    }

    /**
     * 释放 Hub 资源。
     */
    suspend fun release() {
        taxiPoller?.stop()
        clearPendingActions()
        connectionManager.release()
    }
    
    /**
     * 检查工具是否是 Combo 工具
     */
    fun isComboTool(name: String): Boolean = name in comboTools
    
    /**
     * 获取 Hub 配置
     */
    fun getConfig(): HubConfig = config
    
    /**
     * 获取连接管理器（用于状态监控）
     */
    fun getConnectionManager(): McpConnectionManager = connectionManager
    
    /**
     * 获取上下文提供者（用于调试）
     */
    fun getContextProvider(): ContextProvider = contextProvider
}

/**
 * Combo 工具信息（用于 UI 显示）
 */
data class ComboInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val group: ComboGroup,
    val requiredServices: List<String>,
    val isEnabled: Boolean,
    val isAvailable: Boolean,
    val providerId: String? = null
)

/**
 * Combo 配置应用结果
 */
sealed class ComboConfigResult {
    data class Success(val enabledCount: Int) : ComboConfigResult()
    data class PartialSuccess(
        val enabledCount: Int,
        val failedServices: List<String>
    ) : ComboConfigResult()
    data class Failure(val error: String) : ComboConfigResult()
    
    companion object {
        fun success(enabledCount: Int) = Success(enabledCount)
        fun partialSuccess(enabledCount: Int, failedServices: List<String>) = 
            PartialSuccess(enabledCount, failedServices)
        fun failure(error: String) = Failure(error)
    }
}
