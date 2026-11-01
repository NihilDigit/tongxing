package com.nihildigit.tongxing.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object TongXingMotion {
    object DurationMs {
        const val Short = 150
        const val Medium = 300
        const val Long = 500
        const val Emphasis = 500
    }

    object Curves {
        val Standard: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
        val Emphasized: Easing = FastOutSlowInEasing
        val Decelerate: Easing = LinearOutSlowInEasing
    }

    val springMediumBouncy = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun tweenShort(easing: Easing = Curves.Standard) = tween<Float>(
        durationMillis = DurationMs.Short,
        easing = easing
    )

    fun tweenMedium(easing: Easing = Curves.Standard) = tween<Float>(
        durationMillis = DurationMs.Medium,
        easing = easing
    )

    fun colorTweenMedium(easing: Easing = Curves.Standard) = tween<androidx.compose.ui.graphics.Color>(
        durationMillis = DurationMs.Medium,
        easing = easing
    )
}
