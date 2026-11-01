package com.nihildigit.safetycore.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.nihildigit.safetycore.core.LetterboxMap
import com.nihildigit.safetycore.core.Size
import android.util.Log

object Preprocessor {
    /**
     * Letterbox to target size, returning the resized bitmap and mapping params.
     */
    fun letterbox(input: Bitmap, target: Size): Pair<Bitmap, LetterboxMap> {
        val inW = input.width
        val inH = input.height
        val tW = target.width
        val tH = target.height
        val scale = minOf(tW.toFloat() / inW.toFloat(), tH.toFloat() / inH.toFloat())
        val scaledW = (inW * scale).toInt()
        val scaledH = (inH * scale).toInt()
        val padX = ((tW - scaledW) / 2)
        val padY = ((tH - scaledH) / 2)

        val out = Bitmap.createBitmap(tW, tH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val dst = Rect(padX, padY, padX + scaledW, padY + scaledH)
        canvas.drawBitmap(input, null, dst, Paint())

        val map = LetterboxMap(
            inSize = Size(inW, inH),
            modelSize = Size(tW, tH),
            scale = scale,
            padX = padX,
            padY = padY
        )

        if (Log.isLoggable("SC_DIMTRACE", Log.DEBUG)) {
            Log.d(
                "SC_DIMTRACE",
                "letterbox in=${inW}x${inH} -> target=${tW}x${tH} scale=${"%.5f".format(scale)} pad=(${padX},${padY})"
            )
        }
        return Pair(out, map)
    }

    /**
     * Center-crop the input bitmap to match the target aspect ratio, then resize to target size.
     * Useful to force a canonical 640x480 frame for Inspector to match real-time pipeline.
     */
    fun centerCropAndResize(input: Bitmap, target: Size): Bitmap {
        val inW = input.width
        val inH = input.height
        if (inW <= 0 || inH <= 0) return input

        val targetAspect = target.width.toFloat() / target.height.toFloat()
        val inAspect = inW.toFloat() / inH.toFloat()

        val cropX: Int
        val cropY: Int
        val cropW: Int
        val cropH: Int

        if (inAspect > targetAspect) {
            // Too wide: crop width
            cropH = inH
            cropW = (inH * targetAspect).toInt().coerceAtMost(inW)
            cropX = ((inW - cropW) / 2).coerceAtLeast(0)
            cropY = 0
        } else {
            // Too tall: crop height
            cropW = inW
            cropH = (inW / targetAspect).toInt().coerceAtMost(inH)
            cropX = 0
            cropY = ((inH - cropH) / 2).coerceAtLeast(0)
        }

        val cropped = try {
            Bitmap.createBitmap(input, cropX, cropY, cropW, cropH)
        } catch (t: Throwable) {
            // Fallback: if cropping fails, resize directly with possible aspect distortion (last resort)
            return Bitmap.createScaledBitmap(input, target.width, target.height, true)
        }

        val scaled = Bitmap.createScaledBitmap(cropped, target.width, target.height, true)
        if (cropped !== input && !cropped.isRecycled) {
            try { cropped.recycle() } catch (_: Throwable) {}
        }
        // Ensure ARGB_8888 for downstream
        return if (scaled.config != Bitmap.Config.ARGB_8888) {
            scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (!scaled.isRecycled) scaled.recycle() }
        } else scaled
    }

    /**
     * Letterbox into an existing bitmap (must match target size). Returns mapping params.
     * Avoids per-frame allocations; caller owns 'out'.
     */
    fun letterboxInto(input: Bitmap, target: Size, out: Bitmap): LetterboxMap {
        require(out.width == target.width && out.height == target.height) {
            "Output bitmap size must equal target size"
        }
        val inW = input.width
        val inH = input.height
        val tW = target.width
        val tH = target.height
        val scale = minOf(tW.toFloat() / inW.toFloat(), tH.toFloat() / inH.toFloat())
        val scaledW = (inW * scale).toInt()
        val scaledH = (inH * scale).toInt()
        val padX = ((tW - scaledW) / 2)
        val padY = ((tH - scaledH) / 2)

        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val dst = Rect(padX, padY, padX + scaledW, padY + scaledH)
        canvas.drawBitmap(input, null, dst, Paint())

        val map = LetterboxMap(
            inSize = Size(inW, inH),
            modelSize = Size(tW, tH),
            scale = scale,
            padX = padX,
            padY = padY
        )
        if (Log.isLoggable("SC_DIMTRACE", Log.DEBUG)) {
            Log.d(
                "SC_DIMTRACE",
                "letterboxInto in=${inW}x${inH} -> target=${tW}x${tH} scale=${"%.5f".format(scale)} pad=(${padX},${padY})"
            )
        }
        return map
    }
}
