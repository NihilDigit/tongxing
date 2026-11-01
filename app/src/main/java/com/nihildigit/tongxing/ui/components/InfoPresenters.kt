package com.nihildigit.tongxing.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun AssistantMessagePlate(
    text: String,
    isSpeaking: Boolean = false,
    modifier: Modifier = Modifier
) {
    val springSpec = spring<IntOffset>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioLowBouncy)
    
    AnimatedVisibility(
        visible = text.isNotEmpty(),
        enter = fadeIn() + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) + slideInVertically(animationSpec = springSpec) { 20 },
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier.semantics { contentDescription = if (isSpeaking) "助手正在播报：$text" else "助手消息：$text" }
    ) {
        GlassSurface(
            containerColor = if (isSpeaking) TongXingColors.Digital.Glow else TongXingColors.Surface,
            alpha = 0.85f,
            cornerRadius = TongXingDimens.RadiusM
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun ToolResultCard(
    title: String,
    content: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessLow)) { 40 },
        exit = fadeOut() + scaleOut(),
        modifier = modifier.semantics { contentDescription = "$title：$content" }
    ) {
        GlassSurface(
            containerColor = TongXingColors.Navigation.Glow,
            alpha = 0.9f,
            cornerRadius = TongXingDimens.RadiusM
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                    Icon(icon, contentDescription = null, tint = TongXingColors.Secondary, modifier = Modifier.size(32.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TongXingColors.Secondary)
                    Text(content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
