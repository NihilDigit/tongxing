package com.nihildigit.agentcore.hub

/**
 * MCP 服务连接状态
 */
enum class McpConnectionState {
    /**
     * 未连接 - 初始状态或断开后
     */
    DISCONNECTED,
    
    /**
     * 连接中 - 正在建立连接
     */
    CONNECTING,
    
    /**
     * 已连接 - 连接成功，可以使用
     */
    CONNECTED,
    
    /**
     * 连接失败 - 连接尝试失败
     */
    FAILED
}

/**
 * MCP 服务连接状态信息
 */
data class McpConnectionStatus(
    val serviceId: String,
    val serviceName: String,
    val tier: McpServiceTier,
    val state: McpConnectionState,
    val enabled: Boolean,
    val errorMessage: String? = null,
    val lastConnectAttempt: Long? = null
) {
    /**
     * 获取状态显示图标
     */
    fun getStateEmoji(): String = when {
        !enabled -> "⚫"  // 已禁用
        state == McpConnectionState.CONNECTED -> "🟢"  // 已连接
        state == McpConnectionState.CONNECTING -> "🟡"  // 连接中
        state == McpConnectionState.FAILED -> "🔴"  // 连接失败
        else -> "🟡"  // 待连接（DISCONNECTED but enabled）
    }
}
