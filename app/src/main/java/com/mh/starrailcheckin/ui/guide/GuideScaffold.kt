package com.mh.starrailcheckin.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 引导页（启动 → 权限 → 协议 → 完成）公共骨架。
 *
 * 视觉骨架参考 ahmmedrejowan/OnboardingScreen-JetpackCompose（Apache-2.0,
 * Copyright 2024 ahmmedrejowan），本地按本工程主题与四步固定向导改写；
 * 未引入其插画、图标与演示素材，图形使用本工程自有资源。
 */

/** 向导紫系渐变（与现有 anime_btn 配色一致） */
val GuideGradientColors = listOf(
    Color(0xFFA294FF),
    Color(0xFF7B6CF6),
    Color(0xFF5A4AD6)
)

/** 白底主按钮上的深紫文字/图标色 */
val GuidePrimaryText = Color(0xFF5A4AD6)

/** 整屏渐变背景容器 */
@Composable
fun GuideBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = GuideGradientColors,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = colors))
    ) {
        // 背景铺满状态栏区域做沉浸式渐变；内容整体下移避让状态栏，避免标题/按钮被遮挡。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            content = content
        )
    }
}

/** 居中图形圆形半透明底座，内部放自有 logo / 图标 */
@Composable
fun GuideIconCircle(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 标题 + 副标题文本块 */
@Composable
fun GuideTitleBlock(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Center,
    centered: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = align
        )
        if (subtitle != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = align
            )
        }
    }
}

/** 全宽胶囊主按钮：白底、深紫文字，禁用态降透明度 */
@Composable
fun GuidePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = GuidePrimaryText,
            disabledContainerColor = Color.White.copy(alpha = 0.35f),
            disabledContentColor = GuidePrimaryText.copy(alpha = 0.5f)
        ),
        enabled = enabled
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 四步流程指示器：当前步为加长胶囊，其余为小圆点 */
@Composable
fun GuideStepIndicator(
    total: Int,
    current: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 24.dp else 8.dp, 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) Color.White
                        else Color.White.copy(alpha = 0.4f)
                    )
            )
        }
    }
}
