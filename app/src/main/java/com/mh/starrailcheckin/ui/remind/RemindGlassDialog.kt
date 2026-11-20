package com.mh.starrailcheckin.ui.remind

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提醒弹窗（Miuix / MIUI 风格，Compose 通道，Activity 与悬浮窗复用同一套）。
 *
 * 透明背景上居中一张 Miuix 卡片：不铺全屏背景，不使用 backdrop 液态玻璃。
 * 可选自定义背景图铺满整个卡片（叠一层压暗遮罩保证文字可读）；无图时走 Miuix 默认卡片色。
 * 配色跟随 Miuix 主题深浅自适应；声音 / 震动 / 锁屏 / snooze 由载体 Activity/悬浮窗负责。
 */
@Composable
fun RemindGlassDialog(
    title: String,
    message: String,
    launchLabel: String,
    background: Bitmap?,
    onLaunch: () -> Unit,
    onSnooze: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val hasBg = background != null

    val titleColor = if (hasBg) Color.White else scheme.onSurface
    val messageColor = if (hasBg) Color.White.copy(alpha = 0.85f) else scheme.onSurfaceSecondary

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(16.dp),
                    clip = true
                ),
            colors = CardDefaults.defaultColors()
        ) {
            Box {
                // 自定义背景图铺满整个卡片（含外圈）+ 压暗遮罩；无图时透出 Miuix 默认卡片色
                if (background != null) {
                    Image(
                        bitmap = background.asImageBitmap(),
                        contentDescription = stringResource(R.string.home_custom_bg),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                    )
                }

                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.title1,
                        color = titleColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = messageColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(20.dp))

                    // 主按钮：MIUI 主色（primary 蓝）实心
                    Button(
                        onClick = onLaunch,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(launchLabel)
                    }

                    Spacer(Modifier.height(10.dp))

                    // 并排次要文字按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = stringResource(R.string.remind_snooze),
                            onClick = onSnooze,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.remind_close),
                            onClick = onClose,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
