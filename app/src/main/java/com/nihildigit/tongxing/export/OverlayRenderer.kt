package com.nihildigit.tongxing.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.nihildigit.safetycore.v2.types.DangerLevelV2
import com.nihildigit.safetycore.v2.types.FusionResultV2

/**
 * Renders detection overlay (boxes + labels) onto a camera frame bitmap.
 * Port of the Compose Canvas drawing in SafetyDemoPreviewV2 to Android Canvas.
 */
object OverlayRenderer {

    private data class LabelEntry(
        val boxLeft: Float, val boxTop: Float,
        val boxRight: Float, val boxBottom: Float,
        val color: Int, val lines: List<String>,
        val bubbleWidth: Float, val bubbleHeight: Float
    )

    fun render(frame: Bitmap, result: FusionResultV2?): Bitmap {
        val output = frame.copy(Bitmap.Config.ARGB_8888, true)
        if (result == null) return output

        val canvas = Canvas(output)
        val fw = result.trackSpaceWidth.coerceAtLeast(1).toFloat()
        val fh = result.trackSpaceHeight.coerceAtLeast(1).toFloat()
        val cw = output.width.toFloat()
        val ch = output.height.toFloat()

        val threatMap = result.threats
            .filter { it.level >= DangerLevelV2.MEDIUM }
            .associateBy { it.trackId }

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 24f
            isFakeBoldText = true
        }
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0x99000000.toInt()
        }

        val fontMetrics = labelPaint.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val textBaseline = -fontMetrics.top
        val padX = 6f
        val padY = 4f
        val lineSpacing = 2f

        // Phase 1: collect all labels and draw boxes
        val labels = mutableListOf<LabelEntry>()

        for (track in result.tracks) {
            if (!track.confirmed) continue
            val threat = threatMap[track.trackId] ?: continue

            val bbox = track.bbox
            val boxLeft = (bbox[0] / fw) * cw
            val boxTop = (bbox[1] / fh) * ch
            val boxWidth = (bbox[2] / fw) * cw
            val boxHeight = (bbox[3] / fh) * ch
            val boxRight = boxLeft + boxWidth
            val boxBottom = boxTop + boxHeight

            val color = dangerLevelColor(threat.level)

            // Filled background
            fillPaint.color = color
            fillPaint.alpha = 46
            canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, fillPaint)

            // Border
            boxPaint.color = color
            boxPaint.alpha = 204
            canvas.drawRect(boxLeft, boxTop, boxRight, boxBottom, boxPaint)

            // Build label
            val lines = mutableListOf<String>()
            lines.add("#${track.trackId} ${threat.level}")
            lines.add("${String.format("%.1f", threat.distanceMeters)}m")
            if (threat.ttcSeconds.isFinite() && threat.ttcSeconds < 10f) {
                lines.add("TTC ${String.format("%.1f", threat.ttcSeconds)}s")
            }

            val maxTextWidth = lines.maxOf { labelPaint.measureText(it) }
            val totalTextHeight = textHeight * lines.size + lineSpacing * (lines.size - 1)
            val bubbleH = totalTextHeight + padY * 2f
            val bubbleW = maxTextWidth + padX * 2f

            labels.add(LabelEntry(boxLeft, boxTop, boxRight, boxBottom, color, lines, bubbleW, bubbleH))
        }

        // Phase 2: compute label positions with collision avoidance
        // Sort by box center X so labels lay out left-to-right
        labels.sortBy { (it.boxLeft + it.boxRight) / 2f }

        val gap = 4f
        val placedRects = mutableListOf<RectF>()

        for (entry in labels) {
            val textX = entry.boxLeft
            // Preferred: above the box
            var bubbleTop = entry.boxTop - 8f - entry.bubbleHeight
            if (bubbleTop < 6f) bubbleTop = 6f

            var rect = RectF(textX, bubbleTop, textX + entry.bubbleWidth, bubbleTop + entry.bubbleHeight)

            // Resolve overlaps: shift down if colliding with already-placed labels
            var iterations = 0
            while (iterations < 10) {
                val collision = placedRects.any { RectF.intersects(it, rect) }
                if (!collision) break
                // Move below the colliding rect
                val blockingBottom = placedRects
                    .filter { RectF.intersects(it, rect) }
                    .maxOf { it.bottom }
                bubbleTop = blockingBottom + gap
                rect = RectF(textX, bubbleTop, textX + entry.bubbleWidth, bubbleTop + entry.bubbleHeight)
                iterations++
            }

            // Clamp to canvas
            if (rect.bottom > ch - 4f) {
                bubbleTop = ch - 4f - entry.bubbleHeight
                rect = RectF(textX, bubbleTop, textX + entry.bubbleWidth, bubbleTop + entry.bubbleHeight)
            }

            placedRects.add(rect)

            // Draw bubble
            canvas.drawRoundRect(rect, 12f, 12f, bgPaint)

            // Draw text
            labelPaint.color = 0xFFFFFFFF.toInt()
            var lineY = rect.top + padY + textBaseline
            for (line in entry.lines) {
                canvas.drawText(line, rect.left + padX, lineY, labelPaint)
                lineY += textHeight + lineSpacing
            }
        }

        return output
    }

    private fun dangerLevelColor(level: DangerLevelV2): Int = when (level) {
        DangerLevelV2.CRITICAL -> 0xFFFF4D4F.toInt()
        DangerLevelV2.HIGH -> 0xFFFFA726.toInt()
        DangerLevelV2.MEDIUM -> 0xFFFFEB3B.toInt()
        DangerLevelV2.LOW -> 0xFF4CAF50.toInt()
        DangerLevelV2.NONE -> 0xFF4CAF50.toInt()
    }
}
