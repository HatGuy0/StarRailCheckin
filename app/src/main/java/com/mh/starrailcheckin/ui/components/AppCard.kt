package com.mh.starrailcheckin.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一卡片：靠极轻的色差 + 柔和阴影与背景区分，不使用描边。
 * - 浅色：在背景上叠一层极淡黑，只比背景深一点点。
 * - 深色：在背景上叠一层极淡白，只比背景亮一点点。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = LocalAppDarkTheme.current
    val colors = MiuixTheme.colorScheme
    val bg = colors.background
    // 浅色叠淡黑、深色叠淡白，色差压到最低
    val cardColor = if (dark) {
        Color.White.copy(alpha = 0.06f).compositeOver(bg)
    } else {
        Color.Black.copy(alpha = 0.04f).compositeOver(bg)
    }
    val contentColor = if (dark) {
        colors.onSurfaceContainerHighest
    } else {
        colors.onSurfaceContainer
    }
    val shadowColor = if (dark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val shape = RoundedCornerShape(16.dp)

    Card(
        modifier = modifier.shadow(
            elevation = 6.dp,
            shape = shape,
            clip = true,
            ambientColor = shadowColor,
            spotColor = shadowColor
        ),
        colors = CardDefaults.defaultColors(color = cardColor, contentColor = contentColor),
        content = content
    )
}
