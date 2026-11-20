package com.mh.starrailcheckin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * Miuix 弹窗双按钮行：左侧取消（普通），右侧确认（primary 强调）。
 * 参照官方示例：SpaceBetween + weight(1f) 平分，中间 20dp 间距。
 */
@Composable
fun DialogButtonRow(
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = "取消",
    confirmEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(20.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            enabled = confirmEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

/** Miuix 弹窗单按钮（右对齐，primary 强调）。 */
@Composable
fun DialogSingleButton(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            text = text,
            onClick = onClick,
            colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
        )
    }
}
