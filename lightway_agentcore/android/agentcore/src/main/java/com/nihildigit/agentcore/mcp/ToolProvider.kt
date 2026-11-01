package com.nihildigit.agentcore.mcp

/**
 * 工具来源类型
 */
enum class ToolProvider {
    BUILT_IN,      // 内置工具（如 describe_camera, describe_screen, ask_cloud）
    MCP_SERVICE    // 第三方 MCP 服务工具
}
