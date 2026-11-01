package com.nihildigit.tongxing.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nihildigit.tongxing.ui.theme.TongXingMotion

@Composable
fun <T> AnimatedPage(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val spec = tween<Float>(durationMillis = TongXingMotion.DurationMs.Long, easing = TongXingMotion.Curves.Decelerate)
            (fadeIn(animationSpec = spec)) togetherWith (fadeOut(animationSpec = spec))
        },
        label = "animated_page"
    ) { state ->
        content(state)
    }
}
