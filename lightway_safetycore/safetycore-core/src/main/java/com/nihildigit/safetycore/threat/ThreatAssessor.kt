package com.nihildigit.safetycore.threat

import com.nihildigit.safetycore.core.Threat
import com.nihildigit.safetycore.core.ThreatExplain
import com.nihildigit.safetycore.core.Track

/**
 * Threat assessors consume fused tracks and emit prioritized threats plus optional diagnostics.
 * All assessors must implement this interface so FrameProcessor/SafetyCoreEngine can swap them.
 */
interface ThreatAssessor {
    fun assess(tracks: List<Track>): ThreatAssessment

    /**
     * Optional lifecycle hook. Assessors with internal state (e.g., histories) can override.
     */
    fun reset() {}
}

/**
 * Unified assessment payload for telemetry/UI.
 *
 * @param threats ordered threat list
 * @param explains optional legacy explain map (trackId -> ThreatExplain)
 * @param metrics optional assessor-specific metrics
 */
data class ThreatAssessment(
    val threats: List<Threat>,
    val explains: Map<Int, ThreatExplain>? = null,
    val metrics: ThreatMetrics? = null
)

sealed interface ThreatMetrics

/**
 * Three-clue heuristic assessor metrics.
 */
data class HeuristicMetrics(
    val avgDepthTrend: Float? = null,
    val avgAreaTrend: Float? = null,
    val avgCentroidVel: Float? = null,
    val avgScore: Float? = null,
    val proximityHits: Int = 0,
    val assessedTracks: Int = 0
) : ThreatMetrics
