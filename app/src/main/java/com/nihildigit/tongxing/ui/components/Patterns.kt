package com.nihildigit.tongxing.ui.components

import com.nihildigit.tongxing.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens
import androidx.compose.foundation.Canvas
import kotlin.math.cos
import kotlin.math.sin

object TongXingMotionSpecs {
    const val NavDuration = 220
    val NavEasing = FastOutSlowInEasing
}

enum class AssistantStatus { IDLE, WARMING_UP, RUNNING }

/**
 * 治愈系大滑块
 */
@Composable
fun LargeTuningSlider(label: String, value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, steps: Int = 0, modifier: Modifier = Modifier) {
    val formattedValue = String.format("%.1f", value)
    val spokenValue = stringResource(R.string.current_value, formattedValue)
    Column(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label; stateDescription = spokenValue }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(formattedValue, color = TongXingColors.Primary, fontWeight = FontWeight.Bold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, colors = SliderDefaults.colors(thumbColor = TongXingColors.Primary, activeTrackColor = TongXingColors.Primary, inactiveTrackColor = TongXingColors.Primary.copy(alpha = 0.1f)))
    }
}

/**
 * 选项选择器：用于后端切换
 */
@Composable
fun OptionSelectorRow(label: String, options: List<String>, selectedOption: String, onOptionSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                FilterChip(
                    selected = isSelected,
                    onClick = { onOptionSelect(option) },
                    label = { Text(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TongXingColors.Primary.copy(alpha = 0.2f),
                        selectedLabelColor = TongXingColors.Primary
                    )
                )
            }
        }
    }
}

@Composable
fun SettingSwitchRow(title: String, description: String, icon: ImageVector, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassSurface(containerColor = if (enabled) TongXingColors.Navigation.Glow else TongXingColors.Surface, alpha = 0.6f, cornerRadius = TongXingDimens.RadiusM) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = if (enabled) TongXingColors.Secondary else Color.Gray, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = TongXingColors.Secondary))
        }
    }
}

@Composable
fun SpatialAwarenessRadar(hasThreatLeft: Boolean, hasThreatFront: Boolean, hasThreatRight: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar")
    val pulseAlpha by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse")
    Canvas(modifier = modifier.width(130.dp).height(70.dp)) {
        val w = size.width; val h = size.height; val center = Offset(w / 2, h * 0.95f); val radius = w * 0.48f
        for (i in 1..2) {
            val r = radius * (i / 2f)
            drawArc(Color.White.copy(alpha = 0.05f), 180f, 180f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(width = 1.dp.toPx()))
        }
        drawArc(Color.Gray.copy(alpha = 0.15f), 180f, 180f, true, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        if (hasThreatLeft) drawArc(TongXingColors.Critical.copy(alpha = pulseAlpha * 0.6f), 180f, 60f, true, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        if (hasThreatFront) drawArc(TongXingColors.Critical.copy(alpha = pulseAlpha * 0.6f), 240f, 60f, true, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        if (hasThreatRight) drawArc(TongXingColors.Critical.copy(alpha = pulseAlpha * 0.6f), 300f, 60f, true, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
        drawCircle(Color.White, 3.dp.toPx(), center)
    }
}

@Composable
fun MetricPod(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.Start) {
        Text(label.uppercase(), style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White)
            Spacer(modifier = Modifier.width(2.dp))
            Text(unit, style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
fun AlgorithmReasoningCard(targetId: Int, category: String, evidence: List<Pair<String, String>>) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).border(0.5.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).padding(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountTree, null, tint = TongXingColors.Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("算法决策溯源: #$targetId $category", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Divider(color = Color.White.copy(alpha = 0.05f))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                evidence.chunked(2).forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { (key, value) ->
                            Column(modifier = Modifier.weight(1f)) {
                                Text(key, style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Color.Gray))
                                Text(value, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold))
                            }
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumStartDisk(status: AssistantStatus, onClick: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "core")
    val haloDuration = if (status == AssistantStatus.WARMING_UP) 600 else 2000
    val haloAlpha by infinite.animateFloat(0.1f, 0.4f, infiniteRepeatable(tween(haloDuration), RepeatMode.Reverse), label = "alpha")
    val haloScale by infinite.animateFloat(1f, 1.2f, infiniteRepeatable(tween(haloDuration), RepeatMode.Reverse), label = "scale")
    val color by animateColorAsState(targetValue = when (status) {
        AssistantStatus.IDLE -> TongXingColors.Primary
        AssistantStatus.WARMING_UP -> TongXingColors.Caution
        AssistantStatus.RUNNING -> TongXingColors.Safe
    }, animationSpec = tween(400), label = "color")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        Box(modifier = Modifier.size(130.dp).graphicsLayer { scaleX = haloScale; scaleY = haloScale; alpha = haloAlpha }.background(Brush.radialGradient(listOf(color, Color.Transparent)), CircleShape))
        Surface(onClick = onClick, modifier = Modifier.size(120.dp), shape = CircleShape, color = Color.White, tonalElevation = 4.dp, shadowElevation = 8.dp, border = BorderStroke(1.dp, color.copy(alpha = 0.2f))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
                Icon(imageVector = if (status == AssistantStatus.RUNNING) Icons.Filled.PowerSettingsNew else if (status == AssistantStatus.WARMING_UP) Icons.Filled.Autorenew else Icons.Filled.Bolt, contentDescription = null, tint = color, modifier = Modifier.size(48.dp))
                Text(if (status == AssistantStatus.WARMING_UP) stringResource(R.string.warming_up) else if (status == AssistantStatus.RUNNING) stringResource(R.string.running) else stringResource(R.string.start), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
fun StatusHorizontalHub(statuses: List<StatusItemData>) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), containerColor = TongXingColors.Surface, alpha = 0.3f, cornerRadius = TongXingDimens.RadiusM) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            statuses.forEach { item ->
                Box(contentAlignment = Alignment.BottomEnd) {
                    Icon(item.icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
                    if (item.isFailed) Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(TongXingColors.Critical).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.padding(1.dp)) }
                    else Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(item.color).border(1.dp, Color.White, CircleShape))
                }
            }
        }
    }
}

data class StatusItemData(val label: String, val value: String, val icon: ImageVector, val color: Color, val isFailed: Boolean = false)

@Composable
fun ToolkitEntry(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(TongXingDimens.RadiusM), color = color.copy(alpha = 0.08f), border = BorderStroke(0.5.dp, color.copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(36.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ComboSwitchEntry(name: String, description: String, icon: ImageVector, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(TongXingDimens.RadiusM), color = if (enabled) TongXingColors.Navigation.Glow.copy(alpha = 0.5f) else TongXingColors.SurfaceVariant.copy(alpha = 0.3f), border = BorderStroke(0.5.dp, (if (enabled) TongXingColors.Secondary else Color.Gray).copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = if (enabled) TongXingColors.Secondary else Color.Gray, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge); Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = enabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = TongXingColors.Secondary))
        }
    }
}

@Composable
fun OnboardingProgressBar(currentStep: Int, totalSteps: Int = 5) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(totalSteps) { index ->
            val isCompleted = index < currentStep
            val isCurrent = index == currentStep
            Box(modifier = Modifier.weight(if (isCurrent) 2.5f else 1f).height(5.dp).clip(CircleShape).background(if (isCompleted) TongXingColors.Safe else if (isCurrent) TongXingColors.Primary else TongXingColors.Outline.copy(alpha = 0.2f)))
        }
    }
}

@Composable
fun PermissionEntry(title: String, description: String, isGranted: Boolean, onAction: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(TongXingDimens.RadiusM), color = if (isGranted) TongXingColors.Navigation.Glow.copy(alpha = 0.5f) else TongXingColors.Surface, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (isGranted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (isGranted) TongXingColors.Safe else TongXingColors.Caution)
            Column(modifier = Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!isGranted) Button(onClick = onAction, contentPadding = PaddingPaddingValues(horizontal = 12.dp), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.grant), fontSize = 12.sp) }
        }
    }
}

@Composable
fun OnboardingPageScaffold(title: String, subtitle: String, currentStep: Int, onNext: () -> Unit, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = TongXingColors.Surface) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(TongXingDimens.SpaceM), verticalArrangement = Arrangement.spacedBy(TongXingDimens.SpaceM)) {
            OnboardingProgressBar(currentStep = currentStep)
            Column { Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (currentStep > 0) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } else Spacer(modifier = Modifier.width(1.dp))
                Button(onClick = onNext, modifier = Modifier.height(52.dp).padding(horizontal = 12.dp), shape = MaterialTheme.shapes.medium) { Text(if (currentStep == 4) stringResource(R.string.start_setup_complete) else stringResource(R.string.next)) }
            }
        }
    }
}

@Composable
fun AdvancedSettingsEntry(onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.more_settings), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun CommandItem(icon: ImageVector, command: String, description: String, color: Color) {
    GlassSurface(containerColor = color, alpha = 0.08f, cornerRadius = TongXingDimens.RadiusM) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("“$command”", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge, color = color)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.VolumeUp, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String, unit: String = "", color: Color = Color.White) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = androidx.compose.ui.text.TextStyle(fontSize = 9.sp, color = Color.Gray))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
            if (unit.isNotEmpty()) {
                Text(unit, style = androidx.compose.ui.text.TextStyle(fontSize = 8.sp, color = color.copy(alpha = 0.6f)), modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}

fun PaddingPaddingValues(horizontal: androidx.compose.ui.unit.Dp) = PaddingValues(horizontal = horizontal)

@Composable
fun UserChatBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
        ) { Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun AssistantChatBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(end = 48.dp), horizontalArrangement = Arrangement.Start) {
        GlassSurface(containerColor = TongXingColors.Digital.Glow, alpha = 0.8f, cornerRadius = 16.dp) {
            Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
