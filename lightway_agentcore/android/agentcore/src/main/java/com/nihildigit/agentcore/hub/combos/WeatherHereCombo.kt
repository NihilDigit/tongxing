package com.nihildigit.agentcore.hub.combos

import com.nihildigit.agentcore.hub.*
import com.nihildigit.agentcore.model.ChatMessage
import com.nihildigit.agentcore.model.ToolType
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.serialization.json.*

/**
 * 当前天气 Combo
 * 
 * 输入：无（自动使用当前位置）
 * 流程：maps_regeocode(current) → maps_weather(city)
 * 输出：天气状况、温度、出行建议
 */
class WeatherHereCombo(
    connectionManager: McpConnectionManager,
    contextProvider: ContextProvider,
    config: HubConfig
) : ComboTool(connectionManager, contextProvider, config) {
    
    companion object {
        private const val TAG = "WeatherHereCombo"
    }
    
    override val name = "weather_here"
    
    override val description = "查询当前位置天气"
    
    override val group = ComboGroup.LIFE

    override val toolType: ToolType = ToolType.ACTION

    override val renewalSeconds: Int = 30
    
    override val parametersSchema: JsonElement = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            // 无参数
        }
    }
    
    override val requiredServices = listOf("amap-maps")
    
    override suspend fun execute(
        arguments: Map<String, Any?>,
        history: List<ChatMessage>
    ): ComboToolResult {
        // 检查位置权限
        checkLocationPermission()?.let { return it }
        
        val currentLocation = getCurrentLocationString()
            ?: return ComboToolResult.failure("无法获取当前位置")
        
        val client = ensureServiceConnected("amap-maps")
            ?: return ComboToolResult.failure("高德地图服务未连接")
        
        // Extract user's original query from history
        val userQuery = history.lastOrNull { it.role == "user" }?.content
        
        try {
            // Step 1: 逆地理编码获取城市
            AgentLogger.verbose(TAG, "Execute", "reverse geocoding",
                "location" to currentLocation
            )
            
            val regeoResult = client.callTool(
                "maps_regeocode",
                mapOf("location" to currentLocation),
                history
            )
            
            if (regeoResult.isFailure) {
                return ComboToolResult.failure("无法获取当前城市: ${regeoResult.exceptionOrNull()?.message}")
            }
            
            val regeoJson = regeoResult.getOrNull() ?: return ComboToolResult.failure("逆地理编码返回空结果")
            val city = extractCityFromRegeocode(regeoJson)
                ?: return ComboToolResult.failure("无法解析当前城市")
            
            // Step 2: 查询天气
            AgentLogger.verbose(TAG, "Execute", "querying weather",
                "city" to city
            )
            
            val weatherResult = client.callTool(
                "maps_weather",
                mapOf("city" to city),
                history
            )
            
            if (weatherResult.isFailure) {
                return ComboToolResult.failure("天气查询失败: ${weatherResult.exceptionOrNull()?.message}")
            }
            
            val weatherJson = weatherResult.getOrNull() ?: return ComboToolResult.failure("天气查询返回空结果")
            val distilled = distillWeatherResult(weatherJson, city, userQuery)
            
            AgentLogger.normal(TAG, "Execute", "weather query complete",
                "city" to city,
                "result" to distilled.take(100)
            )
            
            return ComboToolResult.success(distilled, weatherJson)
            
        } catch (e: Exception) {
            AgentLogger.error(TAG, "Execute", "weather query failed", e)
            return ComboToolResult.failure(e.message ?: "天气查询失败")
        }
    }
    
    private fun distillWeatherResult(weatherJson: String, city: String, userQuery: String?): String {
        return try {
            val json = Json.parseToJsonElement(weatherJson)
            val jsonObj = json.jsonObject
            
            // Try "lives" (real-time weather) format first
            val lives = jsonObj["lives"]?.jsonArray?.firstOrNull()?.jsonObject
            if (lives != null) {
                return distillLivesWeather(lives, city, userQuery)
            }
            
            // Try "forecasts" (weather forecast) format
            val forecasts = jsonObj["forecasts"]?.jsonArray
            val cityName = jsonObj["city"]?.jsonPrimitive?.contentOrNull ?: city
            if (!forecasts.isNullOrEmpty()) {
                return distillForecastWeather(forecasts, cityName, userQuery)
            }
            
            "$city 天气信息暂时无法获取"
        } catch (e: Exception) {
            AgentLogger.error(TAG, "DistillWeather", "failed to parse weather", e)
            "$city 天气查询完成"
        }
    }
    
    private fun distillLivesWeather(lives: JsonObject, city: String, userQuery: String?): String {
        val weather = lives["weather"]?.jsonPrimitive?.content ?: "未知"
        val temperature = lives["temperature"]?.jsonPrimitive?.content ?: "未知"
        val humidity = lives["humidity"]?.jsonPrimitive?.content
        val windDirection = lives["winddirection"]?.jsonPrimitive?.content
        val windPower = lives["windpower"]?.jsonPrimitive?.content
        
        return buildString {
            append("$city 现在$weather，$temperature°C")
            
            if (!humidity.isNullOrBlank()) {
                append("，湿度$humidity%")
            }
            
            if (!windDirection.isNullOrBlank() && windDirection != "无风向") {
                append("，${windDirection}风")
                if (!windPower.isNullOrBlank() && windPower != "≤3") {
                    append("${windPower}级")
                }
            }
            append("。")
        }
    }
    
    private fun distillForecastWeather(forecasts: JsonArray, city: String, userQuery: String?): String {
        val today = forecasts.firstOrNull()?.jsonObject
            ?: return "$city 天气预报暂时无法获取"
        
        val dayWeather = today["dayweather"]?.jsonPrimitive?.content ?: "未知"
        val nightWeather = today["nightweather"]?.jsonPrimitive?.content ?: "未知"
        val dayTemp = today["daytemp"]?.jsonPrimitive?.content ?: "未知"
        val nightTemp = today["nighttemp"]?.jsonPrimitive?.content ?: "未知"
        val dayWind = today["daywind"]?.jsonPrimitive?.content
        val dayPower = today["daypower"]?.jsonPrimitive?.content
        
        return buildString {
            append("$city 今天$dayWeather")
            if (nightWeather != dayWeather) {
                append("转$nightWeather")
            }
            append("，${nightTemp}到${dayTemp}度")
            
            if (!dayWind.isNullOrBlank() && dayWind != "无风向") {
                append("，${dayWind}风")
                if (!dayPower.isNullOrBlank() && dayPower != "≤3") {
                    append("${dayPower}级")
                }
            }
            append("。")
            
            // Add tomorrow's forecast if available
            if (forecasts.size > 1) {
                val tomorrow = forecasts[1].jsonObject
                val tomorrowWeather = tomorrow["dayweather"]?.jsonPrimitive?.content
                val tomorrowDayTemp = tomorrow["daytemp"]?.jsonPrimitive?.content
                val tomorrowNightTemp = tomorrow["nighttemp"]?.jsonPrimitive?.content
                if (tomorrowWeather != null) {
                    append("明天$tomorrowWeather，${tomorrowNightTemp}到${tomorrowDayTemp}度。")
                }
            }
        }
    }

    // 移除 generateWeatherSuggestion 函数
}
