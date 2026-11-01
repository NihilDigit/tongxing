package com.nihildigit.tongxing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nihildigit.tongxing.ui.components.LargeTuningSlider
import com.nihildigit.tongxing.ui.components.SettingSwitchRow
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun SettingsScreenPreview(
    onBack: () -> Unit,
    speechRate: Float = 1.2f,
    pitch: Float = 1.0f,
    vibrationEnabled: Boolean = true,
    safetyCoreEnabled: Boolean = true,
    onSpeechRateChange: (Float) -> Unit = {},
    onPitchChange: (Float) -> Unit = {},
    onVibrationEnabledChange: (Boolean) -> Unit = {},
    onSafetyCoreEnabledChange: (Boolean) -> Unit = {},
    onOpenLlmSettings: () -> Unit = {},
    onOpenServiceConnections: () -> Unit = {}
) {
    var speechRateState by remember { mutableStateOf(speechRate) }
    var pitchState by remember { mutableStateOf(pitch) }
    var vibrationEnabledState by remember { mutableStateOf(vibrationEnabled) }
    var safetyCoreEnabledState by remember { mutableStateOf(safetyCoreEnabled) }

    LaunchedEffect(speechRate, pitch, vibrationEnabled, safetyCoreEnabled) {
        speechRateState = speechRate
        pitchState = pitch
        vibrationEnabledState = vibrationEnabled
        safetyCoreEnabledState = safetyCoreEnabled
    }

    Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(TongXingDimens.SpaceM)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.voice_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TongXingColors.Primary)
            LargeTuningSlider(stringResource(R.string.speech_rate), speechRateState, {
                speechRateState = it
                onSpeechRateChange(it)
            }, 0.5f..2.0f)
            LargeTuningSlider(stringResource(R.string.speech_pitch), pitchState, {
                pitchState = it
                onPitchChange(it)
            }, 0.5f..2.0f)
            Text(stringResource(R.string.interaction_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TongXingColors.Secondary)
            SettingSwitchRow(stringResource(R.string.vibration_alerts), stringResource(R.string.vibration_help), Icons.Filled.Vibration, vibrationEnabledState) {
                vibrationEnabledState = it
                onVibrationEnabledChange(it)
            }
            SettingSwitchRow(stringResource(R.string.safety_engine), stringResource(R.string.safety_help), Icons.Filled.Shield, safetyCoreEnabledState) {
                safetyCoreEnabledState = it
                onSafetyCoreEnabledChange(it)
            }
            Text(stringResource(R.string.model_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TongXingColors.Primary)
            Text(
                stringResource(R.string.llm_settings),
                style = MaterialTheme.typography.bodyMedium,
                color = TongXingColors.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLlmSettings() }
                    .padding(vertical = 8.dp)
            )
            Text(
                stringResource(R.string.select_model_help),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.service_connections),
                color = TongXingColors.Primary,
                modifier = Modifier.fillMaxWidth().clickable { onOpenServiceConnections() }
                    .padding(vertical = 8.dp)
            )
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${stringResource(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}
