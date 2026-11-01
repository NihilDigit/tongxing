package com.nihildigit.tongxing.ui.accessibility

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.LocalContentColor

/**
 * 可访问性工具函数和扩展
 *
 * 提供无障碍支持相关的工具函数和扩展属性
 */

/**
 * 为装饰性图标清除语义（屏幕阅读器将跳过装饰性图标）
 */
fun Modifier.decorativeIcon(): Modifier = this.clearAndSetSemantics {}

/**
 * 为功能性图标添加内容描述
 */
fun Modifier.functionalIcon(description: String): Modifier = this.semantics {
    contentDescription = description
}

/**
 * 创建一个标准的装饰性图标（屏幕阅读器跳过）
 */
@Composable
fun DecorativeIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = modifier,
        tint = tint
    )
}

/**
 * 创建一个标准的功能性图标（带有内容描述）
 */
@Composable
fun FunctionalIcon(
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

/**
 * 可访问性相关的语义描述模板
 */
object AccessibilityDescriptions {
    fun button(label: String, state: String? = null): String {
        return if (state != null) "$label 按钮，$state" else "$label 按钮"
    }

    fun toggle(label: String, isOn: Boolean): String {
        return "$label 开关，当前${if (isOn) "开启" else "关闭"}"
    }

    fun slider(label: String, value: String): String {
        return "$label 滑块，当前值 $value"
    }

    fun status(label: String, status: String): String {
        return "$label 状态：$status"
    }
}
