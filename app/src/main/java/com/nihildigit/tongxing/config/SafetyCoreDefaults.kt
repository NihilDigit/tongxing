package com.nihildigit.tongxing.config

import com.nihildigit.safetycore.SafetyCoreConfig
import com.nihildigit.safetycore.core.BackendMode
import com.nihildigit.safetycore.core.DebugFlags
import com.nihildigit.safetycore.core.FusionConfig
import com.nihildigit.safetycore.core.HeuristicThreatConfig
import com.nihildigit.safetycore.core.SdkRuntimeConfig
import com.nihildigit.safetycore.core.YoloNormConfig

object SafetyCoreDefaults {

    object Heuristic {
        // Simplified two-signal threat assessment (Simplify Stage4 change)
        // Velocity and proximity bonus removed; centroid velocity no longer used
        const val CRITICAL_THRESHOLD = 0.85f
        const val HIGH_THRESHOLD = 0.62f
        const val MEDIUM_THRESHOLD = 0.40f

        const val ENABLE_LEVEL_HYSTERESIS = true
        const val HIGH_CONFIRM_FRAMES = 1
        const val CRITICAL_CONFIRM_FRAMES = 2

        fun createConfig(): HeuristicThreatConfig {
            return HeuristicThreatConfig(
                criticalThreshold = CRITICAL_THRESHOLD,
                highThreshold = HIGH_THRESHOLD,
                mediumThreshold = MEDIUM_THRESHOLD,
                enableLevelHysteresis = ENABLE_LEVEL_HYSTERESIS,
                highConfirmFrames = HIGH_CONFIRM_FRAMES,
                criticalConfirmFrames = CRITICAL_CONFIRM_FRAMES
            )
        }
    }

    object Fusion {
        fun createConfig(): FusionConfig {
            return FusionConfig()  // Use all defaults from Config.kt
        }
    }

    object YoloNorm {
        const val SCORE_THRESH = 0.4f
        const val IOU_THRESH = 0.2f
        const val TOP_K = 10

        fun createConfig(): YoloNormConfig {
            return YoloNormConfig(
                scoreThresh = SCORE_THRESH,
                iouThresh = IOU_THRESH,
                topK = TOP_K
            )
        }
    }

    fun createSafetyCoreConfig(
        heuristicConfig: HeuristicThreatConfig = Heuristic.createConfig(),
        fusionConfig: FusionConfig = Fusion.createConfig(),
        yoloNormConfig: YoloNormConfig = YoloNorm.createConfig()
    ): SafetyCoreConfig {
        return SafetyCoreConfig(
            sdkRuntime = SdkRuntimeConfig(
                backendMode = BackendMode.DEFAULT
            ),
            yoloNorm = yoloNormConfig,
            fusion = fusionConfig,
            heuristic = heuristicConfig,
            debug = DebugFlags(
                enableOverlay = false,
                enableRealtimeVisualData = true,
                enableTrackingDebug = true
            )
        )
    }
}
