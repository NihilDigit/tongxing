package com.nihildigit.safetycore.depth



/**
 * Result of depth processing with raw relative depth + frame-relative ranking.
 * Returns MiDaS raw values and sorting information rather than [0,1] normalized depth.
 */
data class DepthProcessingResult(
    val rawDepth: FloatArray,        // Original MiDaS relative depth (H*W)
    val depthRank: FloatArray? = null, // Optional frame-relative ranking [0=farthest, 1=nearest]
    val percentile10: Float,         // 10th percentile value (for reference)
    val percentile90: Float,         // 90th percentile value (for reference)
    val sampleCount: Int = 0         // Valid sample count used for percentiles
)

/**
 * Processes MiDaS relative depth to extract raw depth + frame-relative ranking.
 * Preserves raw MiDaS output without conversion to [0,1] or metric units.
 */
interface DepthNormalizer {
    /**
     * Process relative depth array to generate raw depth + ranking information.
     * @param relDepth flat array of relative depth values (H*W)
     * @param width depth map width
     * @param height depth map height
     * @param imu optional IMU data for camera pitch adjustment (deprecated)
     * @param walkableMask optional segmentation mask (same size as relDepth); only pixels where walkableMask[i]==1 are used for ground band sampling (deprecated)
     * @return DepthProcessingResult with raw depth + ranking
     */
    fun normalize(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        walkableMask: ByteArray? = null
    ): DepthProcessingResult

    /**
     * Optional runtime tuning hook for depth normalization.
     */
    fun updateConfig(config: DepthNormConfig) {}
}

enum class DepthStrategy { FRAME_RANK }

/**
 * Depth normalization configuration (rank-based processing).
 *
 * IMPORTANT: MiDaS outputs relative (inverse) depth, NOT metric distances.
 * Runtime logic operates on frame-relative ranking [0=farthest, 1=nearest].
 * Metric parameters (minMeters/maxMeters) are ONLY for optional debug calibration
 * and should NOT be used in production decision-making.
 */
data class DepthNormConfig(
    val strategy: DepthStrategy = DepthStrategy.FRAME_RANK,
    val frameSampleRatio: Float = 1.0f  // portion of frame to sample (default full frame; legacy ground band if <1.0)
)
