package com.nihildigit.tongxing.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingMotion
import kotlin.math.sin

enum class OrbState {
    IDLE, LISTENING, THINKING, SPEAKING, TOOL_CALLING, THREAT_ALERT
}

@Composable
fun AssistantOrb(
    state: OrbState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "nono_final")

    val floatY by infiniteTransition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse)
    )

    val shakeOffset by infiniteTransition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(50, easing = LinearEasing), RepeatMode.Reverse)
    )

    val strobeAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(200, easing = LinearEasing), RepeatMode.Reverse)
    )

    val color by animateColorAsState(
        targetValue = when (state) {
            OrbState.IDLE -> TongXingColors.Digital.Primary
            OrbState.LISTENING -> TongXingColors.Critical
            OrbState.THINKING -> TongXingColors.Caution
            OrbState.SPEAKING -> TongXingColors.Safe
            OrbState.TOOL_CALLING -> TongXingColors.Tertiary
            OrbState.THREAT_ALERT -> TongXingColors.Critical
        },
        animationSpec = TongXingMotion.colorTweenMedium()
    )

    Box(
        modifier = modifier
            .size(64.dp) // 最终实战尺寸：64dp
            .graphicsLayer { 
                translationY = floatY
                if (state == OrbState.THREAT_ALERT) translationX = shakeOffset
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. 陶瓷外壳
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(TongXingColors.Surface, TongXingColors.SurfaceVariant),
                        center = Offset(0.3f, 0.3f)
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
        )

        // 2. 电子面罩
        Box(
            modifier = Modifier
                .size(48.dp) // 随比例缩小
                .clip(CircleShape)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (state == OrbState.THREAT_ALERT) strobeAlpha else 1f }
                    .blur(12.dp)
                    .background(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent)))
            )

            FunctionalFaceFinal(state = state, color = color)
            ScanlineOverlay(color.copy(alpha = 0.05f))
        }
    }
}

@Composable
fun FunctionalFaceFinal(state: OrbState, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "face_final")
    
    val blink by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 3500; 1f at 3000; 0.1f at 3060; 1f at 3150 }
        )
    )

    val rotateCW by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)))
    val rotateCCW by infiniteTransition.animateFloat(360f, 0f, infiniteRepeatable(tween(2500, easing = LinearEasing)))
    val spec1 by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse))
    val spec2 by infiniteTransition.animateFloat(0.5f, 0.8f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    val spec3 by infiniteTransition.animateFloat(0.2f, 1.2f, infiniteRepeatable(tween(350), RepeatMode.Reverse))
    val wavePhase by infiniteTransition.animateFloat(0f, 2f * Math.PI.toFloat(), infiniteRepeatable(tween(1200, easing = LinearEasing)))

    Canvas(modifier = Modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        val faceColor = color.copy(alpha = 0.95f)

        when (state) {
            OrbState.IDLE -> {
                drawPillEyeSmall(w * 0.3f, h * 0.5f, blink, faceColor)
                drawPillEyeSmall(w * 0.7f, h * 0.5f, blink, faceColor)
            }
            OrbState.LISTENING -> {
                for (i in 0..2) {
                    val path = Path()
                    val amp = (6.dp.toPx()) * (1f - i * 0.25f)
                    val offset = i * 1.5f
                    for (x in 0..w.toInt()) {
                        val nX = x / w
                        val y = h/2 + sin(nX * 2.5 * Math.PI + wavePhase + offset).toFloat() * amp * sin(nX * Math.PI).toFloat()
                        if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
                    }
                    drawPath(path, faceColor.copy(alpha = 0.8f - i * 0.3f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            OrbState.SPEAKING -> {
                val barW = 2.5.dp.toPx()
                val baseH = 18.dp.toPx()
                listOf(spec1, spec2, spec3).forEachIndexed { i, value ->
                    val barH = baseH * value
                    val xL = w * 0.42f - i * 4.dp.toPx()
                    val xR = w * 0.58f + i * 4.dp.toPx()
                    drawRoundRect(faceColor, Offset(xL - barW/2, h/2 - barH/2), Size(barW, barH), CornerRadius(barW/2))
                    drawRoundRect(faceColor, Offset(xR - barW/2, h/2 - barH/2), Size(barW, barH), CornerRadius(barW/2))
                }
            }
            OrbState.TOOL_CALLING -> {
                rotate(rotateCW) {
                    drawArc(faceColor, 0f, 60f, false, Offset(w*0.25f, h*0.25f), Size(w*0.5f, h*0.5f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(faceColor, 180f, 60f, false, Offset(w*0.25f, h*0.25f), Size(w*0.5f, h*0.5f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                rotate(rotateCCW) {
                    drawArc(faceColor.copy(alpha = 0.5f), 90f, 40f, false, Offset(w*0.1f, h*0.1f), Size(w*0.8f, h*0.8f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(faceColor.copy(alpha = 0.5f), 270f, 40f, false, Offset(w*0.1f, h*0.1f), Size(w*0.8f, h*0.8f), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
                }
                drawCircle(faceColor, radius = 2.5.dp.toPx() * (0.8f + 0.2f * sin(rotateCW * Math.PI.toFloat() / 180f)), center = Offset(w/2, h/2))
            }
            OrbState.THINKING -> {
                // 简约思考视觉：单层圆弧旋转，轻盈且不显厚重
                rotate(rotateCW) {
                    drawArc(
                        color = faceColor,
                        startAngle = 0f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(w * 0.25f, h * 0.25f),
                        size = Size(w * 0.5f, h * 0.5f),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            OrbState.THREAT_ALERT -> {
                val xS = 8.dp.toPx() 
                drawLine(faceColor, Offset(w/2-xS, h/2-xS), Offset(w/2+xS, h/2+xS), 4.dp.toPx(), StrokeCap.Round)
                drawLine(faceColor, Offset(w/2+xS, h/2-xS), Offset(w/2-xS, h/2+xS), 4.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPillEyeSmall(x: Float, y: Float, scaleY: Float, color: Color) {
    val eyeW = 5.dp.toPx(); val eyeH = 14.dp.toPx(); val curH = eyeH * scaleY
    drawRoundRect(color, Offset(x - eyeW / 2, y - curH / 2), Size(eyeW, curH), CornerRadius(eyeW / 2))
}

@Composable
fun ScanlineOverlay(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val count = 10; val gap = size.height / count
        for (i in 0 until count) drawLine(color, Offset(0f, i * gap), Offset(size.width, i * gap), 0.5.dp.toPx())
    }
}