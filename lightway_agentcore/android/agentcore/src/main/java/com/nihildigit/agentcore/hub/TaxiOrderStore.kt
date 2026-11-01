package com.nihildigit.agentcore.hub

/**
 * 进程内的打车订单状态存储（极简）。
 *
 * 用途：
 * - `call_taxi` 成功后缓存最近一次 `orderId`
 * - 仅维护最新订单号，用于 `taxi_status` / `cancel_taxi` 内部调用
 *
 * 注意：这是调试期的轻量实现，不做持久化；应用重启后会丢失。
 */
object TaxiOrderStore {
    private val lock = Any()

    @Volatile
    private var lastOrderId: String? = null

    fun setLastOrderId(orderId: String?) {
        val normalized = orderId?.trim()?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            lastOrderId = normalized
        }
    }

    fun getLastOrderId(): String? = lastOrderId

    fun clear() {
        synchronized(lock) {
            lastOrderId = null
        }
    }
}
