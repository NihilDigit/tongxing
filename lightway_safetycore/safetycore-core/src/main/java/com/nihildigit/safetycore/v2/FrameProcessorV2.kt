package com.nihildigit.safetycore.v2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk
import com.nihildigit.safetycore.v2.types.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "FrameProcessorV2"
private val WATCH_TRACK_IDS = setOf(45, 144)

/**
 * V2 Frame Processor - orchestrates the four-model fusion pipeline.
 *
 * Pipeline: MiDaS → YOLOX → FFNet → FeatureExtractor → Fusion
 */
class FrameProcessorV2(
    private val sdk: QnnLiteRtSdk,
    private val config: ConfigV2,
    private val postFusion: PostFusionV2
) : AutoCloseable {
    private data class FeaturePack(
        val features: List<FloatArray>,
        val extractedCount: Int
    )

    private companion object {
        private const val FEATURE_DIM = 1280
    }

    interface Listener {
        fun onResult(result: FusionResultV2)
    }

    private var listener: Listener? = null
    private var frameCount = 0L
    private val degradationManager = DegradationManagerV2()
    private val modelExecutor = Executors.newFixedThreadPool(3)
    private val lifecycle = InferenceLifecycle()

    private var featureScratchBitmaps: MutableList<Bitmap> = mutableListOf()
    private var featureScratchCanvases: MutableList<Canvas> = mutableListOf()
    private val featureSrcRect = Rect()
    private val featureDstRect = Rect(0, 0, 224, 224)
    private val featurePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Process a single frame through the V2 pipeline.
     */
    fun processFrame(frame: Bitmap): FusionResultV2 {
        return processFrame(frame, null, 0.0)
    }

    fun ffnetInputSize(): Pair<Int, Int> = lifecycle.run {
        val shape = sdk.ffnetInputShape() // [1,H,W,C]
        shape[2] to shape[1]
    }

    /**
     * Process a single frame through the V2 pipeline.
     * Optional FFNet input can be provided as RGB24 ByteBuffer.
     */
    fun processFrame(frame: Bitmap, ffnetRgb: ByteBuffer?, ffnetPreMs: Double): FusionResultV2 {
        return lifecycle.run { processFrameInternal(frame, ffnetRgb, ffnetPreMs) }
    }

    private fun processFrameInternal(frame: Bitmap, ffnetRgb: ByteBuffer?, ffnetPreMs: Double): FusionResultV2 {
        val t0 = System.nanoTime()
        frameCount++

        Log.d(TAG, "Frame #$frameCount: input ${frame.width}x${frame.height}")

        // Stage 1: Run all models in parallel
        val depthFuture = modelExecutor.submit<com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk.DepthResult> {
            sdk.inferDepth(frame)
        }
        val detectFuture = modelExecutor.submit<com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk.DetectResult> {
            sdk.inferDetect(frame)
        }
        val ffnetFuture = modelExecutor.submit<com.nihildigit.qnninferencesdk.sdk.QnnLiteRtSdk.FFNetResult> {
            if (ffnetRgb != null) {
                sdk.inferFFNetFromRgbBuffer(ffnetRgb, ffnetPreMs)
            } else {
                sdk.inferFFNet(frame)
            }
        }

        awaitInferenceTasks(listOf(depthFuture, detectFuture, ffnetFuture))
        val depthResult = depthFuture.get()
        val t1 = System.nanoTime()
        Log.d(TAG, "  Depth: ${depthResult.width}x${depthResult.height}, " +
            "pre=${depthResult.preMs}ms, infer=${depthResult.inferMs}ms, post=${depthResult.postMs}ms")

        val detectResult = detectFuture.get()
        val t2 = System.nanoTime()
        Log.d(TAG, "  Detect: ${detectResult.width}x${detectResult.height}, " +
            "pre=${detectResult.preMs}ms, infer=${detectResult.inferMs}ms, post=${detectResult.postMs}ms, " +
            "boxes=${detectResult.boxes.size / 4}")

        val ffnetResult = ffnetFuture.get()
        val t3 = System.nanoTime()
        Log.d(TAG, "  FFNet: ${ffnetResult.width}x${ffnetResult.height}, " +
            "pre=${ffnetResult.preMs}ms, infer=${ffnetResult.inferMs}ms, post=${ffnetResult.postMs}ms")

        val degradationState = degradationManager.checkInputs(
            depthRel = depthResult.depth,
            detectionsAvailable = true,
            segClasses = ffnetResult.classes
        )
        if (degradationState.useConservativeMode) {
            val tCritical = System.nanoTime()
            val timings = TimingsV2(
                depthInferMs = depthResult.inferMs.toFloat(),
                detectInferMs = detectResult.inferMs.toFloat(),
                segInferMs = ffnetResult.inferMs.toFloat(),
                featureInferMs = 0f,
                fusionMs = 0f,
                totalMs = (tCritical - t0) / 1_000_000f
            )
            val result = FusionResultV2(
                tracks = emptyList(),
                threats = emptyList(),
                scaleState = ScaleStateV2(1.0f, false, 0),
                frameTimeMs = timings.totalMs,
                timings = timings,
                inputFrameWidth = frame.width,
                inputFrameHeight = frame.height,
                trackSpaceWidth = ffnetResult.width,
                trackSpaceHeight = ffnetResult.height,
                isDegraded = true,
                degradationReason = degradationState.message
            )
            Log.i(
                TAG,
                "telemetry frame=$frameCount total=${"%.1f".format(timings.totalMs)}ms " +
                    "depth=${"%.1f".format(depthResult.inferMs.toFloat())}ms detect=${"%.1f".format(detectResult.inferMs.toFloat())}ms " +
                    "seg=${"%.1f".format(ffnetResult.inferMs.toFloat())}ms mode=conservative " +
                    "in=${frame.width}x${frame.height} trackSpace=${ffnetResult.width}x${ffnetResult.height}"
            )
            listener?.onResult(result)
            return result
        }

        // Stage 2: Update scale from segmentation + depth
        // Note: segClasses uses FFNet output size, depth uses its own size
        // We need to resample depth to match FFNet size for scale recovery
        val resampledDepth = resampleDepthToSegSize(
            depthResult.depth,
            depthResult.width,
            depthResult.height,
            ffnetResult.width,
            ffnetResult.height
        )
        val scaleState = postFusion.updateScale(
            ffnetResult.classes,
            resampledDepth,
            ffnetResult.width,
            ffnetResult.height
        ) ?: ScaleStateV2(1.0f, false, 0)
        if (scaleState.validFrames <= 0) {
            degradationManager.reportScaleUnavailable()
        }
        val tScale = System.nanoTime()

        // Stage 3: Extract obstacles from both paths
        // Convert detections to FFNet space so both paths share one coordinate system.
        val scaledBoxes = scaleBoxes(
            detectResult.boxes,
            detectResult.width,
            detectResult.height,
            ffnetResult.width,
            ffnetResult.height
        )
        val adjustedScores = adjustScoresForWalkable(
            scores = detectResult.scores,
            boxes = scaledBoxes,
            segClasses = ffnetResult.classes,
            width = ffnetResult.width,
            height = ffnetResult.height
        )
        val detectObstacles = postFusion.extractFromDetections(
            scaledBoxes,
            adjustedScores,
            detectResult.classIdx,
            resampledDepth,
            ffnetResult.width,
            ffnetResult.height
        )

        val maskedSegClasses = maskSegByDetections(
            segClasses = ffnetResult.classes,
            width = ffnetResult.width,
            height = ffnetResult.height,
            detections = detectObstacles
        )
        val segObstacles = postFusion.extractFromSegmentation(
            maskedSegClasses,
            resampledDepth,
            ffnetResult.width,
            ffnetResult.height
        )

        // Merge obstacles (detection takes priority)
        val allObstacles = mergeObstacles(detectObstacles, segObstacles)
        Log.d(TAG, "  Obstacles: detect=${detectObstacles.size}, seg=${segObstacles.size}, merged=${allObstacles.size}")
        val tExtract = System.nanoTime()

        // Stage 4: Extract features for each obstacle (pipeline parity with Python).
        val featurePack = extractFeatures(frame, allObstacles, ffnetResult.width, ffnetResult.height)
        val features = featurePack.features
        val t4 = System.nanoTime()
        Log.d(TAG, "  Features: extracted=${featurePack.extractedCount}/${features.size} in ${(t4 - t3) / 1_000_000f}ms")

        // Stage 5+6: Fused tracker update + danger assessment (reduce JNI crossings)
        val (rawTracks, fusedThreats) = postFusion.updateTrackerAndAssessDanger(
            allObstacles,
            features,
            scaleState.scaleFactor,
            ffnetResult.width,
            ffnetResult.height
        )
        val tracks = rawTracks.filter(::isValidTrack)
        val droppedTracks = rawTracks.size - tracks.size
        if (droppedTracks > 0) {
            Log.w(TAG, "Dropped $droppedTracks invalid track(s) due to non-finite/degenerate bbox")
        }
        if (allObstacles.isNotEmpty() && tracks.isEmpty()) {
            degradationManager.reportTrackingFailed()
        } else {
            degradationManager.reportTrackingSuccess()
        }
        val tTrack = System.nanoTime()

        val validTrackIds = tracks.asSequence().map { it.trackId }.toHashSet()
        val threats = fusedThreats.filter { it.trackId in validTrackIds }
        if (WATCH_TRACK_IDS.isNotEmpty()) {
            tracks.filter { it.trackId in WATCH_TRACK_IDS }.forEach { watched ->
                val wt = threats.firstOrNull { it.trackId == watched.trackId }
                Log.i(
                    TAG,
                    "watch track#${watched.trackId} class=${watched.classId} conf=${"%.2f".format(watched.confidence)} " +
                        "bbox=[${"%.1f".format(watched.bbox[0])},${"%.1f".format(watched.bbox[1])}," +
                        "${"%.1f".format(watched.bbox[2])},${"%.1f".format(watched.bbox[3])}] " +
                        "depth=${"%.2f".format(watched.depthMeters)}m " +
                        "threat=${wt?.level ?: "NONE"} dist=${wt?.distanceMeters ?: Float.NaN} ttc=${wt?.ttcSeconds ?: Float.NaN}"
                )
            }
        }

        val t5 = System.nanoTime()

        val timings = TimingsV2(
            depthInferMs = depthResult.inferMs.toFloat(),
            detectInferMs = detectResult.inferMs.toFloat(),
            segInferMs = ffnetResult.inferMs.toFloat(),
            featureInferMs = (t4 - t3) / 1_000_000f,
            fusionMs = (t5 - t4) / 1_000_000f,
            totalMs = (t5 - t0) / 1_000_000f
        )

        val result = FusionResultV2(
            tracks = tracks,
            threats = threats,
            scaleState = scaleState,
            frameTimeMs = timings.totalMs,
            timings = timings,
            inputFrameWidth = frame.width,
            inputFrameHeight = frame.height,
            trackSpaceWidth = ffnetResult.width,
            trackSpaceHeight = ffnetResult.height,
            isDegraded = degradationManager.state.level != DegradationLevelV2.NONE,
            degradationReason = degradationManager.state.message
        )

        val scaleMs = (tScale - t3) / 1_000_000f
        val extractMs = (tExtract - tScale) / 1_000_000f
        val trackerMs = (tTrack - t4) / 1_000_000f
        val dangerMs = (t5 - tTrack) / 1_000_000f
        val firstTrack = tracks.firstOrNull()
        Log.i(
            TAG,
            "telemetry frame=$frameCount total=${"%.1f".format(timings.totalMs)}ms " +
                "depth=${"%.1f".format(depthResult.inferMs.toFloat())}ms detect=${"%.1f".format(detectResult.inferMs.toFloat())}ms seg=${"%.1f".format(ffnetResult.inferMs.toFloat())}ms " +
                "scale=${"%.1f".format(scaleMs)}ms extract=${"%.1f".format(extractMs)}ms feature=${"%.1f".format(timings.featureInferMs)}ms " +
                "tracker=${"%.1f".format(trackerMs)}ms danger=${"%.1f".format(dangerMs)}ms " +
                "obs=${allObstacles.size} featureExtracted=${featurePack.extractedCount} tracks=${tracks.size}/${rawTracks.size} threats=${threats.size} degraded=${result.isDegraded} " +
                "in=${frame.width}x${frame.height} trackSpace=${ffnetResult.width}x${ffnetResult.height}" +
                (if (firstTrack != null) {
                    " firstTrack=#${firstTrack.trackId}[${"%.1f".format(firstTrack.bbox[0])}," +
                        "${"%.1f".format(firstTrack.bbox[1])},${"%.1f".format(firstTrack.bbox[2])},${"%.1f".format(firstTrack.bbox[3])}]"
                } else "")
        )

        listener?.onResult(result)
        return result
    }

    private fun isValidTrack(track: TrackedObstacleV2): Boolean {
        val x = track.bbox[0]
        val y = track.bbox[1]
        val w = track.bbox[2]
        val h = track.bbox[3]
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return false
        if (w <= 0f || h <= 0f) return false
        if (w > 10_000f || h > 10_000f) return false
        if (kotlin.math.abs(x) > 1_000_000f || kotlin.math.abs(y) > 1_000_000f) return false
        return true
    }

    private fun adjustScoresForWalkable(
        scores: FloatArray,
        boxes: FloatArray,
        segClasses: ByteArray,
        width: Int,
        height: Int
    ): FloatArray {
        if (scores.isEmpty()) return scores
        if (segClasses.isEmpty() || width <= 0 || height <= 0) return scores

        val adjusted = scores.copyOf()
        val walkable = BooleanArray(256)
        for (id in config.walkableClassIds) {
            if (id in 0..255) walkable[id] = true
        }
        val boxCount = boxes.size / 4
        for (i in 0 until boxCount) {
            val x = boxes[i * 4 + 0].toInt().coerceIn(0, width - 1)
            val y = boxes[i * 4 + 1].toInt().coerceIn(0, height - 1)
            val w = boxes[i * 4 + 2].toInt().coerceAtLeast(1).coerceAtMost(width - x)
            val h = boxes[i * 4 + 3].toInt().coerceAtLeast(1).coerceAtMost(height - y)
            val score = scores[i]
            if (score >= config.detConfidenceHigh) continue

            var walkableCount = 0
            val total = w * h
            for (yy in y until (y + h)) {
                val row = yy * width
                for (xx in x until (x + w)) {
                    if (walkable[segClasses[row + xx].toInt() and 0xFF]) {
                        walkableCount++
                    }
                }
            }
            if (total > 0) {
                val ratio = walkableCount.toFloat() / total.toFloat()
                if (ratio > config.detWalkableRatioMax) {
                    adjusted[i] = score * (1f - ratio)
                }
            }
        }
        return adjusted
    }

    private fun maskSegByDetections(
        segClasses: ByteArray,
        width: Int,
        height: Int,
        detections: List<ObstacleV2>
    ): ByteArray {
        if (segClasses.isEmpty() || detections.isEmpty()) return segClasses
        if (width <= 0 || height <= 0) return segClasses

        val masked = segClasses.copyOf()
        val marginRatio = config.segMaskMarginRatio.coerceAtLeast(0f)
        for (det in detections) {
            val margin = if (shouldExpandSegMask(det.classId)) marginRatio else 0f
            val expandW = det.bbox[2] * margin
            val expandH = det.bbox[3] * margin
            val left = (det.bbox[0] - expandW).toInt().coerceIn(0, width - 1)
            val top = (det.bbox[1] - expandH).toInt().coerceIn(0, height - 1)
            val right = (det.bbox[0] + det.bbox[2] + expandW).toInt().coerceIn(0, width - 1)
            val bottom = (det.bbox[1] + det.bbox[3] + expandH).toInt().coerceIn(0, height - 1)
            for (yy in top..bottom) {
                val row = yy * width
                for (xx in left..right) {
                    masked[row + xx] = 0
                }
            }
        }
        return masked
    }

    private fun shouldExpandSegMask(cocoClassId: Int): Boolean {
        return when (cocoClassId) {
            0,  // person
            1,  // bicycle
            2,  // car
            3,  // motorcycle
            5,  // bus
            7   // truck
            -> true
            else -> false
        }
    }

    /**
     * Merge obstacles from detection and segmentation paths.
     * Detection obstacles take priority; segmentation fills gaps.
     */
    private fun mergeObstacles(
        detectObs: List<ObstacleV2>,
        segObs: List<ObstacleV2>
    ): List<ObstacleV2> {
        val merged = detectObs.toMutableList()

        // Add segmentation obstacles that don't overlap significantly with detections
        for (seg in segObs) {
            val overlaps = detectObs.any { det -> iou(det.bbox, seg.bbox) > 0.3f }
            if (!overlaps) {
                merged.add(seg)
            }
        }

        return merged
    }

    /**
     * Extract appearance features for each obstacle.
     */
    private fun extractFeatures(
        frame: Bitmap,
        obstacles: List<ObstacleV2>,
        modelW: Int,
        modelH: Int
    ): FeaturePack {
        if (obstacles.isEmpty()) return FeaturePack(emptyList(), 0)
        if (modelW <= 0 || modelH <= 0) {
            return FeaturePack(List(obstacles.size) { FloatArray(FEATURE_DIM) }, 0)
        }

        val maxFeatures = config.maxObstaclesForFeatures.coerceAtLeast(1).coerceAtMost(obstacles.size)
        val selected = selectFeatureObstacleIndices(obstacles, maxFeatures)
        val selectedIndices = selected.indices.filter { selected[it] }
        val zeroFeature = FloatArray(FEATURE_DIM)
        val features = MutableList(obstacles.size) { zeroFeature }
        var extractedCount = 0

        val batchSize = config.featureBatchSize.coerceAtLeast(1)
        ensureFeatureScratch(batchSize)

        val xScale = frame.width.toFloat() / modelW.toFloat()
        val yScale = frame.height.toFloat() / modelH.toFloat()
        var cursor = 0
        while (cursor < selectedIndices.size) {
            val count = minOf(batchSize, selectedIndices.size - cursor)
            for (j in 0 until count) {
                val obs = obstacles[selectedIndices[cursor + j]]
                val fx = obs.bbox[0] * xScale
                val fy = obs.bbox[1] * yScale
                val fw = obs.bbox[2] * xScale
                val fh = obs.bbox[3] * yScale

                val x = fx.toInt().coerceIn(0, frame.width - 1)
                val y = fy.toInt().coerceIn(0, frame.height - 1)
                val w = fw.toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
                val h = fh.toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)

                featureSrcRect.set(x, y, x + w, y + h)
                featureScratchCanvases[j].drawBitmap(frame, featureSrcRect, featureDstRect, featurePaint)
            }

            val batch = sdk.inferFeaturesBatch(featureScratchBitmaps.subList(0, count))
            for (j in 0 until count) {
                val idx = selectedIndices[cursor + j]
                features[idx] = batch[j].features
            }
            extractedCount += count
            cursor += count
        }

        return FeaturePack(features, extractedCount)
    }

    private fun selectFeatureObstacleIndices(obstacles: List<ObstacleV2>, maxCount: Int): BooleanArray {
        if (obstacles.isEmpty()) return BooleanArray(0)
        if (maxCount >= obstacles.size) return BooleanArray(obstacles.size) { true }

        val ranked = obstacles.mapIndexed { index, obs ->
            val areaScore = (obs.bbox[2] * obs.bbox[3]).coerceAtLeast(0f)
            val depthScore = if (obs.depthMedian > 0f) (1f / obs.depthMedian).coerceAtMost(10f) else 0f
            val sourceBoost = if (obs.source == ObstacleSource.DETECTION) 1f else 0f
            val score = areaScore * (1f + obs.confidence) + depthScore * 50f + sourceBoost * 25f
            index to score
        }.sortedByDescending { it.second }

        val selected = BooleanArray(obstacles.size)
        for (i in 0 until maxCount) {
            selected[ranked[i].first] = true
        }
        return selected
    }

    private fun ensureFeatureScratch(batchSize: Int) {
        if (featureScratchBitmaps.size == batchSize) return
        featureScratchBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        featureScratchBitmaps = MutableList(batchSize) {
            Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        }
        featureScratchCanvases = featureScratchBitmaps.map { Canvas(it) }.toMutableList()
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ax1 = a[0]; val ay1 = a[1]; val ax2 = a[0] + a[2]; val ay2 = a[1] + a[3]
        val bx1 = b[0]; val by1 = b[1]; val bx2 = b[0] + b[2]; val by2 = b[1] + b[3]

        val ix1 = maxOf(ax1, bx1); val iy1 = maxOf(ay1, by1)
        val ix2 = minOf(ax2, bx2); val iy2 = minOf(ay2, by2)

        val iw = maxOf(0f, ix2 - ix1)
        val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih

        val areaA = a[2] * a[3]
        val areaB = b[2] * b[3]
        val union = areaA + areaB - inter

        return if (union > 0f) inter / union else 0f
    }

    /**
     * Resample depth map to match segmentation output size.
     * Uses bilinear interpolation.
     */
    private fun resampleDepthToSegSize(
        depth: FloatArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        if (srcW == dstW && srcH == dstH) return depth

        val result = FloatArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH

        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                val srcX = x * scaleX
                val srcY = y * scaleY
                val x0 = srcX.toInt().coerceIn(0, srcW - 1)
                val y0 = srcY.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val y1 = (y0 + 1).coerceIn(0, srcH - 1)

                val fx = srcX - x0
                val fy = srcY - y0

                val v00 = depth[y0 * srcW + x0]
                val v10 = depth[y0 * srcW + x1]
                val v01 = depth[y1 * srcW + x0]
                val v11 = depth[y1 * srcW + x1]

                val v = v00 * (1 - fx) * (1 - fy) +
                        v10 * fx * (1 - fy) +
                        v01 * (1 - fx) * fy +
                        v11 * fx * fy

                result[y * dstW + x] = v
            }
        }
        return result
    }

    /**
     * Scale detection boxes from detection model space to depth model space.
     * boxes format: [N*4] where each box is [x, y, w, h]
     */
    private fun scaleBoxes(
        boxes: FloatArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): FloatArray {
        val scaleX = dstW.toFloat() / srcW
        val scaleY = dstH.toFloat() / srcH
        val result = FloatArray(boxes.size)

        for (i in boxes.indices step 4) {
            // Detect output is xyxy; native V2 extractor expects xywh.
            val x1 = boxes[i] * scaleX
            val y1 = boxes[i + 1] * scaleY
            val x2 = boxes[i + 2] * scaleX
            val y2 = boxes[i + 3] * scaleY
            result[i] = x1
            result[i + 1] = y1
            result[i + 2] = maxOf(1f, x2 - x1)
            result[i + 3] = maxOf(1f, y2 - y1)
        }
        return result
    }

    fun reset() = lifecycle.run {
        postFusion.reset()
        degradationManager.reset()
        frameCount = 0
    }

    override fun close() {
        lifecycle.close {
            modelExecutor.shutdown()
            var interrupted = false
            while (!modelExecutor.isTerminated) {
                try { modelExecutor.awaitTermination(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            try {
                postFusion.reset()
                sdk.close()
                featureScratchBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                featureScratchBitmaps.clear()
                featureScratchCanvases.clear()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
