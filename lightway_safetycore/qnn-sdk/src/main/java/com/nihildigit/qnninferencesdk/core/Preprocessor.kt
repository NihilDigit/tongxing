package com.nihildigit.qnninferencesdk.core

import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Preprocessor {
    /**
     * Prepare NHWC ByteBuffer for TFLite input.
     * - FLOAT32: normalized [0,1]
     * - UINT8: raw 0..255
     * - INT8: currently not supported (returns null)
     *
     * Uses OpenCV for acceleration when available (especially for large images).
     */
    fun bitmapToInput(
        src: Bitmap,
        height: Int,
        width: Int,
        dtype: DataType
    ): ByteBuffer? {
        // Try OpenCV first (much faster for large images like FFNet 2048x1024)
        val needsResize = src.width != width || src.height != height
        if (needsResize && OpenCVPreprocessor.isAvailable()) {
            val result = OpenCVPreprocessor.bitmapToInput(src, height, width, dtype)
            if (result != null) return result
        }

        // Fast-path: if src already matches model size and native library available, use JNI packers
        val useFastPath = (!needsResize && src.config != Bitmap.Config.HARDWARE)

        return when (dtype) {
            DataType.FLOAT32 -> {
                val buf = ByteBuffer.allocateDirect(4 * width * height * 3).order(ByteOrder.nativeOrder())
                if (useFastPath) {
                    try {
                        if (NativeOps.packArgbToRgbF(src, buf, width, height)) {
                            buf.rewind()
                            return buf
                        }
                    } catch (t: Throwable) {
                        Log.w("QNN-Preprocessor", "native packArgbToRgbF failed, falling back: ${t.message}")
                    }
                }
                // Fallback: scale + Java packing
                val sw = if (src.config == Bitmap.Config.HARDWARE) {
                    try { src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { src }
                } else src
                val resized = if (useFastPath) src else Bitmap.createScaledBitmap(sw, width, height, true)
                val pixels = IntArray(width * height)
                resized.getPixels(pixels, 0, width, 0, 0, width, height)
                val fbuf = buf.asFloatBuffer()
                for (p in pixels) {
                    val r = ((p ushr 16) and 0xFF) / 255f
                    val g = ((p ushr 8) and 0xFF) / 255f
                    val b = (p and 0xFF) / 255f
                    fbuf.put(r); fbuf.put(g); fbuf.put(b)
                }
                buf.rewind(); buf
            }
            DataType.UINT8 -> {
                val buf = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
                if (useFastPath) {
                    try {
                        if (NativeOps.packArgbToRgbU8(src, buf, width, height)) {
                            buf.rewind()
                            return buf
                        }
                    } catch (t: Throwable) {
                        Log.w("QNN-Preprocessor", "native packArgbToRgbU8 failed, falling back: ${t.message}")
                    }
                }
                // Fallback: scale + Java packing
                val sw = if (src.config == Bitmap.Config.HARDWARE) {
                    try { src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { src }
                } else src
                val resized = if (useFastPath) src else Bitmap.createScaledBitmap(sw, width, height, true)
                val pixels = IntArray(width * height)
                resized.getPixels(pixels, 0, width, 0, 0, width, height)
                for (p in pixels) {
                    buf.put(((p ushr 16) and 0xFF).toByte())
                    buf.put(((p ushr 8) and 0xFF).toByte())
                    buf.put((p and 0xFF).toByte())
                }
                buf.rewind(); buf
            }
            else -> null
        }
    }
}
