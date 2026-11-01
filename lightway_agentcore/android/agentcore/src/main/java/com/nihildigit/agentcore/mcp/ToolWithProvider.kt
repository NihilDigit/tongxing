package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.model.Tool

/**
 * 带来源信息的工具
 */
data class ToolWithProvider(
    val tool: Tool,
    val provider: ToolProvider,
    val serviceId: String?,      // MCP 服务 ID（如 "baidu-maps"）
    val serviceName: String?     // 显示名称（如 "百度地图"）
)