package com.nihildigit.qnninferencesdk.core

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenCV-accelerated image preprocessing for TFLite models.
 * Significantly faster than Java-based preprocessing for large images.
 */
object OpenCVPreprocessor {
    private const val TAG = "OpenCVPreprocessor"

    private var initialized = false

    init {
        try {
            System.loadLibrary("opencv_java4")
            initialized = true
            Log.i(TAG, "OpenCV initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "OpenCV not available: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = initialized

    /**
     * Fast preprocessing using OpenCV.
     * - Resize using OpenCV (much faster than Bitmap.createScaledBitmap)
     * - Convert BGRA to RGB
     * - Pack into ByteBuffer
     */
    fun bitmapToInput(
        src: Bitmap,
        height: Int,
        width: Int,
        dtype: DataType
    ): ByteBuffer? {
        if (!initialized) return null

        return try {
            when (dtype) {
                DataType.UINT8 -> processUint8(src, height, width)
                DataType.FLOAT32 -> processFloat32(src, height, width)
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV preprocessing failed: ${e.message}")
            null
        }
    }

    private fun processUint8(src: Bitmap, height: Int, width: Int): ByteBuffer {
        // Convert Bitmap to Mat (BGRA)
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // Resize if needed
        val resized = if (src.width != width || src.height != height) {
            val dst = Mat()
            Imgproc.resize(srcMat, dst, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            srcMat.release()
            dst
        } else {
            srcMat
        }

        // Convert BGRA to RGB
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGRA2RGB)
        resized.release()

        // Pack into ByteBuffer
        val buf = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
        val byteArray = ByteArray(width * height * 3)
        rgb.get(0, 0, byteArray)
        rgb.release()

        buf.put(byteArray)
        buf.rewind()
        return buf
    }

    private fun processFloat32(src: Bitmap, height: Int, width: Int): ByteBuffer {
        // Convert Bitmap to Mat (BGRA)
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // Resize if needed
        val resized = if (src.width != width || src.height != height) {
            val dst = Mat()
            Imgproc.resize(srcMat, dst, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            srcMat.release()
            dst
        } else {
            srcMat
        }

        // Convert BGRA to RGB
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGRA2RGB)
        resized.release()

        // Convert to float and normalize to [0, 1]
        val rgbFloat = Mat()
        rgb.convertTo(rgbFloat, CvType.CV_32FC3, 1.0 / 255.0)
        rgb.release()

        // Pack into ByteBuffer
        val buf = ByteBuffer.allocateDirect(4 * width * height * 3).order(ByteOrder.nativeOrder())
        val floatArray = FloatArray(width * height * 3)
        rgbFloat.get(0, 0, floatArray)
        rgbFloat.release()

        buf.asFloatBuffer().put(floatArray)
        buf.rewind()
        return buf
    }
}