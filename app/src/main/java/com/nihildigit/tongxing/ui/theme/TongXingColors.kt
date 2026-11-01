package com.nihildigit.tongxing.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

object TongXingColors {
    val Primary = Color(0xFFE07A5F) // 暖阳橙
    val Secondary = Color(0xFF81B29A) // 守护绿
    val Tertiary = Color(0xFFA8DADC) // 天际蓝

    val Surface = Color(0xFFFFF8F5) // 贝壳白
    val SurfaceVariant = Color(0xFFF5EBE6) // 暖灰
    val OnSurface = Color(0xFF3D405B) // 墨蓝
    val OnSurfaceVariant = Color(0xFF6B7280) // 灰蓝
    val Outline = Color(0xFFD1D5DB) // 浅灰

    val Safe = Color(0xFF4CAF50)
    val Caution = Color(0xFFE9C46A)
    val Warning = Color(0xFFF4A261)
    val Critical = Color(0xFFE63946)

    object Digital {
        val Primary = Color(0xFFE07A5F)
        val Secondary = Color(0xFFC97B84)
        val Tertiary = Color(0xFFD4A5A5)
        val Glow = Color(0xFFFFF1EC)
    }

    object Navigation {
        val Primary = Color(0xFF81B29A)
        val Secondary = Color(0xFFA8DADC)
        val Tertiary = Color(0xFFB5E2E0)
        val Glow = Color(0xFFF1FAEE)
    }
}

fun tongXingLightColorScheme(): ColorScheme {
    return lightColorScheme(
        primary = TongXingColors.Primary,
        onPrimary = Color.White,
        primaryContainer = TongXingColors.Digital.Glow,
        onPrimaryContainer = TongXingColors.OnSurface,

        secondary = TongXingColors.Secondary,
        onSecondary = Color.White,
        secondaryContainer = TongXingColors.Navigation.Glow,
        onSecondaryContainer = TongXingColors.OnSurface,

        tertiary = TongXingColors.Tertiary,
        onTertiary = TongXingColors.OnSurface,

        background = TongXingColors.Surface,
        onBackground = TongXingColors.OnSurface,
        surface = TongXingColors.Surface,
        onSurface = TongXingColors.OnSurface,
        surfaceVariant = TongXingColors.SurfaceVariant,
        onSurfaceVariant = TongXingColors.OnSurfaceVariant,

        outline = TongXingColors.Outline,

        error = TongXingColors.Critical,
        onError = Color.White,
        errorContainer = TongXingColors.Critical.copy(alpha = 0.12f),
        onErrorContainer = TongXingColors.OnSurface,
    )
}

