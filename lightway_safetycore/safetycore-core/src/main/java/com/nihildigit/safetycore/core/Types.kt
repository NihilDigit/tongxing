package com.nihildigit.safetycore.core

data class Size(val width: Int, val height: Int)

data class LetterboxMap(
    val inSize: Size,      // camera frame size
    val modelSize: Size,   // model input size
    val scale: Float,      // applied uniform scale from frame -> model content
    val padX: Int,         // left padding inside model canvas
    val padY: Int          // top padding inside model canvas
)

/**
 * Dual letterbox mapping for depth and detection models.
 * Unified letterbox mapping structure.
 */
data class DualLetterboxMap(
    val depth: LetterboxMap,
    val detect: LetterboxMap
)



enum class Source { DEPTH, DETECTION, SEGMENTATION, FUSED }

/**
 * Depth feature extracted from MiDaS relative depth output.
 * DEPRECATED: depthRank-only pipeline uses depthRank exclusively.
 */
@Deprecated("DepthFeature is deprecated; use depthRank-only pipeline", level = DeprecationLevel.WARNING)
data class DepthFeature(
    val median: Float,        // Median relative depth (raw MiDaS output)
    val iqr: Float,           // Interquartile range (Q3 - Q1)
    val ema: Float,           // Exponential moving average over recent frames
    val rank: Float,          // Frame-relative ranking [0=farthest, 1=nearest]
    val confidence: Float     // Confidence based on sample count + variance
)

data class Obstacle(
    val source: Source,
    val bbox: FloatArray,    // [x,y,w,h] in frame coords
    val classId: Int?,
    val confidence: Float,
    val depthRank: Float? = null,           // Frame-relative depth ranking [0=farthest, 1=nearest]
    val depthTemporal: Float? = null,       // EMA-stabilized temporal depth signal (unclamped)
    @Deprecated("DepthFeature is deprecated; use depthRank-only pipeline", level = DeprecationLevel.WARNING)
    val depthFeature: DepthFeature? = null  // Full depth statistics (deprecated, use depthRank)
)

data class FilteredBlob(
    val bbox: FloatArray,
    val reason: String
)

/**
 * Snapshot of track state at a single frame.
 * Used for temporal history buffer (10 frames).
 */
data class TrackSnapshot(
    val frameId: Long,
    @Deprecated("depthMedian is deprecated; use depthRank-only pipeline", level = DeprecationLevel.WARNING)
    val depthMedian: Float?,
    val bboxArea: Float,
    val centroidX: Float,
    val centroidY: Float,
    val confidence: Float,
    val depthRank: Float? = null,
    val depthTemporal: Float? = null
)

data class Track(
    val id: Int,
    val bbox: FloatArray,
    val confidence: Float,
    val classId: Int? = null,  // COCO class ID from detection (null if no recent detection match)
    // Tracking fields
    @Deprecated("DepthFeature is deprecated; use depthRank-only pipeline", level = DeprecationLevel.WARNING)
    val depthFeature: DepthFeature? = null,  // Full depth statistics (deprecated, use depthRank)
    val depthRank: Float? = null,            // Frame-relative depth ranking [0=farthest, 1=nearest]
    val depthTemporal: Float? = null,        // EMA-stabilized temporal depth signal (unclamped)
    val bboxArea: Float = 0f,                // Bounding box area (w * h)
    val centroidX: Float = 0f,               // Centroid X coordinate
    val centroidY: Float = 0f,               // Centroid Y coordinate
    // History buffer (10-frame circular queue)
    private val history: List<TrackSnapshot> = emptyList()
) {
    // Accessor for history buffer (for ThreatAssessor)
    fun getHistory(): List<TrackSnapshot> = history
}

data class Threat(
    val trackId: Int,
    val level: ThreatLevel,
    val reason: String,
    // depthTrend-only heuristic fields (depthRank-only pipeline)
    val depthTrend: Float = 0f,      // [-1, 1]: negative=receding, positive=approaching
    val areaTrend: Float = 0f,       // Deprecated in depthRank-only pipeline (always 0)
    val centroidVel: Float = 0f,     // [0, inf]: pixels/frame, low velocity = high priority
    val depthRank: Float = 0f,       // [0, 1]: frame-relative depth ranking (1=nearest)
    val score: Float = 0f,           // Final threat score [0, 1]
    val direction: ThreatDirection = ThreatDirection.FRONT  // Lateral sector relative to camera
)

enum class ThreatLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class ThreatDirection { LEFT, FRONT, RIGHT }

enum class MatchType { HUNGARIAN, SECOND_CHANCE }

/**
 * Debug data for a single tracked match (Hungarian or second-chance association).
 * Used for visualizing tracking decisions in tuning WebUI.
 */
data class TrackMatchDebug(
    val trackId: Int,
    val trackBbox: FloatArray,      // bbox before update (predicted)
    val obsIndex: Int,
    val obsBbox: FloatArray,
    val cost: Float,
    val costIou: Float,
    val costDepth: Float,
    val costVelocity: Float,
    val matchType: MatchType
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TrackMatchDebug

        if (trackId != other.trackId) return false
        if (!trackBbox.contentEquals(other.trackBbox)) return false
        if (obsIndex != other.obsIndex) return false
        if (!obsBbox.contentEquals(other.obsBbox)) return false
        if (cost != other.cost) return false
        if (costIou != other.costIou) return false
        if (costDepth != other.costDepth) return false
        if (costVelocity != other.costVelocity) return false
        if (matchType != other.matchType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = trackId
        result = 31 * result + trackBbox.contentHashCode()
        result = 31 * result + obsIndex
        result = 31 * result + obsBbox.contentHashCode()
        result = 31 * result + cost.hashCode()
        result = 31 * result + costIou.hashCode()
        result = 31 * result + costDepth.hashCode()
        result = 31 * result + costVelocity.hashCode()
        result = 31 * result + matchType.hashCode()
        return result
    }
}

/**
 * Optional debug payload exposed when tracking debug is enabled.
 * Contains match pairs, unmatched sets, and per-match cost components.
 * Used for visualizing Stage4 tracking associations in tuning WebUI.
 */
data class TrackingDebugData(
    val matches: List<TrackMatchDebug>,
    val unmatchedTrackIds: List<Int>,
    val unmatchedObsIndices: List<Int>,
    val secondChanceMatches: List<TrackMatchDebug> = emptyList(),
    // snapshot of effective tracking params for the frame
    val costThresh: Float,
    val costWeightIou: Float,
    val costWeightDepth: Float,
    val costWeightVelocity: Float,
    val maxCentroidDispPxPerFrame: Float
)

data class DetectionViz(
    val bbox: FloatArray,
    val classId: Int,
    val score: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DetectionViz

        if (!bbox.contentEquals(other.bbox)) return false
        if (classId != other.classId) return false
        if (score != other.score) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bbox.contentHashCode()
        result = 31 * result + classId
        result = 31 * result + score.hashCode()
        return result
    }
}
