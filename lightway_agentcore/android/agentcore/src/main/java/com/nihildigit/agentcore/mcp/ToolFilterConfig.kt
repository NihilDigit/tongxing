package com.nihildigit.agentcore.mcp

/**
 * 工具过滤配置
 */
data class ToolFilterConfig(
    /** 启用的工具名列表（显式启用，覆盖默认禁用策略） */
    val enabledTools: Set<String> = emptySet(),
    /** 禁用的工具名列表（显式禁用，覆盖默认启用策略） */
    val disabledTools: Set<String> = emptySet()
) {
    /**
     * 判断工具是否应该启用
     * 
     * 优先级：
     * 1. disabledTools（显式禁用）最高优先级
     * 2. enabledTools（显式启用）次高优先级
     * 3. 如果 enabledTools 非空，则只启用白名单中的工具（严格白名单模式）
     * 4. 如果 enabledTools 为空，按来源类型应用默认策略
     */
    fun isToolEnabled(toolName: String, provider: ToolProvider): Boolean {
        // 显式禁用优先级最高
        if (toolName in disabledTools) return false
        
        // 显式启用次高优先级
        if (toolName in enabledTools) return true
        
        // 如果指定了白名单但工具不在其中，则禁用（严格白名单模式）
        if (enabledTools.isNotEmpty()) return false
        
        // 白名单为空时，按来源类型应用默认策略
        return when (provider) {
            ToolProvider.BUILT_IN -> true      // 内置工具默认启用
            ToolProvider.MCP_SERVICE -> false  // 第三方工具默认禁用
        }
    }
}