package com.nihildigit.tongxing.ui.screen

import androidx.compose.runtime.Composable
import com.nihildigit.tongxing.ui.screen.onboarding.GuidedOnboardingScreen

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    GuidedOnboardingScreen(onComplete = onComplete)
}

