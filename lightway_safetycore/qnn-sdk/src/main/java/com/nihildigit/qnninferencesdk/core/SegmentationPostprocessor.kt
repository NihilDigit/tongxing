package com.nihildigit.qnninferencesdk.core

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

object SegmentationPostprocessor {
    data class SegResult(val classMap: ByteArray, val width: Int, val height: Int)

    // Reusable per-thread buffers to avoid per-frame allocations
    private val threadCache = object {
        private val local = ThreadLocal<Cache>()
        fun get(h: Int, w: Int): Cache {
            var c = local.get()
            if (c == null || c.h != h || c.w != w) {
                c = Cache(h, w)
                local.set(c)
            }
            return c
        }
    }
    private class Cache(val h: Int, val w: Int) {
        var out: ByteArray = ByteArray(h * w)
        var arrU8: ByteArray? = null
        var arrF32: FloatArray? = null
        var lastC: Int = -1
        fun ensureOut(): ByteArray {
            if (out.size != h * w) out = ByteArray(h * w)
            return out
        }
        fun ensureU8Array(c: Int): ByteArray {
            val need = h * w * c
            if (arrU8 == null || arrU8!!.size != need || lastC != c) {
                arrU8 = ByteArray(need)
                lastC = c
            }
            return arrU8!!
        }
        fun ensureF32Array(c: Int): FloatArray {
            val need = h * w * c
            if (arrF32 == null || arrF32!!.size != need || lastC != c) {
                arrF32 = FloatArray(need)
                lastC = c
            }
            return arrF32!!
        }
    }

    /**
     * Compute per-pixel argmax for output in either NHWC [1,H,W,C] or NCHW [1,C,H,W].
     * For UINT8/INT8，直接比较q值即可；对FLOAT32，同理比较浮点。
     */
    fun argmax(
        interpreter: Interpreter,
        outputIndex: Int = 0
    ): SegResult {
        val t = interpreter.getOutputTensor(outputIndex)
        val shape = t.shape()
        require(shape.size == 4 && shape[0] == 1) { "Unexpected output shape: ${shape.contentToString()}" }
        val dtype = t.dataType()

        // Detect layout by finding the likely classes dimension (the smallest among H/W/C)
        val dims = intArrayOf(shape[1], shape[2], shape[3])
        val minIdx = dims.withIndex().minBy { it.value }.index // 0->C(NCHW), 2->C(NHWC)
        val isNHWC = (minIdx == 2)
        val (h, w, c) = if (isNHWC) Triple(shape[1], shape[2], shape[3]) else Triple(shape[2], shape[3], shape[1])

        when (dtype) {
            DataType.UINT8, DataType.INT8 -> {
                val buf = t.asReadOnlyBuffer()
                val cache = threadCache.get(h, w)
                val out = cache.ensureOut()
                val arr = cache.ensureU8Array(c)
                buf.get(arr)
                val ok = try {
                    if (isNHWC) NativeOps.argmaxU8NHWC(arr, h, w, c, out)
                    else NativeOps.argmaxU8NCHW(arr, h, w, c, out)
                } catch (_: Throwable) { false }
                if (!ok) {
                    // Fallback to Kotlin
                    if (isNHWC) {
                        var base = 0
                        for (i in 0 until h * w) {
                            var maxIdx = 0
                            var maxVal = arr[base].toInt() and 0xFF
                            for (cls in 1 until c) {
                                val v = arr[base + cls].toInt() and 0xFF
                                if (v > maxVal) { maxVal = v; maxIdx = cls }
                            }
                            out[i] = maxIdx.toByte()
                            base += c
                        }
                    } else {
                        val hw = h * w
                        var i = 0
                        for (hh in 0 until h) {
                            for (ww in 0 until w) {
                                var maxIdx = 0
                                var maxVal = (arr[(0 * hw) + (hh * w + ww)].toInt() and 0xFF)
                                for (cls in 1 until c) {
                                    val v = (arr[(cls * hw) + (hh * w + ww)].toInt() and 0xFF)
                                    if (v > maxVal) { maxVal = v; maxIdx = cls }
                                }
                                out[i++] = maxIdx.toByte()
                            }
                        }
                    }
                }
                return SegResult(out, w, h)
            }
            DataType.FLOAT32 -> {
                val fb = t.asReadOnlyBuffer().asFloatBuffer()
                val cache = threadCache.get(h, w)
                val out = cache.ensureOut()
                val arr = cache.ensureF32Array(c)
                fb.get(arr)
                val ok = try {
                    if (isNHWC) NativeOps.argmaxF32NHWC(arr, h, w, c, out)
                    else NativeOps.argmaxF32NCHW(arr, h, w, c, out)
                } catch (_: Throwable) { false }
                if (!ok) {
                    // Fallback to Kotlin
                    if (isNHWC) {
                        var base = 0
                        for (i in 0 until h * w) {
                            var maxIdx = 0
                            var maxVal = arr[base]
                            for (cls in 1 until c) {
                                val v = arr[base + cls]
                                if (v > maxVal) { maxVal = v; maxIdx = cls }
                            }
                            out[i] = maxIdx.toByte()
                            base += c
                        }
                    } else {
                        val hw = h * w
                        var i = 0
                        for (hh in 0 until h) {
                            for (ww in 0 until w) {
                                var maxIdx = 0
                                var maxVal = arr[(0 * hw) + (hh * w + ww)]
                                for (cls in 1 until c) {
                                    val v = arr[(cls * hw) + (hh * w + ww)]
                                    if (v > maxVal) { maxVal = v; maxIdx = cls }
                                }
                                out[i++] = maxIdx.toByte()
                            }
                        }
                    }
                }
                return SegResult(out, w, h)
            }
            else -> error("Unsupported dtype: $dtype")
        }
    }
}
