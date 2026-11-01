package com.nihildigit.safetycore.v2

import android.content.Context
import com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk
import com.nihildigit.qnninferencesdk.sdk.SdkConfig

/**
 * SafetyCore V2 Engine - Factory for creating V2 components.
 *
 * Example usage:
 * ```kotlin
 * val config = ConfigV2()
 * val processor = SafetyCoreV2Engine.createFrameProcessor(context, config)
 * val result = processor.processFrame(bitmap)
 * ```
 */
object SafetyCoreV2Engine {

    /**
     * Create a V2 FrameProcessor for real-time camera frame processing.
     *
     * @param context Android Context for loading assets and initializing SDK
     * @param config V2 configuration
     * @return FrameProcessorV2 instance ready for use
     */
    fun createFrameProcessor(
        context: Context,
        config: ConfigV2 = ConfigV2()
    ): FrameProcessorV2 {
        // Initialize SDK with all four models
        val sdk = QnnLiteRtSdk(context, SdkConfig())
            .loadDepth(config.depthModelPath)
            .loadDetect(config.detectModelPath)
            .loadFFNet(config.ffnetModelPath)
            .loadFeatureExtractor(config.featureModelPath)

        // Initialize post-fusion module
        val postFusion = PostFusionV2(config)
        if (!postFusion.init()) {
            throw IllegalStateException("Failed to initialize V2 native module")
        }

        return FrameProcessorV2(sdk, config, postFusion)
    }

    /**
     * Create a V2 FrameProcessor with an existing SDK instance.
     * The returned processor owns the SDK and closes it after in-flight frames finish.
     */
    fun createFrameProcessor(
        sdk: QnnLiteRtSdk,
        config: ConfigV2 = ConfigV2()
    ): FrameProcessorV2 {
        val postFusion = PostFusionV2(config)
        if (!postFusion.init()) {
            throw IllegalStateException("Failed to initialize V2 native module")
        }

        return FrameProcessorV2(sdk, config, postFusion)
    }
}
