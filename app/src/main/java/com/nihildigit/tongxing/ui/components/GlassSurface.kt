package com.nihildigit.tongxing.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nihildigit.tongxing.ui.theme.TongXingColors
import com.nihildigit.tongxing.ui.theme.TongXingDimens

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    alpha: Float = 0.7f,
    blurRadius: Dp = 16.dp,
    containerColor: Color = TongXingColors.Surface,
    cornerRadius: Dp = TongXingDimens.RadiusL,
    border: BorderStroke? = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.4f)),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    
    Box(modifier = modifier) {
        // 1. 背景层：跟随 Box 大小
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .blur(blurRadius)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            containerColor.copy(alpha = alpha),
                            containerColor.copy(alpha = alpha * 0.7f)
                        )
                    )
                )
        )
        
        // 2. 边框层
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
        )

        // 3. 内容层：由内容决定宽度和高度，不强制 fillMaxWidth
        Column(
            modifier = Modifier.padding(TongXingDimens.SpaceS), 
            content = content
        )
    }
}
