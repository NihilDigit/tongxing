package com.nihildigit.tongxing

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens
import com.nihildigit.tongxing.ui.theme.TongXingTheme

class SafetyDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TongXingTheme {
                SafetyDemoScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun SafetyDemoScreen(onBack: () -> Unit) {
    val safetyLevel by AppState.safetyLevel.collectAsState()
    val safetyMessage by AppState.safetyMessage.collectAsState()
    val overlayRunning by AppState.overlayRunning.collectAsState()
    val cameraHealth by AppState.cameraHealth.collectAsState()
    val safetyCoreHealth by AppState.safetyCoreHealth.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TongXingDimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceL)
        ) {
            RowHeader(onBack = onBack)

            StatusHero(
                overlayRunning = overlayRunning,
                level = safetyLevel,
                message = safetyMessage
            )

            HealthCard(
                title = "核心组件状态",
                items = listOf(
                    "相机" to cameraHealth.name,
                    "SafetyCore" to safetyCoreHealth.name
                )
            )
        }
    }
}

@Composable
private fun RowHeader(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = "安全状态演示",
            modifier = Modifier.align(Alignment.Center),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusHero(
    overlayRunning: Boolean,
    level: AppState.SafetyLevel,
    message: String
) {
    val (accent, icon, label) = when (level) {
        AppState.SafetyLevel.SAFE -> Triple(TongXingColors.Safe, Icons.Filled.CheckCircle, "安全")
        AppState.SafetyLevel.LOW -> Triple(TongXingColors.Safe, Icons.Filled.Info, "低风险")
        AppState.SafetyLevel.MEDIUM -> Triple(TongXingColors.Caution, Icons.Filled.Warning, "中等风险")
        AppState.SafetyLevel.HIGH -> Triple(TongXingColors.Warning, Icons.Filled.Warning, "高风险")
        AppState.SafetyLevel.CRITICAL -> Triple(TongXingColors.Critical, Icons.Filled.Error, "危急")
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(TongXingDimens.RadiusL),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TongXingDimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)
            ) {
                Icon(icon, contentDescription = null, tint = accent)
                Text(text = label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = if (overlayRunning) "OverlayService 运行中" else "OverlayService 未启动",
                color = if (overlayRunning) TongXingColors.Safe else TongXingColors.Critical
            )

            if (message.isNotBlank()) {
                Text(text = message, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text(text = "暂无安全提示", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HealthCard(title: String, items: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TongXingDimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceS)
        ) {
            Text(text = title, fontWeight = FontWeight.Bold)
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = value)
                }
            }
        }
    }
}
