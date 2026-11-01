package com.nihildigit.tongxing.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.state.AppState
import com.nihildigit.tongxing.ui.components.AssistantOrb
import com.nihildigit.tongxing.ui.components.OrbState
import com.nihildigit.tongxing.ui.theme.TongXingColors
import kotlinx.coroutines.flow.StateFlow

@Composable
fun OverlayView(
    assistantStateFlow: StateFlow<AppState.AssistantState>,
    safetyLevelFlow: StateFlow<AppState.SafetyLevel>,
    assistantResponseFlow: StateFlow<String>,
    asrTranscriptionFlow: StateFlow<String>,
    activeToolCallFlow: StateFlow<String?>,
    previewFrameFlow: StateFlow<AppState.PreviewFrame?>,
    stemReadyFlow: StateFlow<Boolean>
) {
    val assistantState by assistantStateFlow.collectAsState()
    val safetyLevel by safetyLevelFlow.collectAsState()
    val assistantResponse by assistantResponseFlow.collectAsState()
    val asrTranscription by asrTranscriptionFlow.collectAsState()
    val previewFrame by previewFrameFlow.collectAsState()
    val stemReady by stemReadyFlow.collectAsState()
    val activeToolCall by activeToolCallFlow.collectAsState()

    // 工具面板：仅由 activeToolCall 驱动
    var stickyToolName by remember { mutableStateOf<String?>(null) }
    var showToolPanel by remember { mutableStateOf(false) }
    var showMessageBubble by remember { mutableStateOf(false) }

    LaunchedEffect(activeToolCall) {
        if (activeToolCall != null) {
            stickyToolName = activeToolCall
            showToolPanel = true
        } else {
            kotlinx.coroutines.delay(1000)
            showToolPanel = false
            stickyToolName = null
        }
    }

    val cleanedText = remember(assistantResponse) {
        assistantResponse.replace(Regex("<poi_map>.*?</poi_map>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }
    val currentText = if (assistantState == AppState.AssistantState.LISTENING) asrTranscription else cleanedText

    LaunchedEffect(currentText, assistantState) {
        if (currentText.isNotEmpty()) {
            showMessageBubble = true
            if (assistantState == AppState.AssistantState.IDLE) {
                kotlinx.coroutines.delay(2000)
                showMessageBubble = false
            }
        } else {
            showMessageBubble = false
        }
    }

    val orbState = when {
        !stemReady -> OrbState.THINKING
        safetyLevel >= AppState.SafetyLevel.HIGH -> OrbState.THREAT_ALERT
        showToolPanel -> OrbState.TOOL_CALLING
        assistantState == AppState.AssistantState.LISTENING -> OrbState.LISTENING
        assistantState == AppState.AssistantState.THINKING -> OrbState.THINKING
        assistantState == AppState.AssistantState.SPEAKING -> OrbState.SPEAKING
        else -> OrbState.IDLE
    }

    val toolConfig = remember(stickyToolName) {
        when (stickyToolName) {
            "navigate_to" -> ToolVisual("启动高德导航", Icons.Filled.Navigation, TongXingColors.Secondary)
            "find_nearby" -> ToolVisual("搜索周边地点", Icons.Filled.Explore, TongXingColors.Secondary)
            "stop_navigation" -> ToolVisual("结束当前导航", Icons.Filled.StopCircle, Color(0xFFE63946))
            "call_taxi" -> ToolVisual("呼叫滴滴打车", Icons.Filled.LocalTaxi, TongXingColors.Caution)
            "weather_here" -> ToolVisual("查询本地天气", Icons.Filled.CloudQueue, TongXingColors.Primary)
            "search_train" -> ToolVisual("查询火车时刻", Icons.Filled.Train, Color(0xFF4A90E2))
            "search_flight" -> ToolVisual("查询航班动态", Icons.Filled.FlightTakeoff, Color(0xFF50E3C2))
            "bing_search" -> ToolVisual("联网必应搜索", Icons.Filled.Public, TongXingColors.Tertiary)
            "describe_camera" -> ToolVisual("正在观察前方", Icons.Filled.PhotoCamera, Color(0xFF9B51E0))
            "vision", "describe_screen" -> ToolVisual("正在识别屏幕", Icons.Filled.Screenshot, Color(0xFF9B51E0))
            else -> ToolVisual("准备智能工具", Icons.Filled.AutoAwesome, TongXingColors.Primary)
        }
    }
    val cameraPreview = remember(previewFrame?.timestampMs) {
        previewFrame?.bitmap?.asImageBitmap()
    }
    val showCameraPreview = showToolPanel && stickyToolName == "describe_camera" && cameraPreview != null
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxPanelWidth = (screenWidthDp - 48 - 12 - 10 - 92 - 16).dp
    val useCompactLayout = showCameraPreview && maxPanelWidth < 180.dp
    val density = LocalDensity.current
    var toolPanelHeightPx by remember { mutableStateOf<Int?>(null) }
    val toolPanelHeightDp = remember(toolPanelHeightPx, density) {
        toolPanelHeightPx?.let { with(density) { it.toDp() } }
    }

    Column(modifier = Modifier.wrapContentSize()) {
        Row(
            modifier = Modifier.wrapContentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistantOrb(state = orbState)

            Spacer(modifier = Modifier.width(12.dp))

            // 统一容器，减少重绘导致的透明度异常
            Box(contentAlignment = Alignment.CenterStart) {
                // 工具面板
                if (showToolPanel) {
                    ExpressiveToolPanel(
                        config = toolConfig,
                        maxWidth = if (useCompactLayout) 280.dp else maxPanelWidth.coerceAtLeast(180.dp),
                        onMeasured = { toolPanelHeightPx = it.height }
                    )
                } else if (showMessageBubble) {
                    // 消息面板：不再使用 AnimatedVisibility 以免流式更新时动画干扰导致透明
                    DetailedMessageBubble(currentText, assistantState)
                }
            }

            if (showCameraPreview && !useCompactLayout) {
                Spacer(modifier = Modifier.width(5.dp))
                CameraPreviewBubble(preview = cameraPreview!!, height = toolPanelHeightDp ?: 120.dp)
            }
        }

        if (showCameraPreview && useCompactLayout) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.wrapContentSize()) {
                Spacer(modifier = Modifier.width(60.dp))
                CameraPreviewBubble(preview = cameraPreview!!)
            }
        }
    }
}


data class ToolVisual(val label: String, val icon: ImageVector, val color: Color)

@Composable
fun OpaquePanel(
    modifier: Modifier = Modifier,
    containerColor: Color,
    cornerRadius: Int = 20,
    content: @Composable () -> Unit
) {
    // 使用纯色 Box + 显式不透明度
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(containerColor) 
            .border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(cornerRadius.dp))
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
fun ExpressiveToolPanel(
    config: ToolVisual,
    maxWidth: Dp = 280.dp,
    onMeasured: (androidx.compose.ui.unit.IntSize) -> Unit = {}
) {
    val infinite = rememberInfiniteTransition(label = "tool_anim")
    val pulseScale by infinite.animateFloat(0.9f, 1.1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")

    OpaquePanel(
        modifier = Modifier
            .widthIn(min = 180.dp, max = maxWidth)
            .onSizeChanged { onMeasured(it) },
        containerColor = config.color
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f), CircleShape))
                Icon(
                    imageVector = config.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "正在准备",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = config.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
fun CameraPreviewBubble(preview: ImageBitmap, height: Dp = 120.dp) {
    Image(
        bitmap = preview,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = 92.dp, height = height)
            .clip(RoundedCornerShape(10.dp))
            .graphicsLayer { rotationZ = 90f }
    )
}

@Composable
fun DetailedMessageBubble(text: String, state: AppState.AssistantState) {
    OpaquePanel(
        modifier = Modifier.widthIn(min = 120.dp, max = 280.dp),
        containerColor = Color(0xFFFFFFFF) // 显式使用全白实体色
    ) {
        Column {
            if (state == AppState.AssistantState.LISTENING) {
                Text(
                    text = "正在聆听",
                    style = MaterialTheme.typography.labelSmall,
                    color = TongXingColors.Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF111111), // 几乎纯黑，确保对比度
                lineHeight = 22.sp
            )
        }
    }
}
