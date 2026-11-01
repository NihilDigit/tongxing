package com.nihildigit.tongxing.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object TongXingDimens {
    // 间距规范
    val SpaceXs = 4.dp
    val SpaceS = 8.dp
    val SpaceM = 16.dp   // 规范要求组件间距 ≥16dp
    val SpaceL = 24.dp
    val SpaceXl = 32.dp

    // 触控规范
    val TouchMin = 64.dp // 规范要求最小触控区域 64dp

    // 圆角规范
    val RadiusXs = 4.dp
    val RadiusS = 8.dp
    val RadiusM = 16.dp
    val RadiusL = 24.dp
    val RadiusXl = 32.dp

    // 图标规范
    val IconS = 24.dp
    val IconM = 32.dp
    val IconL = 48.dp
}

val TongXingShapes = Shapes(
    extraSmall = RoundedCornerShape(TongXingDimens.RadiusXs),
    small = RoundedCornerShape(TongXingDimens.RadiusS),
    medium = RoundedCornerShape(TongXingDimens.RadiusM),
    large = RoundedCornerShape(TongXingDimens.RadiusL),
    extraLarge = RoundedCornerShape(TongXingDimens.RadiusXl),
)

