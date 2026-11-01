package com.nihildigit.safetycore.v2.types

/**
 * Tracked obstacle with Kalman state and appearance features.
 */
data class TrackedObstacleV2(
    val trackId: Int,
    val bbox: FloatArray,       // [x, y, w, h] in frame coordinates
    val classId: Int,
    val classSource: Int,       // 0=detection(COCO), 1=segmentation(Cityscapes)
    val confidence: Float,
    // JNI 原样传回跟踪器的相对深度；DangerAssessor 用 scaleFactor / depthMeters 换算米数。
    val depthMeters: Float,
    val velocityX: Float,       // Velocity in pixels/frame
    val velocityY: Float,
    val approachSpeed: Float,   // Relative depth trend per second
    val timeSinceUpdate: Int,
    val hits: Int,
    val age: Int,
    val confirmed: Boolean      // True if hits >= n_init
) {
    val centerX: Float get() = bbox[0] + bbox[2] / 2f
    val centerY: Float get() = bbox[1] + bbox[3] / 2f
    val area: Float get() = bbox[2] * bbox[3]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TrackedObstacleV2
        return trackId == other.trackId &&
               bbox.contentEquals(other.bbox) &&
               classId == other.classId
    }

    override fun hashCode(): Int {
        var result = trackId
        result = 31 * result + bbox.contentHashCode()
        result = 31 * result + classId
        return result
    }
}
