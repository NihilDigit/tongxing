package com.nihildigit.safetycore.v2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.nihildigit.safetycore.v2.types.FusionResultV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Video processor for V2 pipeline.
 * Processes video files frame by frame through the V2 fusion pipeline.
 */
class VideoProcessorV2(
    private val context: Context,
    private val config: ConfigV2 = ConfigV2()
) : AutoCloseable {

    interface Listener {
        fun onFrameProcessed(frameIndex: Int, totalFrames: Int, result: FusionResultV2)
        fun onProgress(progress: Float)
        fun onComplete(results: List<FusionResultV2>)
        fun onError(error: Throwable)
    }

    private var processor: FrameProcessorV2? = null
    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    /**
     * Initialize the processor.
     */
    fun init(): Boolean {
        return try {
            processor = SafetyCoreV2Engine.createFrameProcessor(context, config)
            true
        } catch (e: Exception) {
            listener?.onError(e)
            false
        }
    }

    /**
     * Process a video file.
     * @param videoUri URI of the video file
     * @param targetFps Target frame rate for processing (default 10 fps)
     */
    suspend fun processVideo(videoUri: Uri, targetFps: Float = 10f): List<FusionResultV2> {
        val proc = processor ?: throw IllegalStateException("Processor not initialized")
        val results = mutableListOf<FusionResultV2>()

        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, videoUri)

                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L

                val frameIntervalUs = (1_000_000L / targetFps).toLong()
                val totalFrames = ((durationMs * 1000L) / frameIntervalUs).toInt()

                var frameIndex = 0
                var timeUs = 0L

                while (timeUs < durationMs * 1000L) {
                    yield() // Allow cancellation

                    val bitmap = retriever.getFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )

                    if (bitmap != null) {
                        val result = proc.processFrame(bitmap)
                        results.add(result)

                        withContext(Dispatchers.Main) {
                            listener?.onFrameProcessed(frameIndex, totalFrames, result)
                            listener?.onProgress(frameIndex.toFloat() / totalFrames)
                        }

                        bitmap.recycle()
                    }

                    frameIndex++
                    timeUs += frameIntervalUs
                }

                withContext(Dispatchers.Main) {
                    listener?.onComplete(results)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener?.onError(e)
                }
            } finally {
                retriever.release()
            }
        }

        return results
    }

    /**
     * Process a single frame from bitmap.
     */
    fun processFrame(bitmap: Bitmap): FusionResultV2? {
        return processor?.processFrame(bitmap)
    }

    /**
     * Reset the processor state.
     */
    fun reset() {
        processor?.reset()
    }

    override fun close() {
        processor?.close()
        processor = null
    }
}
