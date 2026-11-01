package com.nihildigit.tongxing.ui.screen.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun OnboardingProgressIndicator(
    stepIndex: Int,
    modifier: Modifier = Modifier
) {
    val stepNumber = (stepIndex + 1).coerceIn(1, OnboardingStep.TotalSteps)
    val total = OnboardingStep.TotalSteps

    Row(
        modifier = modifier
            .padding(horizontal = TongXingDimens.SpaceL, vertical = TongXingDimens.SpaceS)
            .semantics { contentDescription = "进度：第 $stepNumber 步，共 $total 步" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "第 $stepNumber 步，共 $total 步",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(total) { index ->
                val dotColor: Color = when {
                    index < stepIndex -> TongXingColors.Secondary
                    index == stepIndex -> TongXingColors.Primary
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                )
            }
        }
    }
}

