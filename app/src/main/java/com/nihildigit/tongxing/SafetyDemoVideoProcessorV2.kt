package com.nihildigit.tongxing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.Image
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.nihildigit.safetycore.v2.ConfigV2
import com.nihildigit.safetycore.v2.FrameProcessorV2
import com.nihildigit.safetycore.v2.SafetyCoreV2Engine
import com.nihildigit.safetycore.v2.types.FusionResultV2
import com.nihildigit.tongxing.util.YuvConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * V2 Video processor for SafetyCore V2 pipeline.
 * Supports both file path and content URI input.
 */
class SafetyDemoVideoProcessorV2(private val context: Context) : AutoCloseable {
    private val frameMutex = Mutex()
    private var argbBuffer: ByteBuffer? = null
    private var argbBufferSize: Int = 0
    private var ffnetRgbBuffer: ByteBuffer? = null
    private var ffnetRgbBufferSize: Int = 0
    private var ffnetInputWidth: Int = 0
    private var ffnetInputHeight: Int = 0
    private var processor: FrameProcessorV2? = null
    private var latestResult: FusionResultV2? = null
    private var latestLatencyMs: Float? = null
    private val inferenceRunning = AtomicBoolean(false)
    private var liveFrameCounter = 0
    private var inferenceJob: Job? = null
    private val inferenceJobParent = SupervisorJob()
    private val inferenceScope = CoroutineScope(inferenceJobParent + Dispatchers.Default)

    fun init(): Boolean {
        return try {
            val config = ConfigV2()
            processor = SafetyCoreV2Engine.createFrameProcessor(context.applicationContext, config)
            val (w, h) = processor?.ffnetInputSize() ?: (0 to 0)
            ffnetInputWidth = w
            ffnetInputHeight = h
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize V2 processor", e)
            false
        }
    }

    /**
     * Run video from file path.
     */
    suspend fun runVideo(
        path: String,
        playbackFps: Int = 30,
        inferenceFps: Int = 15,
        onFrame: (Bitmap, FusionResultV2?, Float?) -> Unit
    ) {
        val filePath = path.trim()
        if (filePath.isEmpty()) return

        val ranHardware = try {
            runVideoHardware(filePath, null, playbackFps, inferenceFps, onFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware decode failed, falling back to retriever", e)
            false
        }

        if (!ranHardware) {
            runVideoRetriever(filePath, null, playbackFps, inferenceFps, onFrame)
        }
    }

    /**
     * Run video from content URI.
     */
    suspend fun runVideoUri(
        uri: Uri,
        playbackFps: Int = 30,
        inferenceFps: Int = 15,
        onFrame: (Bitmap, FusionResultV2?, Float?) -> Unit
    ) {
        val ranHardware = try {
            runVideoHardware(null, uri, playbackFps, inferenceFps, onFrame)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware decode failed for URI, falling back to retriever", e)
            false
        }

        if (!ranHardware) {
            runVideoRetriever(null, uri, playbackFps, inferenceFps, onFrame)
        }
    }

    private suspend fun runVideoHardware(
        path: String?,
        uri: Uri?,
        playbackFps: Int,
        inferenceFps: Int,
        onFrame: (Bitmap, FusionResultV2?, Float?) -> Unit
    ): Boolean {
        val extractor = MediaExtractor()
        try {
            if (path != null) {
                extractor.setDataSource(path)
            } else if (uri != null) {
                extractor.setDataSource(context, uri, null)
            } else {
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open video for hardware decode", e)
            return false
        }

        val trackIndex = selectVideoTrack(extractor)
        if (trackIndex < 0) {
            extractor.release()
            return false
        }

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return false
        }
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        var colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1)
        var stride = format.getInteger(MediaFormat.KEY_STRIDE, width)
        var sliceHeight = format.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        var cropLeft = format.getInteger(MediaFormat.KEY_CROP_LEFT, 0)
        var cropTop = format.getInteger(MediaFormat.KEY_CROP_TOP, 0)
        var cropRight = format.getInteger(MediaFormat.KEY_CROP_RIGHT, width - 1)
        var cropBottom = format.getInteger(MediaFormat.KEY_CROP_BOTTOM, height - 1)
        var loggedFormat = false
        var unsupportedFormat = false

        val decoder = MediaCodec.createDecoderByType(mime)
        return try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.selectTrack(trackIndex)

            val streamFps = format.getInteger(MediaFormat.KEY_FRAME_RATE, playbackFps)
                .coerceAtLeast(1)
            val frameIntervalMs = (1000f / streamFps).toLong().coerceAtLeast(16L)
            val inferenceStride = (streamFps / inferenceFps.coerceAtLeast(1)).coerceAtLeast(1)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var decodedFrameIndex = 0
            var inferredFrames = 0
            var lastPtsUs: Long? = null
            var sumPtsDeltaUs = 0L
            var ptsSamples = 0
            var lastInputPtsUs: Long? = null
            var sumInputPtsDeltaUs = 0L
            var inputPtsSamples = 0
            Log.d(
                TAG,
                "playbackFps=$playbackFps streamFps=$streamFps " +
                    "inferenceFps=$inferenceFps stride=$inferenceStride"
            )
            var basePtsUs: Long? = null
            var baseTimeMs = 0L
            var lastFrameNs = SystemClock.elapsedRealtimeNanos()
            var frameCount = 0
            var sumDeltaMs = 0.0
            var maxDeltaMs = 0.0

            while (!outputDone && currentCoroutineContext().isActive) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (buffer != null) {
                            extractor.readSampleData(buffer, 0)
                        } else {
                            -1
                        }

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            if (presentationTimeUs >= 0) {
                                lastInputPtsUs?.let { prev ->
                                    val delta = presentationTimeUs - prev
                                    if (delta > 0) {
                                        sumInputPtsDeltaUs += delta
                                        inputPtsSamples += 1
                                        if (inputPtsSamples % 30 == 0) {
                                            val avgDeltaMs = sumInputPtsDeltaUs.toDouble() / inputPtsSamples / 1000.0
                                            val estFps = if (avgDeltaMs > 0) 1000.0 / avgDeltaMs else 0.0
                                            Log.d(
                                                TAG,
                                                "input pts avgDeltaMs=${"%.2f".format(avgDeltaMs)} " +
                                                    "estFps=${"%.2f".format(estFps)}"
                                            )
                                        }
                                    }
                                }
                                lastInputPtsUs = presentationTimeUs
                            }
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val frameStartNs = SystemClock.elapsedRealtimeNanos()
                        val ptsUs = bufferInfo.presentationTimeUs
                        if (basePtsUs == null) {
                            basePtsUs = ptsUs
                            baseTimeMs = SystemClock.elapsedRealtime()
                        }
                        if (ptsUs >= 0) {
                            lastPtsUs?.let { prev ->
                                val delta = ptsUs - prev
                                if (delta > 0) {
                                    sumPtsDeltaUs += delta
                                    ptsSamples += 1
                                    if (ptsSamples % 30 == 0) {
                                        val avgDeltaMs = sumPtsDeltaUs.toDouble() / ptsSamples / 1000.0
                                        val estFps = if (avgDeltaMs > 0) 1000.0 / avgDeltaMs else 0.0
                                        Log.d(
                                            TAG,
                                            "pts avgDeltaMs=${"%.2f".format(avgDeltaMs)} " +
                                                "estFps=${"%.2f".format(estFps)}"
                                        )
                                    }
                                }
                            }
                            lastPtsUs = ptsUs
                        }
                        val usePtsDelay = ptsUs >= 0 && basePtsUs != null
                        if (usePtsDelay) {
                            val targetMs = baseTimeMs + ((ptsUs - (basePtsUs ?: ptsUs)) / 1000)
                            val nowMs = SystemClock.elapsedRealtime()
                            val frameDelayMs = (targetMs - nowMs).coerceAtLeast(0L)
                            if (frameDelayMs > 0) delay(frameDelayMs)
                        }
                        val deltaMs = (frameStartNs - lastFrameNs) / 1_000_000.0
                        lastFrameNs = frameStartNs
                        frameCount += 1
                        sumDeltaMs += deltaMs
                        if (deltaMs > maxDeltaMs) maxDeltaMs = deltaMs
                        if (frameCount % 30 == 0) {
                            val avgMs = sumDeltaMs / frameCount
                            Log.d(TAG, "decode tick avg=${"%.2f".format(avgMs)}ms max=${"%.2f".format(maxDeltaMs)}ms")
                        }
                        val shouldInfer = decodedFrameIndex % inferenceStride == 0
                        if (shouldInfer) inferredFrames += 1
                        if (decodedFrameIndex % 30 == 0) {
                            Log.d(
                                TAG,
                                "frame=$decodedFrameIndex infer=$shouldInfer " +
                                    "inferred=$inferredFrames ptsUs=$ptsUs"
                            )
                        }
                        var handled = false
                        val image = decoder.getOutputImage(outputIndex)
                        if (image != null) {
                            try {
                                if (image.format == ImageFormat.YUV_420_888) {
                                    val planes = extractYuv420Planes(image)
                                    if (planes != null) {
                                        if (!loggedFormat) {
                                            loggedFormat = true
                                            Log.d(
                                                TAG,
                                                "decode fmt=YUV_420_888 w=${planes.width} h=${planes.height} " +
                                                    "yStride=${planes.yRowStride} uStride=${planes.uRowStride} " +
                                                    "vStride=${planes.vRowStride} uPix=${planes.uPixelStride} " +
                                                    "vPix=${planes.vPixelStride} crop=${planes.crop}"
                                            )
                                        }
                                        val ffnetPack = yuv420ToFfnetRgb(
                                            planes,
                                            ffnetInputWidth,
                                            ffnetInputHeight,
                                            swapUv = true
                                        )
                                        val decodedBitmap = yuv420ToBitmap(planes, swapUv = true)
                                        if (decodedBitmap != null) {
                                            // V2 uses 720p input, no scaling to 640x480
                                            val bitmap = scaleToTarget(decodedBitmap, 1280, 720)
                                            if (bitmap != decodedBitmap) decodedBitmap.recycle()
                                            if (shouldInfer) {
                                                maybeLaunchInference(bitmap, ffnetPack)
                                            }
                                            onFrame(bitmap, latestResult, latestLatencyMs)
                                            handled = true
                                        }
                                    }
                                }
                            } finally {
                                image.close()
                            }
                        }

                        if (!handled) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null && bufferInfo.size > 0) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                val yuvData = ByteArray(bufferInfo.size)
                                buffer.get(yuvData)
                                if (!loggedFormat) {
                                    loggedFormat = true
                                    Log.d(
                                        TAG,
                                        "decode fmt=$colorFormat size=${bufferInfo.size} " +
                                            "w=$width h=$height stride=$stride slice=$sliceHeight " +
                                            "crop=($cropLeft,$cropTop)-($cropRight,$cropBottom)"
                                    )
                                }

                                val ffnetPack = if (cropLeft == 0 && cropTop == 0 &&
                                    cropRight == width - 1 && cropBottom == height - 1) {
                                    yuvToFfnetRgb(
                                        yuvData,
                                        width,
                                        height,
                                        stride,
                                        sliceHeight,
                                        colorFormat,
                                        ffnetInputWidth,
                                        ffnetInputHeight
                                    )
                                } else {
                                    null
                                }
                                val decodedBitmap = yuvToBitmap(
                                    yuvData,
                                    width,
                                    height,
                                    stride,
                                    sliceHeight,
                                    colorFormat
                                )
                                if (decodedBitmap == null) {
                                    Log.w(TAG, "Unsupported YUV layout, format=$colorFormat")
                                    unsupportedFormat = true
                                } else {
                                    val cropWidth = (cropRight - cropLeft + 1)
                                        .coerceAtMost(decodedBitmap.width - cropLeft)
                                    val cropHeight = (cropBottom - cropTop + 1)
                                        .coerceAtMost(decodedBitmap.height - cropTop)
                                    val croppedBitmap = if (cropLeft != 0 || cropTop != 0 ||
                                        cropWidth != decodedBitmap.width || cropHeight != decodedBitmap.height) {
                                        Bitmap.createBitmap(decodedBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                                    } else {
                                        decodedBitmap
                                    }
                                    // V2 uses 720p input, no scaling to 640x480
                                    val bitmap = scaleToTarget(croppedBitmap, 1280, 720)
                                    if (bitmap != croppedBitmap) croppedBitmap.recycle()
                                    if (croppedBitmap != decodedBitmap) decodedBitmap.recycle()

                                    if (shouldInfer) {
                                        maybeLaunchInference(bitmap, ffnetPack)
                                    }
                                    onFrame(bitmap, latestResult, latestLatencyMs)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)

                        if (!usePtsDelay) {
                            val elapsed = (SystemClock.elapsedRealtimeNanos() - frameStartNs) / 1_000_000f
                            val remaining = frameIntervalMs - elapsed
                            if (remaining > 0) delay(remaining.toLong())
                        }
                        decodedFrameIndex++
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        width = outputFormat.getInteger(MediaFormat.KEY_WIDTH, width)
                        height = outputFormat.getInteger(MediaFormat.KEY_HEIGHT, height)
                        colorFormat = outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                        stride = outputFormat.getInteger(MediaFormat.KEY_STRIDE, width)
                        sliceHeight = outputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
                        cropLeft = outputFormat.getInteger(MediaFormat.KEY_CROP_LEFT, 0)
                        cropTop = outputFormat.getInteger(MediaFormat.KEY_CROP_TOP, 0)
                        cropRight = outputFormat.getInteger(MediaFormat.KEY_CROP_RIGHT, width - 1)
                        cropBottom = outputFormat.getInteger(MediaFormat.KEY_CROP_BOTTOM, height - 1)
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || unsupportedFormat) {
                    outputDone = true
                }
            }
            !unsupportedFormat
        } catch (e: Exception) {
            Log.e(TAG, "Hardware decode loop failed", e)
            false
        } finally {
            try { decoder.stop() } catch (_: IllegalStateException) {}
            decoder.release()
            extractor.release()
        }
    }

    private suspend fun runVideoRetriever(
        path: String?,
        uri: Uri?,
        playbackFps: Int,
        inferenceFps: Int,
        onFrame: (Bitmap, FusionResultV2?, Float?) -> Unit
    ) {
        val retriever = withContext(Dispatchers.IO) {
            MediaMetadataRetriever().apply {
                if (path != null) {
                    setDataSource(path)
                } else if (uri != null) {
                    setDataSource(context, uri)
                }
            }
        }

        try {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return

            val frameIntervalMs = (1000f / playbackFps.coerceAtLeast(1)).toLong().coerceAtLeast(16L)
            val inferenceStride = (playbackFps / inferenceFps.coerceAtLeast(1)).coerceAtLeast(1)
            var timeMs = 0L
            var frameIndex = 0
            var lastFrameNs = SystemClock.elapsedRealtimeNanos()
            var frameCount = 0
            var sumDeltaMs = 0.0
            var maxDeltaMs = 0.0

            while (timeMs <= durationMs && currentCoroutineContext().isActive) {
                val frameStartNs = SystemClock.elapsedRealtimeNanos()
                val deltaMs = (frameStartNs - lastFrameNs) / 1_000_000.0
                lastFrameNs = frameStartNs
                frameCount += 1
                sumDeltaMs += deltaMs
                if (deltaMs > maxDeltaMs) maxDeltaMs = deltaMs
                if (frameCount % 30 == 0) {
                    val avgMs = sumDeltaMs / frameCount
                    Log.d(TAG, "decode tick avg=${"%.2f".format(avgMs)}ms max=${"%.2f".format(maxDeltaMs)}ms")
                }
                val shouldInfer = frameIndex % inferenceStride == 0
                val frameResult = processFrame(retriever, timeMs * 1000, shouldInfer)
                frameResult?.let { (bitmap, result, latencyMs) ->
                    onFrame(bitmap, result, latencyMs)
                }

                val elapsed = (SystemClock.elapsedRealtimeNanos() - frameStartNs) / 1_000_000f
                val remaining = frameIntervalMs - elapsed
                if (remaining > 0) delay(remaining.toLong())
                timeMs += frameIntervalMs
                frameIndex++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run video", e)
        } finally {
            withContext(Dispatchers.IO) { retriever.release() }
        }
    }

    private suspend fun processFrame(
        retriever: MediaMetadataRetriever,
        timestampUs: Long,
        runInference: Boolean
    ): DemoFrameV2? {
        return frameMutex.withLock {
            val decoded = withContext(Dispatchers.IO) {
                retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: return@withLock null

            // V2 uses 720p input
            val bitmap = scaleToTarget(decoded, 1280, 720)
            if (bitmap != decoded) decoded.recycle()

            if (!runInference) {
                return@withLock DemoFrameV2(bitmap, latestResult, null)
            }

            maybeLaunchInference(bitmap, null)
            DemoFrameV2(bitmap, latestResult, null)
        }
    }

    private fun scaleToTarget(decoded: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        }
    }

    private suspend fun runInference(
        bitmap: Bitmap,
        ffnetRgb: ByteBuffer?,
        ffnetPreMs: Double
    ): Pair<FusionResultV2?, Float?> {
        val proc = processor ?: return Pair(null, null)
        return withContext(Dispatchers.Default) {
            val startNs = SystemClock.elapsedRealtimeNanos()
            val result = proc.processFrame(bitmap, ffnetRgb, ffnetPreMs)
            val latencyMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000f
            Log.d(TAG, "V2 inference: total=${latencyMs}ms, " +
                "depth=${result.timings.depthInferMs}ms, " +
                "detect=${result.timings.detectInferMs}ms, " +
                "seg=${result.timings.segInferMs}ms, " +
                "feature=${result.timings.featureInferMs}ms, " +
                "fusion=${result.timings.fusionMs}ms")
            latestResult = result
            result to latencyMs
        }
    }

    private suspend fun runInference(bitmap: Bitmap): Pair<FusionResultV2?, Float?> {
        val proc = processor ?: return Pair(null, null)
        return withContext(Dispatchers.Default) {
            val startNs = SystemClock.elapsedRealtimeNanos()
            val result = proc.processFrame(bitmap)
            val latencyMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000f
            Log.d(TAG, "V2 inference: total=${latencyMs}ms, " +
                "depth=${result.timings.depthInferMs}ms, " +
                "detect=${result.timings.detectInferMs}ms, " +
                "seg=${result.timings.segInferMs}ms, " +
                "feature=${result.timings.featureInferMs}ms, " +
                "fusion=${result.timings.fusionMs}ms")
            latestResult = result
            result to latencyMs
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return index
        }
        return -1
    }

    private fun yuvToBitmap(
        data: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        colorFormat: Int
    ): Bitmap? {
        val required = width * height * 4
        var buffer = argbBuffer
        if (buffer == null || argbBufferSize != required) {
            buffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            argbBuffer = buffer
            argbBufferSize = required
        } else {
            buffer.clear()
        }

        val ok = YuvConverter.convertToArgb(data, width, height, stride, sliceHeight, colorFormat, buffer)
        if (!ok) return null

        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun yuvToFfnetRgb(
        data: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        colorFormat: Int,
        dstWidth: Int,
        dstHeight: Int
    ): Pair<ByteBuffer, Double>? {
        if (dstWidth <= 0 || dstHeight <= 0) return null
        val required = dstWidth * dstHeight * 3
        var buffer = ffnetRgbBuffer
        if (buffer == null || ffnetRgbBufferSize != required) {
            buffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            ffnetRgbBuffer = buffer
            ffnetRgbBufferSize = required
        } else {
            buffer.clear()
        }

        val t0 = System.nanoTime()
        val ok = YuvConverter.convertToRgb24Scaled(
            data,
            width,
            height,
            stride,
            sliceHeight,
            colorFormat,
            dstWidth,
            dstHeight,
            buffer
        )
        val t1 = System.nanoTime()
        if (!ok) return null
        buffer.rewind()
        val copy = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
        copy.put(buffer)
        copy.rewind()
        return copy to (t1 - t0) / 1_000_000.0
    }

    private data class Yuv420Planes(
        val y: ByteBuffer,
        val u: ByteBuffer,
        val v: ByteBuffer,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int,
        val width: Int,
        val height: Int,
        val crop: Rect
    )

    private fun extractYuv420Planes(image: Image): Yuv420Planes? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        if (width <= 0 || height <= 0) return null
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = slicePlane(
            yPlane.buffer,
            yPlane.rowStride,
            1,
            crop.left,
            crop.top
        )
        val uvLeft = crop.left / 2
        val uvTop = crop.top / 2
        val uBuf = slicePlane(
            uPlane.buffer,
            uPlane.rowStride,
            uPlane.pixelStride,
            uvLeft,
            uvTop
        )
        val vBuf = slicePlane(
            vPlane.buffer,
            vPlane.rowStride,
            vPlane.pixelStride,
            uvLeft,
            uvTop
        )
        return Yuv420Planes(
            y = yBuf,
            u = uBuf,
            v = vBuf,
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vPixelStride = vPlane.pixelStride,
            width = width,
            height = height,
            crop = Rect(crop)
        )
    }

    private fun slicePlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int
    ): ByteBuffer {
        val offset = rowStride * top + pixelStride * left
        val dup = buffer.duplicate()
        dup.position(offset)
        return dup.slice()
    }

    private fun yuv420ToBitmap(planes: Yuv420Planes, swapUv: Boolean = false): Bitmap? {
        val required = planes.width * planes.height * 4
        var buffer = argbBuffer
        if (buffer == null || argbBufferSize != required) {
            buffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            argbBuffer = buffer
            argbBufferSize = required
        } else {
            buffer.clear()
        }

        val uPlane = if (swapUv) planes.v else planes.u
        val vPlane = if (swapUv) planes.u else planes.v
        val uStride = if (swapUv) planes.vRowStride else planes.uRowStride
        val vStride = if (swapUv) planes.uRowStride else planes.vRowStride
        val uPix = if (swapUv) planes.vPixelStride else planes.uPixelStride
        val vPix = if (swapUv) planes.uPixelStride else planes.vPixelStride
        val ok = YuvConverter.convertYuv420ToArgb(
            planes.y,
            planes.yRowStride,
            uPlane,
            uStride,
            uPix,
            vPlane,
            vStride,
            vPix,
            planes.width,
            planes.height,
            buffer
        )
        if (!ok) return null
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(planes.width, planes.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun yuv420ToFfnetRgb(
        planes: Yuv420Planes,
        dstWidth: Int,
        dstHeight: Int,
        swapUv: Boolean = false
    ): Pair<ByteBuffer, Double>? {
        if (dstWidth <= 0 || dstHeight <= 0) return null
        val required = dstWidth * dstHeight * 3
        var buffer = ffnetRgbBuffer
        if (buffer == null || ffnetRgbBufferSize != required) {
            buffer = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            ffnetRgbBuffer = buffer
            ffnetRgbBufferSize = required
        } else {
            buffer.clear()
        }

        val t0 = System.nanoTime()
        val uPlane = if (swapUv) planes.v else planes.u
        val vPlane = if (swapUv) planes.u else planes.v
        val uStride = if (swapUv) planes.vRowStride else planes.uRowStride
        val vStride = if (swapUv) planes.uRowStride else planes.vRowStride
        val uPix = if (swapUv) planes.vPixelStride else planes.uPixelStride
        val vPix = if (swapUv) planes.uPixelStride else planes.vPixelStride
        val ok = YuvConverter.convertYuv420ToRgb24Scaled(
            planes.y,
            planes.yRowStride,
            uPlane,
            uStride,
            uPix,
            vPlane,
            vStride,
            vPix,
            planes.width,
            planes.height,
            dstWidth,
            dstHeight,
            buffer
        )
        val t1 = System.nanoTime()
        if (!ok) return null
        buffer.rewind()
        val copy = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
        copy.put(buffer)
        copy.rewind()
        return copy to (t1 - t0) / 1_000_000.0
    }

    private fun maybeLaunchInference(
        bitmap: Bitmap,
        ffnetPack: Pair<ByteBuffer, Double>?
    ) {
        if (!inferenceRunning.compareAndSet(false, true)) return
        inferenceJob?.cancel()
        inferenceJob = inferenceScope.launch {
            try {
                val (result, latencyMs) = if (ffnetPack != null) {
                    runInference(bitmap, ffnetPack.first, ffnetPack.second)
                } else {
                    runInference(bitmap)
                }
                latestResult = result
                latestLatencyMs = latencyMs
            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    fun submitLiveFrame(
        bitmap: Bitmap,
        inferenceInterval: Int = 3,
        onFrame: (Bitmap, FusionResultV2?, Float?) -> Unit
    ) {
        val normalized = scaleToTarget(bitmap, 1280, 720)
        val interval = inferenceInterval.coerceAtLeast(1)
        val shouldInfer = (liveFrameCounter++ % interval) == 0
        if (shouldInfer) {
            maybeLaunchInference(normalized, null)
        }
        onFrame(normalized, latestResult, latestLatencyMs)
    }

    fun reset() {
        processor?.reset()
        latestResult = null
        latestLatencyMs = null
        liveFrameCounter = 0
    }

    override fun close() {
        processor?.close()
        processor = null
        latestResult = null
        latestLatencyMs = null
        inferenceJob?.cancel()
        inferenceJob = null
        inferenceJobParent.cancel()
    }

    private data class DemoFrameV2(
        val bitmap: Bitmap,
        val result: FusionResultV2?,
        val latencyMs: Float?
    )

    private companion object {
        private const val TAG = "SafetyDemoVideoV2"
    }
}
