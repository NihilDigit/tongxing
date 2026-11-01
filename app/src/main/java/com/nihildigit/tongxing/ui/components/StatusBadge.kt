package com.nihildigit.tongxing.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.tongxing.ui.theme.TongXingDimens
import com.nihildigit.tongxing.ui.theme.TongXingMotion

@Composable
fun StatusBadge(
    label: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = label
) {
    val bg by animateColorAsState(
        targetValue = color.copy(alpha = 0.14f),
        animationSpec = TongXingMotion.colorTweenMedium(),
        label = "badge_bg"
    )
    val fg by animateColorAsState(
        targetValue = color,
        animationSpec = TongXingMotion.colorTweenMedium(),
        label = "badge_fg"
    )

    GlassSurface(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        alpha = 1f,
        blurRadius = 0.dp,
        containerColor = bg,
        cornerRadius = TongXingDimens.RadiusM,
        border = null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = fg)
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
