package com.nihildigit.qnninferencesdk.core

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

object DepthPostprocessor {
    /**
     * Read depth output and return FloatArray [H*W].
     * If quantized, dequantize via scale/zeroPoint.
     * Normalizes output to [0, 1] range (higher = closer).
     */
    fun readDepthAsFloat(
        interpreter: Interpreter,
        outputIndex: Int = 0
    ): FloatArray {
        val t = interpreter.getOutputTensor(outputIndex)
        val shape = t.shape() // [1, H, W, 1] or [1,1,H,W]
        val dtype = t.dataType()

        // Resolve H*W assuming NHWC first; fall back to NCHW single-channel
        val hw = when (shape.size) {
            4 -> if (shape[3] == 1) shape[1] * shape[2] else shape[2] * shape[3]
            else -> error("Unexpected output shape: ${shape.contentToString()}")
        }

        val out = when (dtype) {
            DataType.FLOAT32 -> {
                val fb = t.asReadOnlyBuffer().asFloatBuffer()
                val arr = FloatArray(hw)
                fb.get(arr)
                arr
            }
            DataType.UINT8, DataType.INT8 -> {
                val q = t.quantizationParams()
                val scale = q.scale
                val zero = q.zeroPoint
                val bb = t.asReadOnlyBuffer()
                val bytes = ByteArray(hw)
                bb.get(bytes)
                val arr = FloatArray(hw)
                for (i in 0 until hw) {
                    val qv = if (dtype == DataType.UINT8) (bytes[i].toInt() and 0xFF) else bytes[i].toInt()
                    arr[i] = scale * (qv - zero)
                }
                arr
            }
            else -> error("Unsupported dtype: $dtype")
        }

        // Normalize to [0, 1] range (matching Python implementation)
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        for (v in out) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val range = maxVal - minVal
        if (range > 1e-6f) {
            for (i in out.indices) {
                out[i] = (out[i] - minVal) / range
            }
        } else {
            out.fill(0f)
        }

        return out
    }
}
