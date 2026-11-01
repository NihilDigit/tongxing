package com.nihildigit.qnninferencesdk.sdk

object DetectionNms {
    /**
     * Greedy NMS with score filtering.
     * - boxes: [N*4] in (x0,y0,x1,y1) pixel coords of model input size
     * - scores: [N]
     * - classIdx: [N]
     * Returns indices kept, sorted by descending score, truncated by topK.
     */
    @JvmStatic
    fun selectIndices(
        boxes: FloatArray,
        scores: FloatArray,
        classIdx: IntArray,
        scoreThresh: Float = 0.25f,
        iouThresh: Float = 0.45f,
        topK: Int = 100,
        classAgnostic: Boolean = false
    ): IntArray {
        val n = scores.size
        val idxs = ArrayList<Int>(n)
        for (i in 0 until n) if (scores[i] >= scoreThresh) idxs.add(i)
        // sort by score desc
        idxs.sortWith { a, b -> java.lang.Float.compare(scores[b], scores[a]) }

        val keep = ArrayList<Int>(minOf(topK, idxs.size))
        val suppressed = BooleanArray(n)

        for (i in idxs.indices) {
            val a = idxs[i]
            if (suppressed[a]) continue
            keep.add(a)
            if (keep.size >= topK) break
            val ax0 = boxes[a * 4]
            val ay0 = boxes[a * 4 + 1]
            val ax1 = boxes[a * 4 + 2]
            val ay1 = boxes[a * 4 + 3]
            val aArea = (ax1 - ax0).coerceAtLeast(0f) * (ay1 - ay0).coerceAtLeast(0f)
            for (j in i + 1 until idxs.size) {
                val b = idxs[j]
                if (suppressed[b]) continue
                if (!classAgnostic && classIdx[a] != classIdx[b]) continue
                val bx0 = boxes[b * 4]
                val by0 = boxes[b * 4 + 1]
                val bx1 = boxes[b * 4 + 2]
                val by1 = boxes[b * 4 + 3]
                val bArea = (bx1 - bx0).coerceAtLeast(0f) * (by1 - by0).coerceAtLeast(0f)
                val xx0 = maxOf(ax0, bx0)
                val yy0 = maxOf(ay0, by0)
                val xx1 = minOf(ax1, bx1)
                val yy1 = minOf(ay1, by1)
                val w = (xx1 - xx0).coerceAtLeast(0f)
                val h = (yy1 - yy0).coerceAtLeast(0f)
                val inter = w * h
                val union = aArea + bArea - inter
                val iou = if (union > 0f) inter / union else 0f
                if (iou > iouThresh) suppressed[b] = true
            }
        }
        return keep.toIntArray()
    }
}

