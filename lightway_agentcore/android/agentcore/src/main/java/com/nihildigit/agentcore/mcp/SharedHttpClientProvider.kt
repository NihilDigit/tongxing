package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.logging.AgentLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * 共享 HttpClient 提供者
 * 
 * 为所有 MCP 客户端提供共享的 Ktor HttpClient 实例，优化连接池复用和资源管理。
 * 
 * 功能：
 * - 单例模式，确保全局共享同一 HttpClient 实例
 * - 配置合理的超时参数
 * - 支持 SSE 插件（用于 Streamable HTTP 传输）
 * - 支持 WebSocket 插件（用于 WebSocket 传输）
 * - 提供 shutdown() 方法用于应用退出时清理资源
 * 
 * 超时配置（基于 spec 规范）：
 * - connectTimeout = 10 秒
 * - socketTimeout = 60 秒
 * - requestTimeout = 60 秒
 * 
 * 使用方式：
 * ```kotlin
 * // 获取共享的 SSE HttpClient
 * val sseClient = SharedHttpClientProvider.getSseClient()
 * 
 * // 获取共享的 WebSocket HttpClient
 * val wsClient = SharedHttpClientProvider.getWebSocketClient()
 * 
 * // 应用退出时关闭
 * SharedHttpClientProvider.shutdown()
 * ```
 */
object SharedHttpClientProvider {
    
    private const val TAG = "SharedHttpClientProvider"
    
    // 超时配置常量
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SOCKET_TIMEOUT_MS = 60_000L
    private const val REQUEST_TIMEOUT_MS = 60_000L
    
    // 连接池配置常量
    private const val MAX_CONNECTIONS_PER_ROUTE = 10
    private const val MAX_CONNECTIONS_COUNT = 50
    private const val KEEP_ALIVE_TIME_MS = 60_000L
    
    // 保护 HttpClient 实例的互斥锁
    private val mutex = Mutex()
    
    // SSE HttpClient 实例（用于 Streamable HTTP）
    @Volatile
    private var sseClient: HttpClient? = null
    
    // WebSocket HttpClient 实例
    @Volatile
    private var webSocketClient: HttpClient? = null
    
    /**
     * 获取共享的 SSE HttpClient 实例
     * 
     * 使用 CIO 引擎，配置 SSE 插件、超时参数和连接池。
     * 首次调用时创建实例，后续调用返回同一实例。
     * 
     * @return 配置好的 HttpClient 实例
     */
    suspend fun getSseClient(): HttpClient {
        // Fast path: 已创建则直接返回
        sseClient?.let { return it }
        
        return mutex.withLock {
            // Double-check
            sseClient?.let { return@withLock it }
            
            AgentLogger.normal(TAG, "CreateSseClient", "creating shared SSE HttpClient",
                "connectTimeout" to CONNECT_TIMEOUT_MS,
                "socketTimeout" to SOCKET_TIMEOUT_MS,
                "requestTimeout" to REQUEST_TIMEOUT_MS,
                "maxConnectionsPerRoute" to MAX_CONNECTIONS_PER_ROUTE,
                "maxConnectionsCount" to MAX_CONNECTIONS_COUNT,
                "keepAliveTime" to KEEP_ALIVE_TIME_MS
            )
            
            val client = HttpClient(CIO) {
                // 配置 CIO 引擎的连接池参数
                engine {
                    // 总最大连接数
                    maxConnectionsCount = MAX_CONNECTIONS_COUNT
                    endpoint {
                        // 每个主机的最大连接数
                        maxConnectionsPerRoute = MAX_CONNECTIONS_PER_ROUTE
                        // 连接超时（毫秒）
                        connectTimeout = CONNECT_TIMEOUT_MS
                        // 空闲连接保持时间（毫秒）
                        keepAliveTime = KEEP_ALIVE_TIME_MS
                        // Socket 超时（毫秒）
                        socketTimeout = SOCKET_TIMEOUT_MS
                    }
                }
                
                // 配置 HTTP 层超时（作为备份）
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = SOCKET_TIMEOUT_MS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
                
                // 启用 SSE 插件（Streamable HTTP 必需）
                install(SSE)
            }
            
            sseClient = client
            AgentLogger.normal(TAG, "CreateSseClient", "shared SSE HttpClient created")
            client
        }
    }
    
    /**
     * 获取共享的 WebSocket HttpClient 实例
     * 
     * 使用 OkHttp 引擎，配置 WebSocket 插件。
     * 首次调用时创建实例，后续调用返回同一实例。
     * 
     * @return 配置好的 HttpClient 实例
     */
    suspend fun getWebSocketClient(): HttpClient {
        // Fast path: 已创建则直接返回
        webSocketClient?.let { return it }
        
        return mutex.withLock {
            // Double-check
            webSocketClient?.let { return@withLock it }
            
            AgentLogger.normal(TAG, "CreateWsClient", "creating shared WebSocket HttpClient")
            
            val client = HttpClient(OkHttp) {
                // 配置 OkHttp 引擎的连接参数
                engine {
                    config {
                        connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        readTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        writeTimeout(SOCKET_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                }
                
                // 启用 WebSocket 插件
                install(WebSockets)
            }
            
            webSocketClient = client
            AgentLogger.normal(TAG, "CreateWsClient", "shared WebSocket HttpClient created")
            client
        }
    }
    
    /**
     * 关闭所有共享 HttpClient 实例
     * 
     * 应在应用退出或 ViewModel 销毁时调用。
     * 关闭后，下次调用 getXxxClient() 会创建新实例。
     * 此方法是幂等的，多次调用安全。
     */
    suspend fun shutdown() {
        mutex.withLock {
            AgentLogger.normal(TAG, "Shutdown", "shutting down shared HttpClients")
            
            // 关闭 SSE HttpClient
            sseClient?.let { client ->
                try {
                    client.close()
                    AgentLogger.normal(TAG, "Shutdown", "SSE HttpClient closed")
                } catch (e: Exception) {
                    AgentLogger.error(TAG, "Shutdown", "error closing SSE HttpClient", e)
                }
            }
            sseClient = null
            
            // 关闭 WebSocket HttpClient
            webSocketClient?.let { client ->
                try {
                    client.close()
                    AgentLogger.normal(TAG, "Shutdown", "WebSocket HttpClient closed")
                } catch (e: Exception) {
                    AgentLogger.error(TAG, "Shutdown", "error closing WebSocket HttpClient", e)
                }
            }
            webSocketClient = null
            
            AgentLogger.normal(TAG, "Shutdown", "all shared HttpClients shut down")
        }
    }
    
    /**
     * 检查 SSE HttpClient 是否已创建
     * 
     * 用于测试和调试
     */
    fun isSseClientCreated(): Boolean = sseClient != null
    
    /**
     * 检查 WebSocket HttpClient 是否已创建
     * 
     * 用于测试和调试
     */
    fun isWebSocketClientCreated(): Boolean = webSocketClient != null
}
