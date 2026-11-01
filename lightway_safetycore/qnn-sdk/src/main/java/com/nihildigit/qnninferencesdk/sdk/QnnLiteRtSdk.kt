package com.nihildigit.qnninferencesdk.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.nihildigit.qnninferencesdk.core.DepthPostprocessor
import com.nihildigit.qnninferencesdk.core.DepthRunner
import com.nihildigit.qnninferencesdk.core.Preprocessor
import com.nihildigit.qnninferencesdk.core.SegmentationPostprocessor
import com.nihildigit.qnninferencesdk.core.SegmentationRunner
import com.nihildigit.qnninferencesdk.core.DetectionRunner
import com.nihildigit.qnninferencesdk.core.DetectionPostprocessor
import com.nihildigit.qnninferencesdk.core.FFNetRunner
import com.nihildigit.qnninferencesdk.core.FeatureExtractorRunner
import java.nio.ByteBuffer

class QnnLiteRtSdk(private val context: Context, config: SdkConfig = SdkConfig()) : AutoCloseable {
    private var depth: DepthRunner? = null
    private var seg: SegmentationRunner? = null
    private var det: DetectionRunner? = null
    private var ffnet: FFNetRunner? = null
    private var featureExtractor: FeatureExtractorRunner? = null
    private var ffnetResizeBitmap: Bitmap? = null
    private var ffnetResizeCanvas: Canvas? = null
    private val ffnetSrcRect = Rect()
    private val ffnetDstRect = Rect()
    private val ffnetResizePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { config.backendMode.applyToHelper() }

    fun loadDepth(assetPath: String): QnnLiteRtSdk {
        closeDepth()
        depth = DepthRunner(context, assetPath)
        return this
    }

    fun loadSeg(assetPath: String): QnnLiteRtSdk {
        closeSeg()
        seg = SegmentationRunner(context, assetPath)
        return this
    }

    fun loadDetect(assetPath: String): QnnLiteRtSdk {
        closeDetect()
        det = DetectionRunner(context, assetPath)
        return this
    }

    fun loadFFNet(assetPath: String): QnnLiteRtSdk {
        closeFFNet()
        ffnet = FFNetRunner(context, assetPath)
        return this
    }

    fun loadFeatureExtractor(assetPath: String): QnnLiteRtSdk {
        closeFeatureExtractor()
        featureExtractor = FeatureExtractorRunner(context, assetPath)
        return this
    }

    data class DepthResult(
        val depth: FloatArray,
        val width: Int,
        val height: Int,
        val backend: String,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double
    )

    fun inferDepth(src: Bitmap): DepthResult {
        val runner = depth ?: error("Depth model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val input = Preprocessor.bitmapToInput(src, h, w, t) ?: error("Unsupported input dtype")
        val t1 = System.nanoTime()
        val out = runner.infer(input)
        val t2 = System.nanoTime()
        return DepthResult(
            depth = out,
            width = w,
            height = h,
            backend = runner.backendLabel(),
            preMs = (t1 - t0) / 1_000_000.0,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    data class SegResult(
        val classes: ByteArray,
        val width: Int,
        val height: Int,
        val backend: String,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double
    )

    fun inferSeg(src: Bitmap): SegResult {
        val runner = seg ?: error("Seg model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val input = Preprocessor.bitmapToInput(src, h, w, t) ?: error("Unsupported input dtype")
        val t1 = System.nanoTime()
        val res: SegmentationPostprocessor.SegResult = runner.infer(input)
        val t2 = System.nanoTime()
        return SegResult(
            classes = res.classMap,
            width = res.width,
            height = res.height,
            backend = runner.backendLabel(),
            preMs = (t1 - t0) / 1_000_000.0,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    data class DetectResult(
        val boxes: FloatArray, // [N*4] in model input coordinate space
        val scores: FloatArray, // [N]
        val classIdx: IntArray, // [N]
        val width: Int,
        val height: Int,
        val backend: String,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double
    )

    fun inferDetect(src: Bitmap): DetectResult {
        val runner = det ?: error("Detection model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val input = Preprocessor.bitmapToInput(src, h, w, t) ?: error("Unsupported input dtype")
        val t1 = System.nanoTime()
        val res: DetectionPostprocessor.DetectRaw = runner.infer(input)
        val t2 = System.nanoTime()
        return DetectResult(
            boxes = res.boxes,
            scores = res.scores,
            classIdx = res.classIdx,
            width = w,
            height = h,
            backend = runner.backendLabel(),
            preMs = (t1 - t0) / 1_000_000.0,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    /**
     * FFNet segmentation result (Cityscapes 19 classes).
     */
    data class FFNetResult(
        val classes: ByteArray,  // [H*W] class IDs
        val width: Int,          // 256
        val height: Int,         // 128
        val backend: String,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double
    )

    @Synchronized
    fun inferFFNet(src: Bitmap): FFNetResult {
        val runner = ffnet ?: error("FFNet model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val prepared = prepareFfnetInput(src, w, h)
        val input = Preprocessor.bitmapToInput(prepared, h, w, t) ?: error("Unsupported input dtype")
        val t1 = System.nanoTime()
        val res: SegmentationPostprocessor.SegResult = runner.infer(input)
        val t2 = System.nanoTime()
        return FFNetResult(
            classes = res.classMap,
            width = res.width,
            height = res.height,
            backend = runner.backendLabel(),
            preMs = (t1 - t0) / 1_000_000.0,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    @Synchronized
    fun inferFFNetFromRgbBuffer(input: ByteBuffer, preMs: Double = 0.0): FFNetResult {
        val runner = ffnet ?: error("FFNet model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        input.order(java.nio.ByteOrder.nativeOrder())
        val res: SegmentationPostprocessor.SegResult = runner.infer(input)
        return FFNetResult(
            classes = res.classMap,
            width = res.width,
            height = res.height,
            backend = runner.backendLabel(),
            preMs = preMs,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    fun ffnetInputShape(): IntArray {
        val runner = ffnet ?: error("FFNet model not loaded")
        return runner.inputShape()
    }

    private fun prepareFfnetInput(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val sameSize = src.width == targetW && src.height == targetH
        if (sameSize && src.config != Bitmap.Config.HARDWARE) {
            return src
        }

        val cached = ffnetResizeBitmap
        if (cached == null || cached.width != targetW || cached.height != targetH || cached.isRecycled) {
            cached?.takeIf { !it.isRecycled }?.recycle()
            ffnetResizeBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            ffnetResizeCanvas = Canvas(ffnetResizeBitmap!!)
            ffnetDstRect.set(0, 0, targetW, targetH)
        }

        ffnetSrcRect.set(0, 0, src.width, src.height)
        ffnetResizeCanvas!!.drawBitmap(src, ffnetSrcRect, ffnetDstRect, ffnetResizePaint)
        return ffnetResizeBitmap!!
    }

    /**
     * Feature extraction result (L2-normalized feature vector).
     */
    data class FeatureResult(
        val features: FloatArray,  // [1280] L2-normalized
        val backend: String,
        val preMs: Double,
        val inferMs: Double,
        val postMs: Double
    )

    fun inferFeatures(src: Bitmap): FeatureResult {
        val runner = featureExtractor ?: error("FeatureExtractor model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val input = Preprocessor.bitmapToInput(src, h, w, t) ?: error("Unsupported input dtype")
        val t1 = System.nanoTime()
        val features = runner.infer(input)
        val t2 = System.nanoTime()
        return FeatureResult(
            features = features,
            backend = runner.backendLabel(),
            preMs = (t1 - t0) / 1_000_000.0,
            inferMs = runner.lastInferenceMs(),
            postMs = runner.lastPostprocessMs()
        )
    }

    /**
     * Batched feature extraction. Delegates to dynamic batch when supported, with internal fallback.
     */
    fun inferFeaturesBatch(srcList: List<Bitmap>): List<FeatureResult> {
        if (srcList.isEmpty()) return emptyList()
        val runner = featureExtractor ?: error("FeatureExtractor model not loaded")
        val shape = runner.inputShape() // [1,H,W,C]
        val h = shape[1]; val w = shape[2]
        val t = runner.inputDataType()
        val t0 = System.nanoTime()
        val inputs = srcList.map { src ->
            Preprocessor.bitmapToInput(src, h, w, t) ?: error("Unsupported input dtype")
        }
        val t1 = System.nanoTime()
        val batchFeatures = runner.inferBatch(inputs)
        val t2 = System.nanoTime()

        val perSamplePreMs = ((t1 - t0) / 1_000_000.0) / srcList.size.toDouble()
        val perSampleInferMs = runner.lastInferenceMs() / srcList.size.toDouble()
        val perSamplePostMs = runner.lastPostprocessMs() / srcList.size.toDouble()
        return batchFeatures.map { features ->
            FeatureResult(
                features = features,
                backend = runner.backendLabel(),
                preMs = perSamplePreMs,
                inferMs = perSampleInferMs,
                postMs = perSamplePostMs
            )
        }
    }

    fun closeDepth() { depth?.close(); depth = null }
    fun closeSeg() { seg?.close(); seg = null }
    fun closeDetect() { det?.close(); det = null }
    @Synchronized
    fun closeFFNet() {
        ffnet?.close()
        ffnet = null
        ffnetResizeBitmap?.takeIf { !it.isRecycled }?.recycle()
        ffnetResizeBitmap = null
        ffnetResizeCanvas = null
    }
    fun closeFeatureExtractor() { featureExtractor?.close(); featureExtractor = null }

    override fun close() { closeDepth(); closeSeg(); closeDetect(); closeFFNet(); closeFeatureExtractor() }
}
