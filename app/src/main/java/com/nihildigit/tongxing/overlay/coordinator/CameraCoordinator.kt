package com.nihildigit.tongxing.overlay.coordinator

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.nihildigit.tongxing.state.AppState
import java.util.concurrent.Executors

/**
 * CameraX 协调器。
 * 管理相机生命周期、帧分析和 ImageProxy→Bitmap 转换。
 */
class CameraCoordinator(
    private val context: Context,
    private val targetResolution: android.util.Size = android.util.Size(640, 480),
    private val targetFpsRange: android.util.Range<Int> = android.util.Range(30, 30),
    private val onFrame: (bitmap: Bitmap, timestampNanos: Long) -> Unit
) {
    companion object {
        private const val TAG = "CameraCoordinator"
    }

    private val cameraExecutor = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var frameCount = 0
        private set

    fun start() {
        if (cameraProvider != null) {
            Log.d(TAG, "Already started, skipping")
            AppState.setCameraHealth(AppState.ComponentHealth.READY)
            return
        }
        AppState.setCameraHealth(AppState.ComponentHealth.INITIALIZING)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
                AppState.setCameraHealth(AppState.ComponentHealth.READY)
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed", e)
                AppState.setCameraHealth(AppState.ComponentHealth.FAILED)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }
        Log.i(TAG, "stop() called")
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalysis = null
        AppState.setPreviewFrame(null)
        AppState.setLatestFrameResult(null)
    }

    fun shutdown() {
        stop()
        cameraExecutor.shutdown()
    }

    // --- Internal ---

    private class AlwaysActiveLifecycleOwner : androidx.lifecycle.LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)
        init { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }
    private val alwaysActiveLifecycleOwner = AlwaysActiveLifecycleOwner()

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val analysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        Camera2Interop.Extender(analysisBuilder)
            .setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                targetFpsRange
            )

        imageAnalysis = analysisBuilder.build().also { analysis ->
            analysis.setAnalyzer(cameraExecutor) { image ->
                frameCount++
                val count = frameCount

                if (count <= 5 || count % 100 == 0) {
                    Log.i(TAG, "Frame #$count | res=${image.width}x${image.height}")
                }

                try {
                    val bmp = imageProxyToBitmap(image)
                    if (bmp != null) {
                        val ts = android.os.SystemClock.elapsedRealtimeNanos()
                        onFrame(bmp, ts)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Analyzer error at frame #$count", e)
                } finally {
                    image.close()
                }
            }
        }

        val dummySurfaceTexture = android.graphics.SurfaceTexture(10)
        val preview = androidx.camera.core.Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            val surface = android.view.Surface(dummySurfaceTexture)
            request.provideSurface(surface, cameraExecutor) {
                surface.release()
                dummySurfaceTexture.release()
            }
            Log.d(TAG, "Hidden Preview surface provided")
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                alwaysActiveLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            Log.i(TAG, "Started with ALWAYS_ACTIVE LifecycleOwner")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val expectedRowBytes = width * pixelStride
            val buffer: java.nio.ByteBuffer = plane.buffer
            buffer.rewind()
            if (rowStride == expectedRowBytes) {
                bmp.copyPixelsFromBuffer(buffer)
            } else {
                val contiguous = java.nio.ByteBuffer.allocateDirect(expectedRowBytes * height)
                val row = ByteArray(expectedRowBytes)
                for (y in 0 until height) {
                    val pos = y * rowStride
                    buffer.position(pos)
                    buffer.get(row, 0, expectedRowBytes)
                    contiguous.put(row)
                }
                contiguous.rewind()
                bmp.copyPixelsFromBuffer(contiguous)
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "imageProxyToBitmap failed", e)
            null
        }
    }
}
