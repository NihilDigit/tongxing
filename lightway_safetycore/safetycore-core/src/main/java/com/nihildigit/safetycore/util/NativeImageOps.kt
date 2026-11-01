package com.nihildigit.safetycore.util

object NativeImageOps {
    private var loaded = false
    @Volatile private var nativeEnabled = true
    @Volatile private var frameRankAvailable = true
    @Volatile private var frameRankSampledAvailable = true
    @Volatile private var downsampleAvailable = true
    @Volatile private var minMaxAvailable = true
    init {
        // Prefer app-side native lib (Path A), fallback to SDK's qnnops if present
        loaded = try {
            System.loadLibrary("scops"); true
        } catch (_: Throwable) {
            try {
                System.loadLibrary("qnnops"); true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun setNativeEnabled(enabled: Boolean) {
        nativeEnabled = enabled
    }

    fun isEnabled(): Boolean = loaded && nativeEnabled

    fun available(): Boolean = isEnabled()

    fun frameRankDepthSafe(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        percentilesOut: FloatArray,
        rankOut: FloatArray,
        scratch: FloatArray
    ): Boolean {
        if (!isEnabled() || !frameRankAvailable) return false
        return try {
            frameRankDepth(relDepth, width, height, percentilesOut, rankOut, scratch)
        } catch (_: UnsatisfiedLinkError) {
            frameRankAvailable = false
            false
        }
    }

    fun frameRankDepthSampledSafe(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        sampleRatio: Float,
        percentilesOut: FloatArray,
        rankOut: FloatArray,
        scratch: FloatArray
    ): Boolean {
        if (!isEnabled() || !frameRankSampledAvailable) return false
        return try {
            frameRankDepthSampled(relDepth, width, height, sampleRatio, percentilesOut, rankOut, scratch)
        } catch (_: UnsatisfiedLinkError) {
            frameRankSampledAvailable = false
            false
        }
    }

    fun frameRankPercentilesSampledSafe(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        sampleRatio: Float,
        percentilesOut: FloatArray,
        scratch: FloatArray
    ): Boolean {
        if (!isEnabled() || !frameRankSampledAvailable) return false
        return try {
            frameRankPercentilesSampled(relDepth, width, height, sampleRatio, percentilesOut, scratch)
        } catch (_: UnsatisfiedLinkError) {
            frameRankSampledAvailable = false
            false
        }
    }

    fun downsample2xMaxIntoSafe(
        src: FloatArray,
        width: Int,
        height: Int,
        dst: FloatArray
    ): Boolean {
        if (!isEnabled() || !downsampleAvailable) return false
        return try {
            downsample2xMaxInto(src, width, height, dst)
        } catch (_: UnsatisfiedLinkError) {
            downsampleAvailable = false
            false
        }
    }

    fun workRank2xFromRawDepthSafe(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        p10: Float,
        p90: Float,
        sampleCount: Int,
        outRank: FloatArray
    ): Boolean {
        if (!isEnabled() || !downsampleAvailable) return false
        return try {
            workRank2xFromRawDepth(relDepth, width, height, p10, p90, sampleCount, outRank)
        } catch (_: UnsatisfiedLinkError) {
            downsampleAvailable = false
            false
        }
    }

    fun rankFromRawDepthSafe(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        p10: Float,
        p90: Float,
        sampleCount: Int,
        outRank: FloatArray
    ): Boolean {
        if (!isEnabled() || !downsampleAvailable) return false
        return try {
            rankFromRawDepth(relDepth, width, height, p10, p90, sampleCount, outRank)
        } catch (_: UnsatisfiedLinkError) {
            downsampleAvailable = false
            false
        }
    }

    fun buildNearMaskFromRankSafe(
        rank: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        ignoreTopRatio: Float,
        ignoreBottomRatio: Float,
        ignoreLeftRatio: Float,
        ignoreRightRatio: Float,
        maxNearShare: Float,
        outMask: ByteArray
    ): Boolean {
        if (!isEnabled() || !downsampleAvailable) return false
        return try {
            buildNearMaskFromRank(
                rank,
                width,
                height,
                threshold,
                ignoreTopRatio,
                ignoreBottomRatio,
                ignoreLeftRatio,
                ignoreRightRatio,
                maxNearShare,
                outMask
            )
        } catch (_: UnsatisfiedLinkError) {
            downsampleAvailable = false
            false
        }
    }

    fun upsampleRankNearestSafe(
        rank: FloatArray,
        workW: Int,
        workH: Int,
        scale: Int,
        frameW: Int,
        frameH: Int,
        outRank: FloatArray
    ): Boolean {
        if (!isEnabled() || !downsampleAvailable) return false
        return try {
            upsampleRankNearest(rank, workW, workH, scale, frameW, frameH, outRank)
        } catch (_: UnsatisfiedLinkError) {
            downsampleAvailable = false
            false
        }
    }

    fun minMaxFiniteSafe(src: FloatArray, out: FloatArray): Boolean {
        if (!isEnabled() || !minMaxAvailable) return false
        return try {
            minMaxFinite(src, out)
        } catch (_: UnsatisfiedLinkError) {
            minMaxAvailable = false
            false
        }
    }

    external fun upsampleDepthBilinear(
        src: FloatArray,
        modelW: Int,
        modelH: Int,
        frameW: Int,
        frameH: Int,
        scale: Float,
        padX: Int,
        padY: Int,
        dst: FloatArray
    ): Boolean

    external fun normalizeDepthMeters(
        rel: FloatArray,
        width: Int,
        height: Int,
        a: Float,
        b: Float,
        minM: Float,
        maxM: Float,
        dst: FloatArray
    ): Boolean

    external fun buildAlignedSegAndWalkMask(
        classes: ByteArray,
        modelW: Int,
        modelH: Int,
        frameW: Int,
        frameH: Int,
        scale: Float,
        padX: Int,
        padY: Int,
        walkableIds: IntArray,
        outSeg: ByteArray,
        outMask: ByteArray
    ): Boolean

    external fun yuv420ToArgb(
        y: java.nio.ByteBuffer,
        yRowStride: Int,
        u: java.nio.ByteBuffer,
        uRowStride: Int,
        v: java.nio.ByteBuffer,
        vRowStride: Int,
        pixelStrideUV: Int,
        width: Int,
        height: Int,
        outArgb: IntArray
    ): Boolean

    external fun frameRankDepth(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        percentilesOut: FloatArray,
        rankOut: FloatArray,
        scratch: FloatArray // Reusable buffer for sorting (size >= width*height)
    ): Boolean

    external fun frameRankDepthSampled(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        sampleRatio: Float,
        percentilesOut: FloatArray,
        rankOut: FloatArray,
        scratch: FloatArray // Reusable buffer for sorting (size >= width*height)
    ): Boolean

    external fun frameRankPercentilesSampled(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        sampleRatio: Float,
        percentilesOut: FloatArray,
        scratch: FloatArray // Reusable buffer for sorting (size >= width*height)
    ): Boolean

    external fun downsample2xMaxInto(
        src: FloatArray,
        width: Int,
        height: Int,
        dst: FloatArray
    ): Boolean

    external fun workRank2xFromRawDepth(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        p10: Float,
        p90: Float,
        sampleCount: Int,
        outRank: FloatArray
    ): Boolean

    external fun rankFromRawDepth(
        relDepth: FloatArray,
        width: Int,
        height: Int,
        p10: Float,
        p90: Float,
        sampleCount: Int,
        outRank: FloatArray
    ): Boolean

    external fun buildNearMaskFromRank(
        rank: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        ignoreTopRatio: Float,
        ignoreBottomRatio: Float,
        ignoreLeftRatio: Float,
        ignoreRightRatio: Float,
        maxNearShare: Float,
        outMask: ByteArray
    ): Boolean

    external fun upsampleRankNearest(
        rank: FloatArray,
        workW: Int,
        workH: Int,
        scale: Int,
        frameW: Int,
        frameH: Int,
        outRank: FloatArray
    ): Boolean

    external fun minMaxFinite(
        src: FloatArray,
        outMinMaxCount: FloatArray // [min, max, count]
    ): Boolean
}
