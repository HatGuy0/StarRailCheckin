package com.mh.starrailcheckin.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.ui.theme.LocalSuccessColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 权限状态行：左侧标题 + 说明，右侧展示「已开启 / 未开启」或固定提示文案，点击跳转对应系统设置。
 * 设置页「权限管理」弹窗统一使用本组件。
 */
@Composable
fun PermissionRow(
    title: String,
    desc: String,
    enabled: Boolean,
    fixedHint: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                desc,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        Text(
            fixedHint ?: if (enabled) "已开启" else "未开启",
            style = MiuixTheme.textStyles.footnote1,
            color = if (enabled) LocalSuccessColor.current else MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}
