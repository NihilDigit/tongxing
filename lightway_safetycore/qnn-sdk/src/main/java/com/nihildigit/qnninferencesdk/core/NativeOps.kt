package com.nihildigit.qnninferencesdk.core

import android.graphics.Bitmap
import java.nio.ByteBuffer

internal object NativeOps {
    init {
        try {
            System.loadLibrary("qnnops")
        } catch (_: Throwable) {
            // Library not available; native fast-path disabled
        }
    }

    external fun packArgbToRgbF(src: Bitmap, dst: ByteBuffer, width: Int, height: Int): Boolean
    external fun packArgbToRgbU8(src: Bitmap, dst: ByteBuffer, width: Int, height: Int): Boolean

    // Argmax native helpers (faster than Kotlin on large tensors)
    external fun argmaxU8NHWC(arr: ByteArray, h: Int, w: Int, c: Int, out: ByteArray): Boolean
    external fun argmaxU8NCHW(arr: ByteArray, h: Int, w: Int, c: Int, out: ByteArray): Boolean
    external fun argmaxF32NHWC(arr: FloatArray, h: Int, w: Int, c: Int, out: ByteArray): Boolean
    external fun argmaxF32NCHW(arr: FloatArray, h: Int, w: Int, c: Int, out: ByteArray): Boolean
}
