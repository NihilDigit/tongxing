package com.nihildigit.safetycore.util

import com.nihildigit.safetycore.core.LetterboxMap
import com.nihildigit.safetycore.core.Size

object GeometryUtils {
    /**
     * Map detection boxes from model coords to frame coords.
     * Input: [x_min, y_min, x_max, y_max] in model input space.
     * Output: [x, y, w, h] in frame space.
     */
    fun toFrameBoxes(modelBoxes: FloatArray, map: LetterboxMap): FloatArray {
        val out = FloatArray(modelBoxes.size)
        var i = 0
        while (i < modelBoxes.size) {
            val x_min = modelBoxes[i]
            val y_min = modelBoxes[i + 1]
            val x_max = modelBoxes[i + 2]
            val y_max = modelBoxes[i + 3]

            // Remove padding then scale back to frame
            val fx0 = (x_min - map.padX) / map.scale
            val fy0 = (y_min - map.padY) / map.scale
            val fx1 = (x_max - map.padX) / map.scale
            val fy1 = (y_max - map.padY) / map.scale

            // Convert to [x, y, w, h]
            out[i] = fx0
            out[i + 1] = fy0
            out[i + 2] = fx1 - fx0
            out[i + 3] = fy1 - fy0
            i += 4
        }
        return out
    }

    /**
     * Clamp a box [x,y,w,h] to frame bounds. Ensures non-negative w/h.
     */
    fun clampBoxXYWH(box: FloatArray, frameW: Int, frameH: Int): FloatArray {
        var x = box[0]
        var y = box[1]
        var w = box[2]
        var h = box[3]

        if (w < 0f) w = 0f
        if (h < 0f) h = 0f

        // Clamp top-left
        if (x < 0f) {
            w += x // reduce width by overflow
            x = 0f
        }
        if (y < 0f) {
            h += y // reduce height by overflow
            y = 0f
        }
        // Clamp bottom-right
        if (x + w > frameW) {
            w = (frameW - x).coerceAtLeast(0f)
        }
        if (y + h > frameH) {
            h = (frameH - y).coerceAtLeast(0f)
        }
        return floatArrayOf(x, y, w, h)
    }

    /**
     * IoU for boxes in [x, y, w, h] format.
     */
    fun iou(a: FloatArray, b: FloatArray): Float {
        val ax0 = a[0]; val ay0 = a[1]; val aw = a[2]; val ah = a[3]
        val bx0 = b[0]; val by0 = b[1]; val bw = b[2]; val bh = b[3]
        val ax1 = ax0 + aw; val ay1 = ay0 + ah
        val bx1 = bx0 + bw; val by1 = by0 + bh
        val x0 = maxOf(ax0, bx0); val y0 = maxOf(ay0, by0)
        val x1 = minOf(ax1, bx1); val y1 = minOf(ay1, by1)
        val iw = (x1 - x0).coerceAtLeast(0f); val ih = (y1 - y0).coerceAtLeast(0f)
        val inter = iw * ih
        val union = aw * ah + bw * bh - inter
        if (union <= 0f) return 0f
        return inter / union
    }

    /**
     * Robust depth sampling within bbox: sample a grid in the bottom band of the box
     * (closer to ground contact) and return a trimmed median to reduce noise.
     * Returns null if no valid samples.
     */
    fun sampleDepthAtBox(
        depth: FloatArray,
        depthWidth: Int,
        depthHeight: Int,
        bbox: FloatArray
    ): Float? {
        val x = bbox[0]
        val y = bbox[1]
        val w = bbox[2].coerceAtLeast(1f)
        val h = bbox[3].coerceAtLeast(1f)

        // Bottom band: lower 30% of the box
        val bandTop = (y + h * 0.7f).coerceAtMost(y + h - 1f)
        val bandBottom = y + h

        val cols = 5
        val rows = 2
        val samples = ArrayList<Float>(cols * rows)

        for (ri in 0 until rows) {
            val fy = bandTop + (ri + 0.5f) * (bandBottom - bandTop) / rows
            for (ci in 0 until cols) {
                val fx = x + (ci + 0.5f) * w / cols
                val xi = fx.toInt().coerceIn(0, depthWidth - 1)
                val yi = fy.toInt().coerceIn(0, depthHeight - 1)
                val v = depth[yi * depthWidth + xi]
                if (v.isFinite() && !v.isNaN()) samples.add(v)
            }
        }

        if (samples.isEmpty()) return null
        samples.sort()
        // Trim 20% highest to reduce background influence; take median of remaining
        val keep = (samples.size * 0.8f).toInt().coerceAtLeast(1)
        val trimmed = samples.subList(0, keep)
        return trimmed[trimmed.size / 2]
    }

    /**
     * Upsample segmentation class map from model output size to frame size using nearest neighbor.
     * Input: ByteArray[modelH * modelW]
     * Output: ByteArray[frameH * frameW]
     */
    fun upsampleSegmentation(
        classes: ByteArray,
        modelWidth: Int,
        modelHeight: Int,
        frameSize: Size,
        map: LetterboxMap
    ): ByteArray {
        val out = ByteArray(frameSize.width * frameSize.height)

        for (fy in 0 until frameSize.height) {
            for (fx in 0 until frameSize.width) {
                // Map frame coord to model coord
                val mx = (fx * map.scale + map.padX).toInt().coerceIn(0, modelWidth - 1)
                val my = (fy * map.scale + map.padY).toInt().coerceIn(0, modelHeight - 1)

                val mIdx = my * modelWidth + mx
                val fIdx = fy * frameSize.width + fx
                out[fIdx] = classes[mIdx]
            }
        }

        return out
    }

    /**
     * Upsample segmentation into a preallocated buffer (len=frameW*frameH).
     * Avoids per-frame allocations when called repeatedly.
     */
    fun upsampleSegmentationInto(
        classes: ByteArray,
        modelWidth: Int,
        modelHeight: Int,
        frameSize: Size,
        map: LetterboxMap,
        out: ByteArray
    ) {
        require(out.size == frameSize.width * frameSize.height) {
            "Output buffer size must be frameWidth*frameHeight"
        }
        var fIdx = 0
        for (fy in 0 until frameSize.height) {
            val my = (fy * map.scale + map.padY).toInt().coerceIn(0, modelHeight - 1)
            val rowBase = my * modelWidth
            for (fx in 0 until frameSize.width) {
                val mx = (fx * map.scale + map.padX).toInt().coerceIn(0, modelWidth - 1)
                out[fIdx++] = classes[rowBase + mx]
            }
        }
    }

    /**
     * Upsample depth map from model output size to frame size using bilinear interpolation.
     * Input: FloatArray[modelH * modelW]
     * Output: FloatArray[frameH * frameW]
     */
    fun upsampleDepth(
        depth: FloatArray,
        modelWidth: Int,
        modelHeight: Int,
        frameSize: Size,
        map: LetterboxMap
    ): FloatArray {
        val out = FloatArray(frameSize.width * frameSize.height)

        for (fy in 0 until frameSize.height) {
            for (fx in 0 until frameSize.width) {
                // Map frame coord to model coord (continuous)
                val mx = fx * map.scale + map.padX
                val my = fy * map.scale + map.padY

                // Bilinear interpolation
                val x0 = mx.toInt().coerceIn(0, modelWidth - 1)
                val y0 = my.toInt().coerceIn(0, modelHeight - 1)
                val x1 = (x0 + 1).coerceIn(0, modelWidth - 1)
                val y1 = (y0 + 1).coerceIn(0, modelHeight - 1)

                val dx = mx - x0
                val dy = my - y0

                val v00 = depth[y0 * modelWidth + x0]
                val v10 = depth[y0 * modelWidth + x1]
                val v01 = depth[y1 * modelWidth + x0]
                val v11 = depth[y1 * modelWidth + x1]

                // If any sample is NaN, result is NaN
                val result = if (v00.isNaN() || v10.isNaN() || v01.isNaN() || v11.isNaN()) {
                    Float.NaN
                } else {
                    v00 * (1 - dx) * (1 - dy) +
                    v10 * dx * (1 - dy) +
                    v01 * (1 - dx) * dy +
                    v11 * dx * dy
                }

                out[fy * frameSize.width + fx] = result
            }
        }

        return out
    }
}
