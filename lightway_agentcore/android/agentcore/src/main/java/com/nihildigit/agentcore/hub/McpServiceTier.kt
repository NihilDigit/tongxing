package com.nihildigit.agentcore.hub

/**
 * MCP 服务分级
 * 
 * 决定连接策略和默认启用状态：
 * - CORE: 启动时预连接，默认启用（如 Amap, Bing）
 * - STANDARD: 默认启用时预连接，否则懒加载（如 12306）
 * - OPTIONAL: 懒加载，默认禁用（如打车服务）
 * 
 * 预连接策略（initializeAsync connectAll=false）：
 * - CORE 服务：始终预连接
 * - STANDARD + defaultEnabled=true：预连接
 * - STANDARD + defaultEnabled=false：懒加载
 * - OPTIONAL：始终懒加载
 */
enum class McpServiceTier {
    /**
     * 核心服务 - 启动时预连接，默认启用
     * 高频使用，是核心功能的基础
     */
    CORE,
    
    /**
     * 标准服务 - 默认启用时预连接，否则懒加载
     * 中频使用，首次调用时连接（如果未预连接）
     */
    STANDARD,
    
    /**
     * 可选服务 - 懒加载，默认禁用
     * 低频或特定场景使用
     */
    OPTIONAL
}
