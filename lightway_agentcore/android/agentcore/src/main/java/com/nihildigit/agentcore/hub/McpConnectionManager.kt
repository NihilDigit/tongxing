package com.nihildigit.agentcore.hub

import com.nihildigit.agentcore.mcp.MCPClientWrapper
import com.nihildigit.agentcore.mcp.MCPConfig
import com.nihildigit.agentcore.mcp.redactUrlsInMessage
import com.nihildigit.agentcore.mcp.SharedHttpClientProvider
import com.nihildigit.agentcore.mcp.ToolClient
import com.nihildigit.agentcore.mcp.ToolFilterConfig
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 服务定义
 */
data class McpServiceDefinition(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val tier: McpServiceTier,
    val config: MCPConfig,
    val defaultEnabled: Boolean,
    val toolFilter: ToolFilterConfig = ToolFilterConfig(),
    /**
     * 连接超时时间（毫秒）
     * 用于 McpConnectionManager.connectService() 的 withTimeout
     */
    val connectionTimeoutMs: Long = 10_000,
    /**
     * 工具调用超时时间（毫秒）
     * 用于 MCPClientWrapper.callTool() 的 withTimeout
     */
    val toolCallTimeoutMs: Long = 30_000
)

/**
 * MCP 连接管理器
 * 
 * 功能：
 * - 管理所有 MCP 服务的连接生命周期
 * - 支持服务分级（CORE/STANDARD/OPTIONAL）
 * - 核心服务预连接，非核心服务懒加载
 * - 用户设置持久化和实时更新
 * 
 * 使用方式：
 * ```kotlin
 * val manager = McpConnectionManager(scope)
 * manager.registerServices(services)
 * manager.initializeAsync()  // 预连接核心服务
 * 
 * // 获取已连接的客户端
 * val clients = manager.getConnectedClients()
 * 
 * // 懒加载连接
 * val client = manager.ensureConnected("didi-sandbox")
 * ```
 */
class McpConnectionManager(
    private val scope: CoroutineScope,
    private val connectionTimeoutMs: Long = 10_000,
    private val enabledServicesProvider: () -> Set<String> = { emptySet() },
    private val onEnabledServicesChanged: (Set<String>) -> Unit = {}
) {
    companion object {
        private const val TAG = "McpConnectionManager"
        private val CONNECT_RETRY_TIMEOUTS_MS = longArrayOf(5_000L, 10_000L, 15_000L)
    }
    
    // 服务定义
    private val services = ConcurrentHashMap<String, McpServiceDefinition>()
    
    // 连接状态
    private val connectionStates = ConcurrentHashMap<String, McpConnectionState>()
    
    // 客户端实例
    private val clients = ConcurrentHashMap<String, MCPClientWrapper>()
    
    // 错误信息
    private val errorMessages = ConcurrentHashMap<String, String>()
    
    // 上次连接尝试时间
    private val lastConnectAttempts = ConcurrentHashMap<String, Long>()
    
    // 连接锁（防止并发连接同一服务）
    private val connectionLocks = ConcurrentHashMap<String, Mutex>()
    
    // 用户启用的服务（从外部持久化存储读取）
    private val _enabledServices = MutableStateFlow<Set<String>>(emptySet())
    val enabledServices: StateFlow<Set<String>> = _enabledServices.asStateFlow()
    
    // 连接状态变化通知
    private val _statusFlow = MutableStateFlow<List<McpConnectionStatus>>(emptyList())
    val statusFlow: StateFlow<List<McpConnectionStatus>> = _statusFlow.asStateFlow()
    
    // 状态变化监听器
    private var stateListener: McpConnectionStateListener? = null
    
    /**
     * 注册服务定义
     */
    fun registerServices(serviceDefinitions: List<McpServiceDefinition>) {
        serviceDefinitions.forEach { service ->
            services[service.id] = service
            connectionStates[service.id] = McpConnectionState.DISCONNECTED
            connectionLocks[service.id] = Mutex()
        }
        
        // 初始化启用状态
        val savedEnabled = enabledServicesProvider()
        if (savedEnabled.isEmpty()) {
            // 首次启动，使用默认配置
            _enabledServices.value = serviceDefinitions
                .filter { it.defaultEnabled }
                .map { it.id }
                .toSet()
        } else {
            _enabledServices.value = savedEnabled
        }
        
        updateStatusFlow()
        AgentLogger.normal(TAG, "Register", "services registered",
            "count" to serviceDefinitions.size,
            "enabled" to _enabledServices.value.size
        )
    }
    
    /**
     * 异步初始化 - 预连接服务
     * 
     * @param connectAll 是否连接所有启用的服务
     *                   - true: 连接所有启用的服务
     *                   - false: 仅连接 CORE 和默认启用的 STANDARD 服务（推荐）
     *                   OPTIONAL 服务始终懒加载
     */
    fun initializeAsync(connectAll: Boolean = false) {
        scope.launch {
            val servicesToConnect = if (connectAll) {
                // 连接所有启用的服务
                services.values.filter { isServiceEnabled(it.id) }
            } else {
                // 连接 CORE 服务 + 默认启用的 STANDARD 服务
                // OPTIONAL 服务始终懒加载
                services.values.filter { service ->
                    isServiceEnabled(service.id) && (
                        service.tier == McpServiceTier.CORE ||
                        (service.tier == McpServiceTier.STANDARD && service.defaultEnabled)
                    )
                }
            }
            
            AgentLogger.normal(TAG, "Initialize", "pre-connecting services",
                "count" to servicesToConnect.size,
                "connectAll" to connectAll
            )
            
            // 并行连接服务
            servicesToConnect.map { service ->
                async {
                    connectService(service.id)
                }
            }.awaitAll()
            
            AgentLogger.normal(TAG, "Initialize", "services connected",
                "connected" to clients.size
            )
        }
    }
    
    /**
     * 确保服务已连接（懒加载）
     * 
     * @param serviceId 服务 ID
     * @return 已连接的客户端，如果连接失败或服务被禁用则返回 null
     */
    suspend fun ensureConnected(serviceId: String): ToolClient? {
        // 检查服务是否启用
        if (!isServiceEnabled(serviceId)) {
            AgentLogger.verbose(TAG, "EnsureConnected", "service disabled", "serviceId" to serviceId)
            return null
        }
        
        // 已连接则直接返回
        if (connectionStates[serviceId] == McpConnectionState.CONNECTED) {
            return clients[serviceId]
        }
        
        // 获取锁，防止并发连接
        val lock = connectionLocks.getOrPut(serviceId) { Mutex() }
        
        return lock.withLock {
            // Double-check
            if (connectionStates[serviceId] == McpConnectionState.CONNECTED) {
                return@withLock clients[serviceId]
            }
            
            // 尝试连接
            val success = connectService(serviceId)
            if (success) clients[serviceId] else null
        }
    }
    
    /**
     * 获取所有已连接的客户端
     */
    fun getConnectedClients(): List<ToolClient> {
        return services.keys
            .filter { isServiceEnabled(it) && connectionStates[it] == McpConnectionState.CONNECTED }
            .mapNotNull { clients[it] }
    }
    
    /**
     * 获取服务状态列表
     */
    fun getServiceStatuses(): List<McpConnectionStatus> {
        return services.values.map { service ->
            McpConnectionStatus(
                serviceId = service.id,
                serviceName = service.displayName,
                tier = service.tier,
                state = connectionStates[service.id] ?: McpConnectionState.DISCONNECTED,
                enabled = isServiceEnabled(service.id),
                errorMessage = errorMessages[service.id],
                lastConnectAttempt = lastConnectAttempts[service.id]
            )
        }
    }
    
    /**
     * 启用/禁用服务
     */
    fun setServiceEnabled(serviceId: String, enabled: Boolean) {
        val currentEnabled = _enabledServices.value.toMutableSet()
        val changed: Boolean
        
        if (enabled) {
            changed = currentEnabled.add(serviceId)
        } else {
            changed = currentEnabled.remove(serviceId)
            // 禁用时断开连接
            scope.launch {
                disconnectService(serviceId)
            }
        }
        
        if (changed) {
            _enabledServices.value = currentEnabled
            onEnabledServicesChanged(currentEnabled)
            updateStatusFlow()
            
            // 通知状态变化（用于触发工具列表刷新）
            // 启用时通知 DISCONNECTED（等待连接），禁用时通知 DISCONNECTED（已断开）
            stateListener?.onConnectionStateChanged(serviceId, McpConnectionState.DISCONNECTED)
            
            AgentLogger.normal(TAG, "ServiceEnabled", "service enabled state changed",
                "serviceId" to serviceId,
                "enabled" to enabled
            )
        }
    }
    
    /**
     * 检查服务是否启用
     */
    fun isServiceEnabled(serviceId: String): Boolean {
        return serviceId in _enabledServices.value
    }
    
    /**
     * 手动重连服务
     */
    suspend fun reconnectService(serviceId: String): Boolean {
        if (!isServiceEnabled(serviceId)) return false
        
        disconnectService(serviceId)
        return connectService(serviceId)
    }
    
    /**
     * 设置状态监听器
     */
    fun setStateListener(listener: McpConnectionStateListener?) {
        this.stateListener = listener
    }
    
    /**
     * 释放所有资源
     * 
     * 断开所有 MCP 服务连接，并关闭共享的 HttpClient 连接池。
     * 应在应用退出或 ViewModel 销毁时调用。
     */
    suspend fun release() {
        services.keys.forEach { serviceId ->
            disconnectService(serviceId)
        }
        clients.clear()
        connectionStates.clear()
        errorMessages.clear()
        
        // 关闭共享的 HttpClient 连接池
        SharedHttpClientProvider.shutdown()
    }
    
    // === Private Methods ===
    
    private suspend fun connectService(serviceId: String): Boolean {
        val service = services[serviceId] ?: return false

        connectionStates[serviceId] = McpConnectionState.CONNECTING
        lastConnectAttempts[serviceId] = System.currentTimeMillis()
        errorMessages.remove(serviceId)
        updateStatusFlow()
        stateListener?.onConnectionStateChanged(serviceId, McpConnectionState.CONNECTING)

        var lastError: Throwable? = null
        var lastErrorMessage = "Unknown error"

        CONNECT_RETRY_TIMEOUTS_MS.forEachIndexed { index, timeoutMs ->
            val attempt = index + 1
            lastConnectAttempts[serviceId] = System.currentTimeMillis()
            updateStatusFlow()

            AgentLogger.normal(
                TAG,
                "Connect",
                "connecting service attempt",
                "serviceId" to serviceId,
                "attempt" to "$attempt/${CONNECT_RETRY_TIMEOUTS_MS.size}",
                "timeoutMs" to timeoutMs
            )

            try {
                val (client, result) = withTimeout(timeoutMs) {
                    val created = MCPClientWrapper(
                        clientName = "lightway-$serviceId",
                        clientVersion = "1.0.0"
                    )
                    created.setToolCallTimeoutMs(service.toolCallTimeoutMs)
                    created to created.connect(service.config)
                }

                if (result.isSuccess) {
                    client.setServiceInfo(service.id, service.displayName)
                    if (service.toolFilter != ToolFilterConfig()) {
                        client.setToolFilter(service.toolFilter)
                    }

                    clients[serviceId] = client
                    connectionStates[serviceId] = McpConnectionState.CONNECTED
                    errorMessages.remove(serviceId)
                    updateStatusFlow()
                    stateListener?.onConnectionStateChanged(serviceId, McpConnectionState.CONNECTED)

                    AgentLogger.normal(
                        TAG,
                        "Connect",
                        "service connected",
                        "serviceId" to serviceId,
                        "attempt" to attempt,
                        "tools" to client.listTools().size
                    )
                    return true
                }

                lastError = result.exceptionOrNull()
                lastErrorMessage = redactUrlsInMessage(lastError?.message)
                runCatching { client.disconnect() }
                AgentLogger.normal(
                    TAG,
                    "Connect",
                    "service connect attempt failed",
                    "serviceId" to serviceId,
                    "attempt" to attempt,
                    "error" to lastErrorMessage
                )
            } catch (e: TimeoutCancellationException) {
                lastError = e
                lastErrorMessage = "连接超时(${timeoutMs / 1000}秒)"
                AgentLogger.normal(
                    TAG,
                    "Connect",
                    "service connect attempt timeout",
                    "serviceId" to serviceId,
                    "attempt" to attempt,
                    "timeoutMs" to timeoutMs
                )
            } catch (e: Exception) {
                lastError = e
                lastErrorMessage = redactUrlsInMessage(e.message)
                AgentLogger.normal(
                    TAG,
                    "Connect",
                    "service connect attempt error",
                    "serviceId" to serviceId,
                    "attempt" to attempt,
                    "error" to lastErrorMessage
                )
            }
        }

        connectionStates[serviceId] = McpConnectionState.FAILED
        errorMessages[serviceId] = lastErrorMessage
        updateStatusFlow()
        stateListener?.onConnectionStateChanged(serviceId, McpConnectionState.FAILED)
        AgentLogger.error(
            TAG,
            "Connect",
            "service connection failed after retries",
            null,
            "serviceId" to serviceId,
            "retries" to CONNECT_RETRY_TIMEOUTS_MS.size,
            "errorType" to (lastError?.javaClass?.simpleName ?: "unknown"),
            "finalError" to lastErrorMessage
        )
        return false
    }
    
    private suspend fun disconnectService(serviceId: String) {
        clients[serviceId]?.let { client ->
            try {
                client.disconnect()
            } catch (e: Exception) {
                AgentLogger.error(TAG, "Disconnect", "error disconnecting (${e.javaClass.simpleName})", null,
                    "serviceId" to serviceId
                )
            }
        }
        
        clients.remove(serviceId)
        connectionStates[serviceId] = McpConnectionState.DISCONNECTED
        errorMessages.remove(serviceId)
        updateStatusFlow()
        stateListener?.onConnectionStateChanged(serviceId, McpConnectionState.DISCONNECTED)
    }
    
    private fun updateStatusFlow() {
        _statusFlow.value = getServiceStatuses()
    }
}

/**
 * 连接状态变化监听器
 */
interface McpConnectionStateListener {
    fun onConnectionStateChanged(serviceId: String, state: McpConnectionState)
}
