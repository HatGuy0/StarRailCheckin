package com.mh.starrailcheckin.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 清洗 GitHub Release 正文：去掉 Markdown 标题/列表/粗体/行内代码标记，
 * 剔除 versionCode、versionName 等供更新比对用的技术字段行，压缩多余空行。
 */
fun cleanReleaseNotes(raw: String): String {
    if (raw.isBlank()) return ""
    val cleaned = raw.lines()
        .map { it.trim() }
        .filter { line ->
            !line.startsWith("versionCode", ignoreCase = true) &&
                !line.startsWith("versionName", ignoreCase = true)
        }
        .map { line ->
            if (line.isBlank()) return@map ""
            var l = line
            l = l.replace(Regex("^#{1,6}\\s*"), "")
            l = l.replace(Regex("^[-*+]\\s+"), "· ")
            l = l.replace("**", "").replace("`", "")
            l
        }
    // 压缩连续空行，去掉首尾空行
    val out = mutableListOf<String>()
    var prevBlank = true
    for (line in cleaned) {
        if (line.isBlank()) {
            if (!prevBlank) out.add("")
        } else {
            out.add(line)
        }
        prevBlank = line.isBlank()
    }
    return out.joinToString("\n").trim()
}

/**
 * 更新日志文本块：限高 [maxHeight]，超长时内部可滚动，避免把弹窗底部按钮挤出可视区。
 */
@Composable
fun ReleaseNotesBlock(
    notes: String,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 280.dp
) {
    val cleaned = remember(notes) { cleanReleaseNotes(notes) }
    if (cleaned.isBlank()) return
    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = cleaned,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}
