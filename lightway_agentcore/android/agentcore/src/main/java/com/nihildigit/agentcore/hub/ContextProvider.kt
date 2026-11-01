package com.nihildigit.agentcore.hub

import android.location.Location
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 上下文提供者接口
 * 
 * 为 Combo 工具提供运行时上下文信息，如 GPS 位置、当前时间、用户偏好等。
 * Combo 工具无需 LLM 显式传入这些参数。
 */
interface ContextProvider {

    /**
     * 获取当前 GPS 位置
     *
     * @return 位置信息，如果 GPS 权限未授予或获取失败则返回 null
     */
    fun getCurrentLocation(): Location?

    /**
     * 获取当前时间
     */
    fun getCurrentTime(): Instant

    /**
     * 获取当前日期（本地时区）
     */
    fun getCurrentDate(): LocalDate = getCurrentTime()
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    /**
     * 获取当前日期字符串（YYYY-MM-DD 格式）
     */
    fun getCurrentDateString(): String = getCurrentDate().toString()

    /**
     * 获取用户偏好
     */
    fun getUserPreferences(): UserPreferences

    /**
     * 检查是否有位置权限
     */
    fun hasLocationPermission(): Boolean

    /**
     * 获取最新相机帧的 Base64 编码（JPEG）
     * @return Base64 字符串，如果相机不可用则返回 null
     */
    fun getCameraBase64(): String? = null

    /**
     * 获取屏幕截图的 Base64 编码（JPEG）
     * 此方法可能需要在协程中调用（如无障碍截图），
     * 因此标记为 suspend。
     * @return Base64 字符串，如果截图不可用则返回 null
     */
    suspend fun getScreenshotBase64(): String? = null
}

/**
 * 用户偏好设置
 */
data class UserPreferences(
    /**
     * 语言（如 "zh-CN"）
     */
    val language: String = "zh-CN",
    
    /**
     * 距离单位（metric/imperial）
     */
    val distanceUnit: String = "metric",
    
    /**
     * 温度单位（celsius/fahrenheit）
     */
    val temperatureUnit: String = "celsius",
    
    /**
     * 默认出行方式（driving/walking/transit）
     */
    val defaultTravelMode: String = "driving"
)

/**
 * 无操作上下文提供者（用于测试或无 Android 环境）
 */
class NoopContextProvider : ContextProvider {
    override fun getCurrentLocation(): Location? = null
    override fun getCurrentTime(): Instant = Instant.now()
    override fun getUserPreferences(): UserPreferences = UserPreferences()
    override fun hasLocationPermission(): Boolean = false
}
