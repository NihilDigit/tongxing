package com.nihildigit.safetycore.v2.types

/**
 * Obstacle extracted from detection or segmentation.
 */
data class ObstacleV2(
    val bbox: FloatArray,       // [x, y, w, h] in frame coordinates
    val classId: Int,
    val confidence: Float,
    val depthMedian: Float,     // Median depth (relative or metric)
    val depthStd: Float,        // Depth standard deviation
    val source: ObstacleSource
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ObstacleV2
        return bbox.contentEquals(other.bbox) &&
               classId == other.classId &&
               confidence == other.confidence &&
               depthMedian == other.depthMedian &&
               source == other.source
    }

    override fun hashCode(): Int {
        var result = bbox.contentHashCode()
        result = 31 * result + classId
        result = 31 * result + confidence.hashCode()
        result = 31 * result + depthMedian.hashCode()
        result = 31 * result + source.hashCode()
        return result
    }
}

enum class ObstacleSource {
    DETECTION,      // From YOLOX
    SEGMENTATION    // From FFNet connected components
}
