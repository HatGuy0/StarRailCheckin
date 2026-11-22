package com.mh.starrailcheckin.ui.gacha

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.util.Prefs
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LoseRed = Color(0xFFE53935)
private val WinGreen = Color(0xFF43A047)

/**
 * 攒抽规划弹窗：填写资源、选择目标池，实时给出各档达成概率与抽数估算。
 * 保底/概率计算复用 WarpPlanner；资源持久化走 Prefs。
 */
@Composable
internal fun GachaPlanDialog(
    stats: GachaStats,
    currentPool: Int,
    onDismiss: () -> Unit,
    onSaved: (jade: Int, pass: Int, std: Int) -> Unit
) {
    val context = LocalContext.current

    var dialogPool by remember {
        mutableIntStateOf(if (currentPool == WarpPlanner.POOL_LC) WarpPlanner.POOL_LC else WarpPlanner.POOL_CHAR)
    }
    var jade by remember { mutableIntStateOf(Prefs.gachaJade(context)) }
    var pass by remember { mutableIntStateOf(Prefs.gachaPass(context)) }
    var std by remember { mutableIntStateOf(Prefs.gachaStd(context)) }

    val currentPulls = jade / 160 + pass
    val guaranteed = dialogPool == WarpPlanner.POOL_CHAR && stats.charGuaranteed()
    val carry = stats.carryOf(dialogPool)
    val plan = remember(dialogPool, carry, guaranteed, currentPulls) {
        WarpPlanner.plan(dialogPool, carry, guaranteed, currentPulls, listOf(1, 2, 3))
    }

    OverlayDialog(
        show = true,
        title = "攒抽规划（估算，非官方保证）",
        onDismissRequest = onDismiss,
        content = {
            Column {
                TextField(
                    value = jade.toString(),
                    onValueChange = { jade = it.trim().toIntOrNull() ?: 0 },
                    label = "当前星琼",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = pass.toString(),
                    onValueChange = { pass = it.trim().toIntOrNull() ?: 0 },
                    label = "专属票（星轨专票）",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = std.toString(),
                    onValueChange = { std = it.trim().toIntOrNull() ?: 0 },
                    label = "普通通票（星轨通票，仅常驻池）",
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PoolChip("角色池", selected = dialogPool == WarpPlanner.POOL_CHAR) {
                        dialogPool = WarpPlanner.POOL_CHAR
                    }
                    PoolChip("光锥池", selected = dialogPool == WarpPlanner.POOL_LC) {
                        dialogPool = WarpPlanner.POOL_LC
                    }
                }

                Text(
                    "可用 ${plan.pulls} 抽 · 已垫 ${plan.carry} 抽 · " +
                        if (plan.guaranteed) "大保底（下金必 UP）" else "小保底（50% UP）",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                plan.rows.forEach { row ->
                    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                val gapText = if (row.gapWorst > 0) "最坏还差 ${row.gapWorst} 抽" else "最坏保底已够"
                                Text(
                                    "期望 ${row.expectedPulls} 抽 · 最坏 ${row.worstPulls} 抽 · $gapText",
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            val prob = (row.probability * 100).toInt()
                            val probColor = when {
                                prob >= 80 -> WinGreen
                                prob >= 50 -> MiuixTheme.colorScheme.primary
                                else -> LoseRed
                            }
                            Text("$prob%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = probColor)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                    TextButton(
                        text = "保存",
                        onClick = {
                            Prefs.setGachaJade(context, jade)
                            Prefs.setGachaPass(context, pass)
                            Prefs.setGachaStd(context, std)
                            onSaved(jade, pass, std)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    )
}

@Composable
private fun PoolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = if (selected) Color.White else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    )
}
