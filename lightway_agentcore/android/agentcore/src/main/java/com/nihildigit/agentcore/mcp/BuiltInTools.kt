package com.nihildigit.agentcore.mcp

/**
 * 内置工具常量定义
 *
 * - describe_camera: 视觉相关问题（相机帧），调用云端视觉模型
 * - describe_screen: 屏幕相关问题（无障碍截图），调用云端视觉模型
 */
object BuiltInTools {
    /**
     * 内置工具名称列表
     */
    val TOOL_NAMES = setOf(
        "describe_camera",
        "describe_screen"
    )
}
