package com.nihildigit.safetycore.v2.types

/**
 * Threat assessment result for a tracked obstacle.
 */
data class ThreatResultV2(
    val trackId: Int,
    val level: DangerLevelV2,
    val distanceMeters: Float,
    val ttcSeconds: Float,      // Time to collision (Infinity if not approaching)
    val score: Float            // Overall threat score [0, 1]
)

enum class DangerLevelV2 {
    NONE,       // no actionable threat
    LOW,        // <= 6m
    MEDIUM,     // <= 4m
    HIGH,       // <= 2m
    CRITICAL    // <= 2m and TTC <= 1s
}
