package com.nihildigit.safetycore.core

data class ThreatExplain(
    val trackId: Int,

    // Distance information
    val distanceMeters: Float?,   // Normalized depth [0,1]

    // Path-based scoring (new)
    val onPath: Boolean = false,
    val surroundRatio: Float = 0f,      // Ratio of directions surrounded by walkable area (0-1)
    val pathScore: Float = 0f,          // Path proximity/presence score (0-1)

    // Component scores and weights
    val scores: PathBasedScores,
    val weights: PathBasedWeights,
    val totalScore: Float,
    val level: ThreatLevel,

    // Dynamics information
    val classId: Int? = null,
    val repClassName: String? = null,
    val classGroup: String? = null,
    val riskWeight: Float = 1.0f,

    // Depth range information
    val depthRangeM: Float = 5.0f,

    // Removed overlap-based fields (replaced by surround-based detection)
    @Deprecated("Replaced by surroundRatio")
    val overlapRatio: Float = 0f,
    @Deprecated("Centerline distance not used in surround-based detection")
    val centerlineDistPx: Float = 0f
)

// Path-based scoring components
data class PathBasedScores(
    val path: Float,        // Path proximity/presence score (0-1)
    val depth: Float,       // Distance-based score (0-1)
    val dynamics: Float,    // Object dynamics score (0-1)
    val confidence: Float   // Track confidence score (0-1)
)

data class PathBasedWeights(
    val wPath: Float,
    val wDepth: Float,
    val wDynamics: Float,
    val wConfidence: Float
)

// Legacy zone-based scoring (deprecated)
@Deprecated("Zone-based scoring replaced by PathBasedScores")
data class Scores(
    val distance: Float,
    val zone: Float,
    val dynamics: Float,
    val confidence: Float
)

@Deprecated("Zone-based weights replaced by PathBasedWeights")
data class Weights(
    val wDistance: Float,
    val wZone: Float,
    val wDynamics: Float,
    val wConfidence: Float
)

enum class RuleMode { Distance, ScoreFallback }

data class NextLevelDelta(
    val type: String, // "meters" or "score"
    val value: Float
)
