package com.nihildigit.safetycore.v2

enum class DegradationLevelV2 {
    NONE,
    PARTIAL,
    SEVERE,
    CRITICAL
}

enum class DegradationReasonV2 {
    SEGMENTATION_FAILED,
    DEPTH_SCALE_UNAVAILABLE,
    TRACKING_FAILED,
    DEPTH_INVALID,
    DETECTION_INVALID
}

data class DegradationStateV2(
    val level: DegradationLevelV2 = DegradationLevelV2.NONE,
    val reasons: Set<DegradationReasonV2> = emptySet(),
    val message: String = "",
    val useConservativeMode: Boolean = false
)

class DegradationManagerV2 {
    private val maxConsecutiveFailures = 5
    private val failures = mutableMapOf(
        "segmentation" to 0,
        "depth" to 0,
        "detection" to 0,
        "tracking" to 0
    )

    var state: DegradationStateV2 = DegradationStateV2()
        private set

    fun checkInputs(
        depthRel: FloatArray?,
        detectionsAvailable: Boolean,
        segClasses: ByteArray?
    ): DegradationStateV2 {
        val reasons = mutableSetOf<DegradationReasonV2>()

        if (!isValidDepth(depthRel)) {
            failures["depth"] = (failures["depth"] ?: 0) + 1
            if ((failures["depth"] ?: 0) >= maxConsecutiveFailures) {
                reasons += DegradationReasonV2.DEPTH_INVALID
            }
        } else {
            failures["depth"] = 0
        }

        if (!detectionsAvailable) {
            failures["detection"] = (failures["detection"] ?: 0) + 1
            if ((failures["detection"] ?: 0) >= maxConsecutiveFailures) {
                reasons += DegradationReasonV2.DETECTION_INVALID
            }
        } else {
            failures["detection"] = 0
        }

        if (!isValidSegmentation(segClasses)) {
            failures["segmentation"] = (failures["segmentation"] ?: 0) + 1
            if ((failures["segmentation"] ?: 0) >= maxConsecutiveFailures) {
                reasons += DegradationReasonV2.SEGMENTATION_FAILED
            }
        } else {
            failures["segmentation"] = 0
        }

        updateState(reasons)
        return state
    }

    fun reportScaleUnavailable() {
        val updated = state.reasons.toMutableSet()
        updated += DegradationReasonV2.DEPTH_SCALE_UNAVAILABLE
        updateState(updated)
    }

    fun reportTrackingFailed() {
        failures["tracking"] = (failures["tracking"] ?: 0) + 1
        if ((failures["tracking"] ?: 0) >= maxConsecutiveFailures) {
            val updated = state.reasons.toMutableSet()
            updated += DegradationReasonV2.TRACKING_FAILED
            updateState(updated)
        }
    }

    fun reportTrackingSuccess() {
        failures["tracking"] = 0
    }

    fun reset() {
        failures.keys.forEach { failures[it] = 0 }
        state = DegradationStateV2()
    }

    private fun isValidDepth(depthRel: FloatArray?): Boolean {
        if (depthRel == null || depthRel.isEmpty()) return false
        var anyPositive = false
        for (v in depthRel) {
            if (!v.isFinite()) return false
            if (v > 0f) anyPositive = true
        }
        return anyPositive
    }

    private fun isValidSegmentation(segClasses: ByteArray?): Boolean {
        return segClasses != null && segClasses.isNotEmpty()
    }

    private fun updateState(reasons: Set<DegradationReasonV2>) {
        if (reasons.isEmpty()) {
            state = DegradationStateV2()
            return
        }

        val level = when {
            DegradationReasonV2.DEPTH_INVALID in reasons -> DegradationLevelV2.CRITICAL
            reasons.size >= 2 -> DegradationLevelV2.SEVERE
            else -> DegradationLevelV2.PARTIAL
        }
        val conservative = level == DegradationLevelV2.CRITICAL || level == DegradationLevelV2.SEVERE
        val msg = "degraded: " + reasons.joinToString(",") { it.name }
        state = DegradationStateV2(level = level, reasons = reasons, message = msg, useConservativeMode = conservative)
    }
}
