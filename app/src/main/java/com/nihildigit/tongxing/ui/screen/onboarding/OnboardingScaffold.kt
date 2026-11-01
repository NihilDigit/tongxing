package com.nihildigit.tongxing.ui.screen.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun OnboardingScaffold(
    step: OnboardingStep,
    canGoBack: Boolean,
    canGoNext: Boolean,
    nextLabel: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OnboardingProgressIndicator(stepIndex = step.index)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = TongXingDimens.SpaceL)
            ) {
                content()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = TongXingDimens.SpaceL,
                        vertical = TongXingDimens.SpaceL
                    ),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (canGoBack) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) { Text("上一步") }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                Button(
                    onClick = onNext,
                    enabled = canGoNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (step == OnboardingStep.Welcome) 80.dp else 56.dp)
                ) { Text(nextLabel) }
            }
        }
    }
}
