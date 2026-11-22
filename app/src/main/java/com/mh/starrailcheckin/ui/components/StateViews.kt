package com.mh.starrailcheckin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 查询页三态复用组件：加载中 / 失败 / 空数据。
 * 口径统一：主文 13sp，副提示 12sp，错误用 error 色，占位用次级色；整页与区块均可通过 [modifier] 约束尺寸。
 */

/** 加载中：进度圈 + 次级文字。 */
@Composable
fun LoadingState(
    text: String = "加载中…",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            InfiniteProgressIndicator(Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
        }
    }
}

/** 失败：错误文案 + 可选副提示 + 可选重试按钮。 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    retryText: String? = "重试",
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            if (!hint.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(hint, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            }
            if (onRetry != null && !retryText.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                TextButton(text = retryText, onClick = onRetry)
            }
        }
    }
}

/** 空数据：居中次级提示。 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            textAlign = TextAlign.Center
        )
    }
}
