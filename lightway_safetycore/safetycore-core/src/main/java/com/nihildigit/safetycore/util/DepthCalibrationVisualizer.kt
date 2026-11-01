package com.nihildigit.safetycore.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import com.nihildigit.safetycore.core.CameraIntrinsics
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Visualizer for depth calibration debugging.
 *
 * Draws ground samples, rays, and geometric distance estimates on a frame.
 * Useful for validating camera intrinsics and depth normalization.
 */
object DepthCalibrationVisualizer {

    data class GroundSample(
        val x: Int,
        val y: Int,
        val relDepth: Float,
        val geoDistance: Float
    )

    fun drawCalibrationOverlay(
        bitmap: Bitmap,
        samples: List<GroundSample>,
        intrinsics: CameraIntrinsics,
        pitchRad: Float,
        cameraHeightM: Float,
        drawRays: Boolean = true
    ): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val samplePaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 3f
            style = Paint.Style.FILL
        }

        val rayPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 2f
            alpha = 150
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            alpha = 200
        }

        for (sample in samples) {
            canvas.drawCircle(sample.x.toFloat(), sample.y.toFloat(), 4f, samplePaint)

            if (drawRays) {
                val groundPixel = computeGroundPixelProjection(
                    sampleX = sample.x,
                    sampleY = sample.y,
                    geoDistance = sample.geoDistance,
                    intrinsics = intrinsics,
                    pitchRad = pitchRad,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height
                )

                if (groundPixel != null) {
                    canvas.drawLine(
                        sample.x.toFloat(),
                        sample.y.toFloat(),
                        groundPixel.first.toFloat(),
                        groundPixel.second.toFloat(),
                        rayPaint
                    )
                    canvas.drawCircle(
                        groundPixel.first.toFloat(),
                        groundPixel.second.toFloat(),
                        3f,
                        rayPaint
                    )
                }
            }
        }

        if (samples.isNotEmpty()) {
            val avgGeo = samples.map { it.geoDistance }.average().toFloat()
            val avgRel = samples.map { it.relDepth }.average().toFloat()
            val info = "Samples: ${samples.size} | AvgGeo: ${"%.2f".format(avgGeo)}m | AvgRel: ${"%.4f".format(avgRel)}"
            canvas.drawText(info, 10f, 30f, textPaint)
        }

        return mutableBitmap
    }

    private fun computeGroundPixelProjection(
        sampleX: Int,
        sampleY: Int,
        geoDistance: Float,
        intrinsics: CameraIntrinsics,
        pitchRad: Float,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<Int, Int>? {
        val u = sampleX.toFloat()
        val v = sampleY.toFloat()

        val kInv = intrinsics.inverseMatrix()
        val xCam = kInv[0] * u + kInv[1] * v + kInv[2]
        val yCam = kInv[3] * u + kInv[4] * v + kInv[5]
        val zCam = kInv[6] * u + kInv[7] * v + kInv[8]

        val norm = sqrt(xCam * xCam + yCam * yCam + zCam * zCam)
        val rxCam = xCam / norm
        val ryCam = yCam / norm
        val rzCam = zCam / norm

        val xWorld = rzCam
        val yWorld = rxCam
        val zWorld = -ryCam

        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)
        val rxWorld = cosPitch * xWorld + sinPitch * zWorld
        val ryWorld = yWorld
        val rzWorld = -sinPitch * xWorld + cosPitch * zWorld

        val scale = geoDistance / geoDistance.coerceAtLeast(0.1f) * 0.5f
        val endX = (sampleX + rxWorld * scale * frameWidth * 0.5f).toInt()
        val endY = (sampleY + ryWorld * scale * frameHeight * 0.5f).toInt()

        val clampedX = endX.coerceIn(0, frameWidth - 1)
        val clampedY = endY.coerceIn(0, frameHeight - 1)

        return Pair(clampedX, clampedY)
    }

    fun drawDepthGrid(
        bitmap: Bitmap,
        depthMap: FloatArray,
        gridSize: Int = 8
    ): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val width = bitmap.width
        val height = bitmap.height

        val textPaint = Paint().apply {
            color = Color.CYAN
            textSize = 18f
            alpha = 220
        }

        val cellWidth = width / gridSize
        val cellHeight = height / gridSize

        for (i in 0 until gridSize) {
            for (j in 0 until gridSize) {
                val centerX = (i + 0.5f) * cellWidth
                val centerY = (j + 0.5f) * cellHeight

                val pixelX = centerX.toInt().coerceIn(0, width - 1)
                val pixelY = centerY.toInt().coerceIn(0, height - 1)
                val depth = depthMap[pixelY * width + pixelX]

                if (depth.isFinite() && depth > 0f) {
                    val text = "${"%.1f".format(depth)}m"
                    canvas.drawText(text, centerX - 20f, centerY, textPaint)
                }
            }
        }

        return mutableBitmap
    }
}
