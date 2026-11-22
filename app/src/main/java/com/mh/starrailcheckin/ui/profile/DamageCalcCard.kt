package com.mh.starrailcheckin.ui.profile

import com.mh.starrailcheckin.util.LogHelper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/** 全角色统一执行 miao-plugin 的角色 calc 规则。 */
@Composable
internal fun DamageCalcCard(d: DetailUi) {
    val ctx = LocalContext.current
    var loading by remember(d.aid) { mutableStateOf(true) }
    var rows by remember(d.aid) { mutableStateOf<List<MiaoRulesEngine.Row>?>(null) }
    var error by remember(d.aid) { mutableStateOf<String?>(null) }

    LaunchedEffect(d) {
        loading = true
        rows = null
        error = null
        if (!MiaoRulesEngine.ensureLoaded(ctx)) {
            error = "miao 伤害规则加载失败"
            loading = false
            return@LaunchedEffect
        }
        val charName = MiaoRulesEngine.resolveCharName(d)
        if (charName == null) {
            error = "暂未找到该角色的 miao 伤害规则"
            loading = false
            return@LaunchedEffect
        }
        if (!MiaoRulesEngine.hasRules(charName)) {
            error = "该角色的 miao 伤害规则暂缺（上游未提供 calc.js）"
            loading = false
            return@LaunchedEffect
        }
        if (!MiaoDataClient.ensureLoaded(ctx, charName)) {
            error = "miao 技能数据缺失，请检查网络后重试"
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) {
            runCatching { MiaoRulesEngine.buildRows(d, charName) }
        }
        rows = result.getOrNull()
        if (rows == null) {
            error = "miao 伤害规则执行失败，请稍后重试"
            result.exceptionOrNull()?.let { LogHelper.e("DamageCalcCard", "miao rule failed: $charName", it) }
        }
        loading = false
    }

    val formatter = remember { NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 0 } }

    Column {
        Text(
            text = "伤害计算",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp)
        )

        Panel {
            Text(
                text = "目标为80级弱点敌人",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "伤害类型",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1.0f)
                )
                Text(
                    text = "暴击/数值",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp)
                )
                Text(
                    text = "期望伤害",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(64.dp)
                )
            }
            Spacer(Modifier.height(4.dp))

            if (loading) {
                Text(
                    text = "正在加载 miao 规则与技能倍率…",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            } else if (error != null) {
                Text(
                    text = error.orEmpty(),
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            } else {
                val result = rows
                if (result.isNullOrEmpty()) {
                    Text(
                        text = "该角色暂无可显示的计算结果",
                        fontSize = 9.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                } else {
                    result.forEach { DamageRow(it, formatter) }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "伤害计算来源：miao-plugin calc.js（全角色统一规则）",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Text(
                text = "倍率数据来源：miao-plugin data.json · 自动跟随上游更新",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
    }
}

@Composable
private fun DamageRow(row: MiaoRulesEngine.Row, formatter: NumberFormat) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1.0f)) {
            Text(
                text = row.title,
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurface,
                lineHeight = 14.sp
            )
        }
        Text(
            text = row.text ?: row.crit?.let(formatter::format) ?: "—",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (row.crit != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = if (row.text != null) "—" else row.avg?.let(formatter::format) ?: "—",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
}

