package com.nihildigit.safetycore.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor

/**
 * Utilities for visualizing depth, segmentation, and detections.
 */
object Visualization {

    fun binaryMaskToBitmap(mask: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in mask.indices) {
            pixels[i] = if (mask[i] != 0.toByte()) {
                0xFFFFFFFF.toInt()
            } else {
                0xFF000000.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun colorizeDepth(
        depth: FloatArray,
        w: Int,
        h: Int,
        min: Float? = null,
        max: Float? = null,
        invalidToTransparent: Boolean = true
    ): Bitmap {
        val validDepths = depth.filter { it.isFinite() }
        val dMin = min ?: validDepths.minOrNull() ?: 0f
        val dMax = max ?: validDepths.maxOrNull() ?: 1f

        val pixels = IntArray(w * h)
        for (i in depth.indices) {
            val d = depth[i]
            pixels[i] = when {
                !d.isFinite() && invalidToTransparent -> 0x00000000
                !d.isFinite() -> 0xFF000000.toInt()
                else -> {
                    val normalized = if (dMax > dMin) {
                        ((d - dMin) / (dMax - dMin)).coerceIn(0f, 1f)
                    } else 0.5f
                    jetColormap(normalized)
                }
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun drawBoxes(
        src: Bitmap,
        boxes: List<FloatArray>,
        color: Int = 0x800000FF.toInt(),
        strokeWidth: Float = 3f
    ): Bitmap {
        val output = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }

        for (box in boxes) {
            val x = box[0]
            val y = box[1]
            val wBox = box[2]
            val hBox = box[3]
            canvas.drawRect(x, y, x + wBox, y + hBox, paint)
        }
        return output
    }

    fun toGrayBitmap(values: FloatArray, w: Int, h: Int): Bitmap {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in values) {
            if (v.isFinite()) {
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        if (!min.isFinite() || !max.isFinite() || max <= min) {
            min = 0f; max = 1f
        }
        val range = max - min
        val pixels = IntArray(w * h)
        var i = 0
        while (i < values.size) {
            val v = values[i]
            val n = if (v.isFinite()) ((v - min) / range).coerceIn(0f, 1f) else 0f
            val g = (n * 255f).toInt().coerceIn(0, 255)
            val color = AndroidColor.argb(255, g, g, g)
            pixels[i] = color
            i++
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    fun toGrayQuantized(values: FloatArray, w: Int, h: Int, scale: Float, zeroPoint: Int = 0): Bitmap {
        val pixels = IntArray(w * h)
        var i = 0
        val inv = if (scale != 0f) 1f / scale else 0f
        while (i < values.size) {
            val v = values[i]
            val q = if (v.isFinite()) (v * inv) + zeroPoint else 0f
            val g = q.toInt().coerceIn(0, 255)
            pixels[i] = AndroidColor.argb(255, g, g, g)
            i++
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun jetColormap(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        val r: Float
        val g: Float
        val b: Float

        when {
            v < 0.25f -> {
                r = 0f
                g = v * 4f
                b = 1f
            }
            v < 0.5f -> {
                r = 0f
                g = 1f
                b = 1f - (v - 0.25f) * 4f
            }
            v < 0.75f -> {
                r = (v - 0.5f) * 4f
                g = 1f
                b = 0f
            }
            else -> {
                r = 1f
                g = 1f - (v - 0.75f) * 4f
                b = 0f
            }
        }

        val ri = (r * 255).toInt().coerceIn(0, 255)
        val gi = (g * 255).toInt().coerceIn(0, 255)
        val bi = (b * 255).toInt().coerceIn(0, 255)

        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
