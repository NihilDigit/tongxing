package com.nihildigit.agentcore.hub

import com.nihildigit.agentcore.model.Tool
import com.nihildigit.agentcore.model.ToolFunction
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.serialization.json.*

/**
 * Combo 工具分组
 */
enum class ComboGroup(val displayName: String, val order: Int) {
    TRAVEL("出行", 1),
    TAXI("打车", 2),
    LIFE("生活", 3),
    INFO("资讯", 4),
    OTHER("其他", 99)
}

/**
 * Combo 工具基类
 * 
 * Combo 工具将多个底层 MCP 调用组合为单一操作，
 * 对 LLM 暴露简化的接口，封装底层复杂性。
 * 
 * 特点：
 * - 自动上下文注入（GPS、时间等）
 * - 输出精简（仅返回关键字段）
 * - 错误转换（技术错误→用户友好描述）
 */
abstract class ComboTool(
    protected val connectionManager: McpConnectionManager,
    protected val contextProvider: ContextProvider,
    protected val config: HubConfig
) {
    /**
     * 工具名称（唯一标识）
     */
    abstract val name: String
    
    /**
     * 工具显示名称（带 [快捷] 标记）
     */
    open val displayName: String
        get() = "[快捷] $name"
    
    /**
     * 工具描述（针对小模型优化，简洁明了）
     */
    abstract val description: String
    
    /**
     * 工具分组（用于 UI 显示）
     */
    open val group: ComboGroup = ComboGroup.OTHER
    
    /**
     * 参数 Schema（JSON Schema 格式）
     */
    abstract val parametersSchema: JsonElement?
    
    /**
     * 依赖的 MCP 服务 ID 列表
     */
    abstract val requiredServices: List<String>
    
    /**
     * 工具类型（默认 INTERMEDIATE）
     */
    open val toolType: ToolType = ToolType.INTERMEDIATE
    
    /**
     * 工具优先级（越大越靠前，Combo 默认 100）
     */
    open val priority: Int
        get() = config.comboPriority

    /**
     * 续约秒数（用于延长 Stem 超时）。
     * null 表示使用系统默认值。
     */
    open val renewalSeconds: Int? = null
    
    /**
     * 检查工具是否可用（所有依赖服务都已启用）
     */
    fun isAvailable(): Boolean {
        return requiredServices.all { connectionManager.isServiceEnabled(it) }
    }
    
    /**
     * 执行工具调用
     * 
     * @param arguments LLM 传入的参数
     * @param history 对话历史（可选）
     * @return 执行结果
     */
    abstract suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage> = emptyList()
    ): ComboToolResult
    
    /**
     * 转换为 Tool 对象（用于 Router）
     */
    fun toTool(): Tool {
        return Tool(
            type = "function",
            function = ToolFunction(
                name = name,
                description = description,
                parameters = parametersSchema
            ),
            toolType = toolType,
            priority = priority,
            renewalSeconds = renewalSeconds ?: -1
        )
    }
    
    // === Helper Methods ===
    
    /**
     * 获取当前位置坐标字符串（lng,lat 格式）
     */
    protected fun getCurrentLocationString(): String? {
        val location = contextProvider.getCurrentLocation() ?: return null
        return "${location.longitude},${location.latitude}"
    }
    
    /**
     * 检查位置权限，返回友好错误
     */
    protected fun checkLocationPermission(): ComboToolResult? {
        if (!contextProvider.hasLocationPermission()) {
            return ComboToolResult.failure("需要位置权限才能使用此功能")
        }
        if (contextProvider.getCurrentLocation() == null) {
            return ComboToolResult.failure("无法获取当前位置，请确保 GPS 已开启")
        }
        return null
    }
    
    /**
     * 确保服务已连接
     */
    protected suspend fun ensureServiceConnected(serviceId: String): com.nihildigit.agentcore.mcp.ToolClient? {
        return connectionManager.ensureConnected(serviceId)
    }
    
    /**
     * 从 Amap MCP regeocode 响应中提取城市名
     * 
     * Amap MCP 返回格式可能是:
     * 1. 直接 JSON: {"provice": "北京市", "city": [], "district": "东城区"}
     * 2. MCP 包装: {"type": "text", "text": "{...}"}
     * 3. MCP content 数组: {"content": [{"type": "text", "text": "{...}"}]}
     * 
     * 注意: Amap 返回的字段名有拼写错误 "provice" (应为 province)
     * 直辖市的 city 字段为空数组 []
     */
    protected fun extractCityFromRegeocode(regeoJson: String): String? {
        return try {
            val json = Json.parseToJsonElement(regeoJson)
            
            // 尝试从不同的包装层级提取实际数据
            val dataJson = extractMcpTextContent(json) ?: json
            val dataObj = dataJson.jsonObject
            
            // 尝试旧格式: regeocode.addressComponent.city
            val regeocode = dataObj["regeocode"]?.jsonObject
            if (regeocode != null) {
                val addressComponent = regeocode["addressComponent"]?.jsonObject
                if (addressComponent != null) {
                    return extractCityFromAddressComponent(addressComponent)
                }
            }
            
            // 新格式: 直接的 city/provice 字段
            extractCityFromFlatStructure(dataObj)
        } catch (e: Exception) {
            AgentLogger.error("ComboTool", "ExtractCity", "failed to parse regeo response", e,
                "json" to regeoJson.take(200)
            )
            null
        }
    }
    
    /**
     * 从 MCP 响应中提取 text content
     * 处理 {"type": "text", "text": "..."} 或 {"content": [{"type": "text", "text": "..."}]} 格式
     */
    private fun extractMcpTextContent(json: JsonElement): JsonElement? {
        val obj = json.jsonObjectOrNull ?: return null
        
        // 格式1: {"type": "text", "text": "..."}
        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
            val textContent = obj["text"]?.jsonPrimitive?.contentOrNull
            if (textContent != null) {
                return try {
                    Json.parseToJsonElement(textContent)
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        // 格式2: {"content": [{"type": "text", "text": "..."}]}
        val contentArray = obj["content"]?.jsonArrayOrNull
        if (contentArray != null && contentArray.isNotEmpty()) {
            val firstContent = contentArray[0].jsonObjectOrNull
            if (firstContent?.get("type")?.jsonPrimitive?.contentOrNull == "text") {
                val textContent = firstContent["text"]?.jsonPrimitive?.contentOrNull
                if (textContent != null) {
                    return try {
                        Json.parseToJsonElement(textContent)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * 从 addressComponent 结构提取城市
     */
    private fun extractCityFromAddressComponent(addressComponent: JsonObject): String? {
        val city = addressComponent["city"]?.let { extractStringOrNull(it) }
        val province = addressComponent["province"]?.let { extractStringOrNull(it) }
        
        return when {
            !city.isNullOrBlank() -> city.removeSuffix("市")
            !province.isNullOrBlank() -> province.removeSuffix("省").removeSuffix("市")
            else -> null
        }
    }
    
    /**
     * 从扁平结构提取城市 (Amap MCP 新格式)
     * 字段: provice (注意拼写错误), city, district
     */
    private fun extractCityFromFlatStructure(obj: JsonObject): String? {
        val city = obj["city"]?.let { extractStringOrNull(it) }
        // 注意: Amap 返回的是 "provice" 而不是 "province"
        val province = obj["provice"]?.let { extractStringOrNull(it) }
            ?: obj["province"]?.let { extractStringOrNull(it) }
        
        return when {
            !city.isNullOrBlank() -> city.removeSuffix("市")
            !province.isNullOrBlank() -> province.removeSuffix("省").removeSuffix("市")
            else -> null
        }
    }
    
    /**
     * 从 JsonElement 提取字符串，处理空数组情况
     * Amap 对直辖市返回 city: [] 而不是 city: ""
     */
    private fun extractStringOrNull(element: JsonElement): String? {
        return when {
            element is JsonPrimitive && element.isString -> {
                val content = element.content
                if (content.isBlank() || content == "[]") null else content
            }
            element is JsonArray -> null  // 空数组表示无值
            else -> null
        }
    }
    
    // Extension helpers
    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = try { this.jsonObject } catch (e: Exception) { null }
    
    private val JsonElement.jsonArrayOrNull: JsonArray?
        get() = try { this.jsonArray } catch (e: Exception) { null }
}

/**
 * Combo 工具执行结果
 */
data class ComboToolResult(
    val success: Boolean,
    val content: String,
    val rawResponse: String? = null,
    val action: ComboAction? = null,
    val announceText: String? = null   // 应用层先播报的文本（不经过 LLM）
) {
    companion object {
        fun success(content: String, rawResponse: String? = null, action: ComboAction? = null, announceText: String? = null) =
            ComboToolResult(true, content, rawResponse, action, announceText)
        
        fun failure(error: String, rawResponse: String? = null) = 
            ComboToolResult(false, OutputDistiller.friendlyError(error), rawResponse)
    }
}

/**
 * Combo 后续动作
 */
sealed class ComboAction {
    /**
     * 启动外部 App（通过 URL/URI）
     * @param url 要启动的 URL 或 URI scheme
     * @param fallbackUrl 如果主 URL 无法处理，则使用备用 URL（通常是网页版）
     * @param packageName 可选的目标应用包名，用于精确指定要启动的 App
     */
    data class LaunchUrl(
        val url: String,
        val fallbackUrl: String? = null,
        val packageName: String? = null
    ) : ComboAction()

    /**
     * 停止外部导航（例如高德）
     * @param provider 导航提供方标识（如 "amap"）
     */
    data class StopNavigation(
        val provider: String = "amap"
    ) : ComboAction()
}

/**
 * Combo Action 处理器接口
 * 
 * 由 App 层实现，用于处理 Combo 返回的动作（如启动外部 App）
 */
interface ComboActionHandler {
    /**
     * 处理 Combo 动作
     * @param action 要处理的动作
     * @return 处理是否成功
     */
    fun handleAction(action: ComboAction): Boolean
}
