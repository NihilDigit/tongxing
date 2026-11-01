package com.nihildigit.tongxing.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.nihildigit.safetycore.v2.ConfigV2
import com.nihildigit.safetycore.v2.FrameProcessorV2
import com.nihildigit.safetycore.v2.SafetyCoreV2Engine
import com.nihildigit.safetycore.v2.types.FusionResultV2
import com.nihildigit.tongxing.util.YuvConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline video exporter: decode → V2 inference → overlay → encode.
 * No playback delay — processes as fast as the pipeline allows.
 */
class VideoExporter(private val context: Context) : AutoCloseable {

    private var processor: FrameProcessorV2? = null
    private var argbBuffer: ByteBuffer? = null
    private var argbBufferSize: Int = 0

    fun init(): Boolean {
        return try {
            processor = SafetyCoreV2Engine.createFrameProcessor(context.applicationContext, ConfigV2())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init V2 processor", e)
            false
        }
    }

    /**
     * Export annotated video.
     * @param inputPath  source video file path
     * @param outputPath output MP4 file path
     * @param inferenceFps target inference frequency (default 15)
     * @param onProgress callback(currentFrame, totalEstimate)
     */
    fun export(
        inputPath: String,
        outputPath: String,
        inferenceFps: Int = 15,
        onProgress: ((Int, Int) -> Unit)? = null
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        val trackIndex = selectVideoTrack(extractor)
        check(trackIndex >= 0) { "No video track found in $inputPath" }

        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: error("No MIME type in video track")
        var width = format.getInteger(MediaFormat.KEY_WIDTH)
        var height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val streamFps = format.getInteger(MediaFormat.KEY_FRAME_RATE, 30).coerceAtLeast(1)
        val durationUs = format.getLong(MediaFormat.KEY_DURATION, 0L)
        val estimatedFrames = if (durationUs > 0) ((durationUs / 1_000_000.0) * streamFps).toInt() else 0

        var colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1)
        var stride = format.getInteger(MediaFormat.KEY_STRIDE, width)
        var sliceHeight = format.getInteger(MediaFormat.KEY_SLICE_HEIGHT, height)
        var cropLeft = format.getInteger(MediaFormat.KEY_CROP_LEFT, 0)
        var cropTop = format.getInteger(MediaFormat.KEY_CROP_TOP, 0)
        var cropRight = format.getInteger(MediaFormat.KEY_CROP_RIGHT, width - 1)
        var cropBottom = format.getInteger(MediaFormat.KEY_CROP_BOTTOM, height - 1)

        val inferenceStride = (streamFps / inferenceFps.coerceAtLeast(1)).coerceAtLeast(1)
        Log.i(TAG, "EXPORT_START input=$inputPath streamFps=$streamFps inferStride=$inferenceStride est=$estimatedFrames")

        // Setup decoder
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        extractor.selectTrack(trackIndex)

        // Setup encoder + muxer
        val outWidth = 1280
        val outHeight = 720
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, streamFps)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = InputSurface(encoder.createInputSurface())
        encoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val encoderBufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frameIndex = 0
        var lastResult: FusionResultV2? = null
        val frameDurationNs = (1_000_000_000L / streamFps)
        var loggedFormat = false

        try {
            while (!outputDone) {
                // Feed decoder input
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read decoder output
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val bitmap = decodeToBitmap(decoder, outputIndex, bufferInfo,
                            width, height, stride, sliceHeight, colorFormat,
                            cropLeft, cropTop, cropRight, cropBottom, loggedFormat)
                        if (!loggedFormat) loggedFormat = true

                        if (bitmap != null) {
                            val scaled = scaleToTarget(bitmap, outWidth, outHeight)
                            if (scaled != bitmap) bitmap.recycle()

                            // Synchronous inference
                            val shouldInfer = frameIndex % inferenceStride == 0
                            if (shouldInfer) {
                                try {
                                    lastResult = processor?.processFrame(scaled)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Inference failed frame=$frameIndex", e)
                                }
                            }

                            // Render overlay
                            val annotated = OverlayRenderer.render(scaled, lastResult)
                            scaled.recycle()

                            // Encode
                            val ptsNs = frameIndex.toLong() * frameDurationNs
                            inputSurface.drawBitmap(annotated, outWidth, outHeight)
                            inputSurface.setPresentationTime(ptsNs)
                            inputSurface.swapBuffers()
                            annotated.recycle()

                            drainEncoder(encoder, encoderBufferInfo, muxer, muxerTrackIndex, muxerStarted).let {
                                muxerTrackIndex = it.first
                                muxerStarted = it.second
                            }

                            frameIndex++
                            if (frameIndex % 30 == 0) {
                                Log.i(TAG, "EXPORT_PROGRESS frame=$frameIndex/$estimatedFrames")
                                onProgress?.invoke(frameIndex, estimatedFrames)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
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
            }

            // Signal encoder EOS and drain
            encoder.signalEndOfInputStream()
            var draining = true
            while (draining) {
                val idx = encoder.dequeueOutputBuffer(encoderBufferInfo, 10_000)
                when {
                    idx >= 0 -> {
                        if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            draining = false
                        }
                        if (muxerStarted && encoderBufferInfo.size > 0) {
                            val buf = encoder.getOutputBuffer(idx)
                            if (buf != null) {
                                muxer.writeSampleData(muxerTrackIndex, buf, encoderBufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(idx, false)
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Brief wait for encoder to produce output
                        Thread.sleep(5)
                    }
                }
            }

            Log.i(TAG, "EXPORT_DONE frames=$frameIndex output=$outputPath")
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            decoder.release()
            extractor.release()
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            inputSurface.release()
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            muxer.release()
        }
    }

    private fun decodeToBitmap(
        decoder: MediaCodec,
        outputIndex: Int,
        bufferInfo: MediaCodec.BufferInfo,
        width: Int, height: Int,
        stride: Int, sliceHeight: Int, colorFormat: Int,
        cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int,
        logged: Boolean
    ): Bitmap? {
        // Try Image API first (YUV_420_888)
        val image = decoder.getOutputImage(outputIndex)
        if (image != null) {
            try {
                if (image.format == ImageFormat.YUV_420_888) {
                    val planes = extractYuv420Planes(image)
                    if (planes != null) {
                        if (!logged) {
                            Log.d(TAG, "decode fmt=YUV_420_888 w=${planes.width} h=${planes.height}")
                        }
                        return yuv420ToBitmap(planes, swapUv = true)
                    }
                }
            } finally {
                image.close()
            }
        }

        // Fallback: raw buffer
        val buffer = decoder.getOutputBuffer(outputIndex) ?: return null
        if (bufferInfo.size <= 0) return null
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        val yuvData = ByteArray(bufferInfo.size)
        buffer.get(yuvData)

        if (!logged) {
            Log.d(TAG, "decode fmt=$colorFormat w=$width h=$height stride=$stride slice=$sliceHeight")
        }

        val decoded = yuvToBitmap(yuvData, width, height, stride, sliceHeight, colorFormat)
            ?: return null

        val cropWidth = (cropRight - cropLeft + 1).coerceAtMost(decoded.width - cropLeft)
        val cropHeight = (cropBottom - cropTop + 1).coerceAtMost(decoded.height - cropTop)
        return if (cropLeft != 0 || cropTop != 0 || cropWidth != decoded.width || cropHeight != decoded.height) {
            val cropped = Bitmap.createBitmap(decoded, cropLeft, cropTop, cropWidth, cropHeight)
            if (cropped != decoded) decoded.recycle()
            cropped
        } else {
            decoded
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        trackIndex: Int,
        started: Boolean
    ): Pair<Int, Boolean> {
        var muxTrack = trackIndex
        var muxStarted = started
        while (true) {
            val idx = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                idx >= 0 -> {
                    if (muxStarted && bufferInfo.size > 0) {
                        val buf = encoder.getOutputBuffer(idx)
                        if (buf != null) {
                            muxer.writeSampleData(muxTrack, buf, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxStarted) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                }
                else -> break
            }
        }
        return muxTrack to muxStarted
    }

    // ---- YUV conversion (reuses YuvConverter JNI, adapts private wrappers) ----

    private fun yuvToBitmap(data: ByteArray, width: Int, height: Int, stride: Int, sliceHeight: Int, colorFormat: Int): Bitmap? {
        val required = width * height * 4
        val buffer = ensureArgbBuffer(required)
        val ok = YuvConverter.convertToArgb(data, width, height, stride, sliceHeight, colorFormat, buffer)
        if (!ok) return null
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private data class Yuv420Planes(
        val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer,
        val yRowStride: Int, val uRowStride: Int, val vRowStride: Int,
        val uPixelStride: Int, val vPixelStride: Int,
        val width: Int, val height: Int
    )

    private fun extractYuv420Planes(image: Image): Yuv420Planes? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null
        val planes = image.planes
        if (planes.size < 3) return null

        fun slicePlane(buf: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int): ByteBuffer {
            val dup = buf.duplicate()
            dup.position(rowStride * top + pixelStride * left)
            return dup.slice()
        }

        val uvLeft = crop.left / 2
        val uvTop = crop.top / 2
        return Yuv420Planes(
            y = slicePlane(planes[0].buffer, planes[0].rowStride, 1, crop.left, crop.top),
            u = slicePlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, uvLeft, uvTop),
            v = slicePlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, uvLeft, uvTop),
            yRowStride = planes[0].rowStride,
            uRowStride = planes[1].rowStride, vRowStride = planes[2].rowStride,
            uPixelStride = planes[1].pixelStride, vPixelStride = planes[2].pixelStride,
            width = w, height = h
        )
    }

    private fun yuv420ToBitmap(planes: Yuv420Planes, swapUv: Boolean = false): Bitmap? {
        val required = planes.width * planes.height * 4
        val buffer = ensureArgbBuffer(required)
        val uPlane = if (swapUv) planes.v else planes.u
        val vPlane = if (swapUv) planes.u else planes.v
        val uStride = if (swapUv) planes.vRowStride else planes.uRowStride
        val vStride = if (swapUv) planes.uRowStride else planes.vRowStride
        val uPix = if (swapUv) planes.vPixelStride else planes.uPixelStride
        val vPix = if (swapUv) planes.uPixelStride else planes.vPixelStride
        val ok = YuvConverter.convertYuv420ToArgb(
            planes.y, planes.yRowStride, uPlane, uStride, uPix, vPlane, vStride, vPix,
            planes.width, planes.height, buffer
        )
        if (!ok) return null
        buffer.rewind()
        val bitmap = Bitmap.createBitmap(planes.width, planes.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun ensureArgbBuffer(required: Int): ByteBuffer {
        var buf = argbBuffer
        if (buf == null || argbBufferSize != required) {
            buf = ByteBuffer.allocateDirect(required).order(ByteOrder.nativeOrder())
            argbBuffer = buf
            argbBufferSize = required
        } else {
            buf.clear()
        }
        return buf
    }

    private fun scaleToTarget(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return if (bitmap.width == targetWidth && bitmap.height == targetHeight) bitmap
        else Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/")) return i
        }
        return -1
    }

    override fun close() {
        processor?.close()
        processor = null
    }

    companion object {
        private const val TAG = "LW_EXPORT"
    }
}
