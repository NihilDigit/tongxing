package com.nihildigit.agentcore.mcp

import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.Tool

/**
 * 统一的工具客户端接口，兼容 MCP 客户端和本地工具桥。
 */
interface ToolClient {

    /**
     * 列出可用工具（OpenAI 函数式格式）。
     */
    fun listTools(): List<Tool>
    
    /**
     * 列出带过滤的工具（新增）
     */
    fun listTools(filter: ToolFilterConfig): List<Tool> {
        return listTools().filter { filter.isToolEnabled(it.function.name, getProvider()) }
    }

    /**
     * 调用指定工具。
     *
     * @param name 工具名
     * @param arguments LLM 传入的参数
     * @param history 当前窗口对话历史（用于按需生成上下文摘要）
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): Result<String>
    
    /**
     * 设置工具过滤配置（新增，运行时可修改）
     */
    fun setToolFilter(filter: ToolFilterConfig) { /* default no-op */ }
    
    /**
     * 获取当前过滤配置（新增）
     */
    fun getToolFilter(): ToolFilterConfig = ToolFilterConfig()
    
    /**
     * 获取工具来源类型（默认为 MCP_SERVICE）
     */
    fun getProvider(): ToolProvider = ToolProvider.MCP_SERVICE
    
    /**
     * 获取服务 ID（用于 UI 分组）
     */
    fun getServiceId(): String? = null
    
    /**
     * 获取服务显示名称（用于 UI 分组）
     */
    fun getServiceName(): String? = null

    /**
     * Session 结束回调。
     * Router 在 process() 返回前调用，用于执行延迟动作（如 Hub 的 pendingActions）。
     */
    fun onSessionComplete() { /* default no-op */ }

    /**
     * 消费待播报文本。
     * Combo 可通过此机制让应用层在 LLM 回复前先播报内容（如搜索结果列表）。
     * 调用后清空，返回 null 表示无待播报内容。
     */
    fun consumeAnnounce(): String? = null
}
