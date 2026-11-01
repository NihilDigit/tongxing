package com.nihildigit.agentcore.hub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parse taxi status responses (JSON or plain text) into structured status info.
 * Kept minimal to support polling + direct TTS without exposing sensitive fields.
 */
object TaxiStatusParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class StatusInfo(
        val status: String,
        val pickupDistanceKm: String? = null,
        val pickupEtaMin: String? = null
    )

    fun parse(raw: String): StatusInfo? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return parseFromText(trimmed)
        }

        return try {
            val jsonElement = json.parseToJsonElement(trimmed)

            // Handle {"type":"text","text":"..."}
            val obj = jsonElement.jsonObjectOrNull
            val topType = obj?.get("type")?.jsonPrimitive?.contentOrNull
            if (topType == "text") {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim()
                if (!text.isNullOrBlank()) return parseFromText(text)
            }

            // structuredContent
            val structured = obj?.get("structuredContent")?.jsonObjectOrNull
            if (structured != null) {
                val statusRaw = structured["status"]?.jsonPrimitive?.contentOrNull
                val normalized = normalizeStatus(statusRaw)
                if (normalized != null) return StatusInfo(status = normalized)
            }

            // content[0].text fallback
            val contentText = obj?.get("content")?.jsonArrayOrNull
                ?.firstOrNull()?.jsonObjectOrNull
                ?.get("text")?.jsonPrimitive?.contentOrNull
            if (!contentText.isNullOrBlank()) {
                return parseFromText(contentText)
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    fun formatTts(info: StatusInfo): String {
        return when (info.status) {
            "matching_driver" -> "正在为您匹配司机"
            "accepted" -> "司机已接单，正在赶来"
            "arriving" -> {
                val distance = info.pickupDistanceKm
                val eta = info.pickupEtaMin
                if (!distance.isNullOrBlank() && !eta.isNullOrBlank()) {
                    "司机距您 ${distance} 公里，预计 ${eta} 分钟到达"
                } else {
                    "司机正在赶来"
                }
            }
            "arrived_pickup" -> "司机已到达，请上车"
            "in_trip" -> "行程已开始"
            "completed" -> "行程已完成"
            "cancelled" -> "订单已取消"
            else -> "订单状态更新中"
        }
    }

    private fun parseFromText(text: String): StatusInfo? {
        val cleaned = text
            .replace("提示：您可以随时取消订单", "")
            .replace("提示：请用叫车人的手机号来拨打司机的电话。", "")
            .trim()

        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        val joined = lines.joinToString("\n")

        val pickupEta = Regex("""还有：\s*([0-9.]+)\s*公里，约需\s*(\d+)\s*分钟""").find(joined)
        val pickupKm = pickupEta?.groupValues?.getOrNull(1)
        val pickupMin = pickupEta?.groupValues?.getOrNull(2)

        val status = when {
            joined.contains("行程已完成") || joined.contains("✅") -> "completed"
            joined.contains("行程进行中") -> "in_trip"
            joined.contains("已经到达起点附近") || joined.contains("司机已到达") -> "arrived_pickup"
            joined.contains("匹配到司机") || (joined.contains("司机") && joined.contains("来接")) -> "arriving"
            joined.contains("匹配最近的司机") || joined.contains("正在为您匹配") -> "matching_driver"
            joined.contains("已取消") || (joined.contains("取消") && joined.contains("成功")) -> "cancelled"
            else -> "unknown"
        }

        return StatusInfo(status = status, pickupDistanceKm = pickupKm, pickupEtaMin = pickupMin)
    }

    private fun normalizeStatus(status: String?): String? {
        return when (status?.lowercase()) {
            "created", "pending", "waiting" -> "matching_driver"
            "accepted" -> "accepted"
            "arriving" -> "arriving"
            "arrived" -> "arrived_pickup"
            "in_progress", "ongoing" -> "in_trip"
            "completed", "finished" -> "completed"
            "cancelled" -> "cancelled"
            null -> null
            else -> status.lowercase()
        }
    }

    private val JsonElement.jsonObjectOrNull: JsonObject?
        get() = try { this.jsonObject } catch (_: Exception) { null }

    private val JsonElement.jsonArrayOrNull: JsonArray?
        get() = try { this.jsonArray } catch (_: Exception) { null }
}
