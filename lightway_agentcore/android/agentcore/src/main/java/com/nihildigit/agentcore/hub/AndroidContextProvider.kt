package com.nihildigit.agentcore.hub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.nihildigit.agentcore.logging.AgentLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Android 实现的上下文提供者
 * 
 * 使用原生 LocationManager 获取 GPS 位置（不依赖 Google Play Services），
 * 支持国内环境，支持权限检查和降级处理。
 * 
 * 使用方式：
 * ```kotlin
 * val contextProvider = AndroidContextProvider(applicationContext)
 * contextProvider.initialize()  // 可选：预热位置
 * 
 * val location = contextProvider.getCurrentLocation()
 * ```
 */
open class AndroidContextProvider(
    private val context: Context,
    private val preferences: UserPreferences = UserPreferences()
) : ContextProvider {
    
    companion object {
        private const val TAG = "AndroidContextProvider"
        private const val LOCATION_TIMEOUT_MS = 10000L // 10秒超时
    }
    
    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    // 缓存的位置（减少频繁的 GPS 请求）
    @Volatile
    private var cachedLocation: Location? = null
    @Volatile
    private var cachedLocationTimestamp: Long = 0
    private val locationCacheValidityMs = 60_000L // 1分钟缓存
    
    /**
     * 初始化并预热位置
     * 
     * 在应用启动时调用，可以提前获取位置，
     * 减少首次 Combo 调用的延迟。
     */
    suspend fun initialize() {
        if (hasLocationPermission()) {
            AgentLogger.verbose(TAG, "Initialize", "pre-warming location")
            fetchLocation()
        }
    }
    
    override fun getCurrentLocation(): Location? {
        // 检查缓存是否有效
        val now = System.currentTimeMillis()
        val cached = cachedLocation
        if (cached != null && (now - cachedLocationTimestamp) < locationCacheValidityMs) {
            return cached
        }
        
        // 缓存过期或不存在，尝试获取最后已知位置
        if (!hasLocationPermission()) {
            AgentLogger.verbose(TAG, "GetLocation", "no location permission")
            return null
        }
        
        return try {
            getLastKnownLocation()?.also { location ->
                cachedLocation = location
                cachedLocationTimestamp = now
            }
        } catch (e: Exception) {
            AgentLogger.error(TAG, "GetLocation", "failed to get last location", e)
            null
        }
    }
    
    /**
     * 获取最后已知位置
     * 优先使用 GPS，然后是网络定位
     */
    @Suppress("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        
        var bestLocation: Location? = null
        
        for (provider in providers) {
            if (!locationManager.isProviderEnabled(provider)) continue
            
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    // 选择最新的位置
                    if (bestLocation == null || location.time > bestLocation.time) {
                        bestLocation = location
                    }
                }
            } catch (e: SecurityException) {
                AgentLogger.verbose(TAG, "GetLastKnown", "no permission for $provider")
            } catch (e: Exception) {
                AgentLogger.error(TAG, "GetLastKnown", "error getting location from $provider", e)
            }
        }
        
        return bestLocation
    }
    
    /**
     * 异步获取位置（更准确但需要等待）
     */
    suspend fun fetchLocation(): Location? {
        if (!hasLocationPermission()) {
            AgentLogger.verbose(TAG, "FetchLocation", "no location permission")
            return null
        }
        
        return withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            requestSingleLocationUpdate()
        } ?: getLastKnownLocation() // 超时时降级到最后已知位置
    }
    
    /**
     * 请求单次位置更新
     */
    @Suppress("MissingPermission")
    private suspend fun requestSingleLocationUpdate(): Location? {
        // 优先使用 GPS，否则使用网络定位
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> 
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> 
                LocationManager.NETWORK_PROVIDER
            else -> {
                AgentLogger.verbose(TAG, "FetchLocation", "no location provider enabled")
                return null
            }
        }
        
        return suspendCancellableCoroutine { continuation ->
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    cachedLocation = location
                    cachedLocationTimestamp = System.currentTimeMillis()
                    AgentLogger.verbose(TAG, "FetchLocation", "location obtained",
                        "provider" to provider,
                        "lat" to location.latitude,
                        "lng" to location.longitude,
                        "accuracy" to location.accuracy
                    )
                    locationManager.removeUpdates(this)
                    continuation.resume(location)
                }
                
                @Deprecated("Deprecated in API 29", ReplaceWith(""))
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                    // Deprecated but required for older APIs
                }
                
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    AgentLogger.verbose(TAG, "FetchLocation", "provider disabled: $provider")
                }
            }
            
            continuation.invokeOnCancellation {
                locationManager.removeUpdates(locationListener)
            }
            
            try {
                // 请求位置更新
                locationManager.requestSingleUpdate(
                    provider,
                    locationListener,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                AgentLogger.error(TAG, "FetchLocation", "security exception", e)
                continuation.resume(null)
            } catch (e: Exception) {
                AgentLogger.error(TAG, "FetchLocation", "exception requesting location", e)
                continuation.resume(null)
            }
        }
    }
    
    override fun getCurrentTime(): Instant = Instant.now()
    
    override fun getUserPreferences(): UserPreferences = preferences
    
    override fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 检查 GPS 是否可用
     */
    fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    
    /**
     * 检查网络定位是否可用
     */
    fun isNetworkLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    
    /**
     * 清除位置缓存
     */
    fun clearLocationCache() {
        cachedLocation = null
        cachedLocationTimestamp = 0
    }
    
    /**
     * 更新用户偏好
     */
    fun updatePreferences(newPreferences: UserPreferences): AndroidContextProvider {
        return AndroidContextProvider(context, newPreferences)
    }
}
