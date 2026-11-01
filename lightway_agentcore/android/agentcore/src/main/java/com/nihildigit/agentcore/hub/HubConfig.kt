package com.nihildigit.agentcore.hub

/**
 * Hub 配置
 */
data class HubConfig(
    /**
     * 是否启用 Hub 层
     * 禁用时不注册任何 Combo 工具，仅保留原始 MCP 工具
     */
    val hubEnabled: Boolean = true,
    
    /**
     * Combo 工具优先级（默认 100）
     * 值越大排序越靠前，原始 MCP 工具默认为 0
     */
    val comboPriority: Int = 100,
    
    /**
     * 默认搜索半径（米）
     * 用于 find_nearby 等工具未指定 radius 时
     */
    val defaultSearchRadius: Int = 10000,
    
    /**
     * POI 搜索最大返回数量
     * 用于精简 find_nearby 等工具的输出
     */
    val maxPOIResults: Int = 3,
    
    /**
     * 火车票最大返回数量
     */
    val maxTrainResults: Int = 5,
    
    /**
     * 航班最大返回数量
     */
    val maxFlightResults: Int = 5,
    
    /**
     * 打车估价车型最大显示数量
     */
    val maxTaxiProducts: Int = 3,
    
    /**
     * MCP 连接超时时间（毫秒）
     */
    val connectionTimeoutMs: Long = 10_000,
    
    /**
     * 工具调用超时时间（毫秒）
     */
    val toolCallTimeoutMs: Long = 30_000,

    /**
     * 云端小抄 API Key（为空则禁用云端小抄，仅使用本地降级小抄）
     */
    val cloudCheatsheetApiKey: String? = null,

    /**
     * 云端小抄模型名（DashScope）
     */
    val cloudCheatsheetModel: String = "qwen3-max",

    /**
     * 云端小抄超时时间（毫秒）
     */
    val cloudCheatsheetTimeoutMs: Long = 60_000,

    /**
     * 云端 API Key（用于 CloudBridge 工具：describe_camera, describe_screen）
     * 为空则禁用云端工具
     */
    val cloudApiKey: String = "",

    /**
     * 云端视觉模型名（DashScope，用于 describe_camera / describe_screen）
     */
    val cloudVisionModel: String = "qwen-vl-max",

    /**
     * 云端视觉 API 基础 URL
     */
    val cloudVisionBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",

    /**
     * 视觉工具冷却时间（毫秒）
     */
    val visionCooldownMs: Long = 10_000,

    /**
     * 视觉工具退避时间（毫秒，失败后）
     */
    val visionBackoffMs: Long = 60_000
)
