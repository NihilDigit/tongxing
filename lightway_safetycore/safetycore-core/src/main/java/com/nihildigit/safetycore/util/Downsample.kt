package com.nihildigit.safetycore.util

import com.nihildigit.safetycore.util.NativeImageOps

/**
 * Utilities for downsampling depth and segmentation maps to a lower work resolution
 * for efficient morphology and CCL in Stage 2.
 */
object Downsample {

     /**
      * Downsamples a generic float field by factor 2 using max pooling (2×2 blocks).
      * Intended for rank-style maps where "larger = more salient" (e.g., near = higher rank).
      * Returns a FloatArray of size (w/2) × (h/2).
      * Invalid values (NaN, Inf) are preserved as Float.NEGATIVE_INFINITY.
      */
     fun downsample2xMax(src: FloatArray, w: Int, h: Int): FloatArray {
         val outW = w / 2
         val outH = h / 2
         val out = FloatArray(outW * outH)
         downsample2xMaxInto(src, w, h, out)
         return out
     }
 
     /**
      * In-place variant to avoid per-frame allocations.
      * @param dst preallocated buffer of size (w/2)*(h/2)
      */
     fun downsample2xMaxInto(src: FloatArray, w: Int, h: Int, dst: FloatArray) {
         val outW = w / 2
         val outH = h / 2
         require(dst.size == outW * outH) { "dst buffer size must be ${outW * outH}" }

         if (NativeImageOps.downsample2xMaxIntoSafe(src, w, h, dst)) {
             return
         }
 
         var outIdx = 0
         for (y in 0 until outH) {
             val srcY = y * 2
             for (x in 0 until outW) {
                 val srcX = x * 2
 
                 var maxValue = Float.NEGATIVE_INFINITY
                 for (dy in 0..1) {
                     val sy = (srcY + dy).coerceIn(0, h - 1)
                     val rowBase = sy * w
                     for (dx in 0..1) {
                         val sx = (srcX + dx).coerceIn(0, w - 1)
                         val v = src[rowBase + sx]
                         if (v.isFinite() && v > maxValue) {
                             maxValue = v
                         }
                     }
                 }
                 dst[outIdx++] = maxValue
             }
         }
     }

    /**
     * Upscales a bounding box [x, y, w, h] from downsampled coordinates back to original frame.
     * Scale factor is applied to all components (no translation offset).
     */
    fun upscaleBbox(bbox: FloatArray, scale: Int): FloatArray {
        return floatArrayOf(
            bbox[0] * scale,
            bbox[1] * scale,
            bbox[2] * scale,
            bbox[3] * scale
        )
    }
}
