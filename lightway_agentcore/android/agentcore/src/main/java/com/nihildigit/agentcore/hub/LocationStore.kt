package com.nihildigit.agentcore.hub

/**
 * 进程内的短 LID → POI 映射存储。
 *
 * 每次 find_nearby 返回结果后调用 [update]，
 * navigate_to / call_taxi 通过 [resolve] 将 "L1" 等短 ID 解析为真实 POI 信息。
 */
object LocationStore {
    private val lock = Any()

    @Volatile
    private var currentMap: LinkedHashMap<String, PoiEntry> = linkedMapOf()

    data class PoiEntry(val poiId: String, val name: String, val distanceMeters: Int?)

    fun update(entries: List<PoiEntry>) {
        synchronized(lock) {
            currentMap = linkedMapOf<String, PoiEntry>().apply {
                entries.forEachIndexed { i, entry ->
                    put("L${i + 1}", entry)
                }
            }
        }
    }

    fun resolve(lid: String): PoiEntry? {
        val snapshot = currentMap
        // 精确匹配: L1, L2, L3
        snapshot[lid.uppercase()]?.let { return it }
        // 数字匹配: "1" → "L1"
        lid.trim().toIntOrNull()?.let { n ->
            return snapshot["L$n"]
        }
        // 名称模糊匹配: "协和" → L1(北京协和医院)
        val trimmed = lid.trim()
        if (trimmed.length >= 2) {
            for ((_, entry) in snapshot) {
                if (entry.name.contains(trimmed)) return entry
            }
        }
        return null
    }

    fun clear() {
        synchronized(lock) { currentMap = linkedMapOf() }
    }

    fun isEmpty(): Boolean = currentMap.isEmpty()
}
