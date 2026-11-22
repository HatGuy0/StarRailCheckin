package com.mh.starrailcheckin.ui.gacha

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.util.Prefs
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PoolNames = listOf("角色池", "光锥池", "常驻池", "新手池")

@Composable
internal fun GachaShowcaseScreen(
    stats: GachaStats,
    headerUid: String,
    headerRegion: String,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current

    var currentPool by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var rankFilter by remember { mutableIntStateOf(0) }
    var timeFilter by remember { mutableIntStateOf(0) }
    var showPlan by remember { mutableStateOf(false) }
    var jade by remember { mutableIntStateOf(Prefs.gachaJade(context)) }
    var pass by remember { mutableIntStateOf(Prefs.gachaPass(context)) }
    var std by remember { mutableIntStateOf(Prefs.gachaStd(context)) }

    val metric = stats.metrics()
    val trimmedQuery = query.trim()
    val plainFilter = trimmedQuery.isBlank() && rankFilter == 0 && timeFilter == 0
    val items = stats.visibleItems(currentPool, trimmedQuery, rankFilter, timeFilter)

    val headerText = buildString {
        append("跃迁记录轨迹")
        if (headerUid.isNotBlank()) append("  UID $headerUid")
        if (headerRegion.isNotBlank()) append("  · $headerRegion")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        TopAppBar(
            title = "抽卡统计",
            color = Color.Transparent,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                headerText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 10.dp)
            )

            // 顶部四指标（2×2）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricCard(metric.noLoseRate, "小保底不歪", Modifier.weight(1f))
                MetricCard(metric.totalPulls, "总抽数", Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricCard(metric.charPerUp, "每UP角色花费", Modifier.weight(1f))
                MetricCard(metric.lcPerUp, "每UP光锥花费", Modifier.weight(1f))
            }

            // 卡池分类标签
            Row(
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PoolNames.forEachIndexed { idx, name ->
                    PoolTab(name, selected = currentPool == idx) { currentPool = idx }
                }
            }

            // 搜索 + 星级筛选
            Row(
                modifier = Modifier
                    .padding(top = 6.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppCard(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索角色/光锥名",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RankChip("全部", selected = rankFilter == 0) { rankFilter = 0 }
                    RankChip("5星", selected = rankFilter == 1) { rankFilter = 1 }
                    RankChip("4星", selected = rankFilter == 2) { rankFilter = 2 }
                }
            }

            // 时间范围筛选
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("时间", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                RankChip("全部", selected = timeFilter == 0) { timeFilter = 0 }
                RankChip("近7天", selected = timeFilter == 1) { timeFilter = 1 }
                RankChip("近30天", selected = timeFilter == 2) { timeFilter = 2 }
                RankChip("近90天", selected = timeFilter == 3) { timeFilter = 3 }
            }

            // 明细列表
            if (items.isEmpty()) {
                Text(
                    if (plainFilter) "该卡池暂无出金记录" else "无匹配结果",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp)
                )
            } else {
                items.forEach { item ->
                    when (item) {
                        is VisibleItem.Carry -> CarryRow(item.carry)
                        is VisibleItem.FiveStar -> FiveStarRow(item.item, item.pity, currentPool, stats)
                        is VisibleItem.FourStar -> FourStarRow(item.item, currentPool, stats)
                    }
                }
            }

            // 保底详情
            AppCard(modifier = Modifier.padding(top = 6.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("保底详情", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        stats.pityDetail(currentPool),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 累计统计（右上攒抽规划入口）
            AppCard(modifier = Modifier.padding(top = 6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "🎯 攒抽规划", onClick = { showPlan = true })
                }
                Text(
                    stats.summary(jade, pass, std),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            Button(
                onClick = onShare,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("分享统计长图")
            }
        }
    }

    if (showPlan) {
        GachaPlanDialog(
            stats = stats,
            currentPool = currentPool,
            onDismiss = { showPlan = false },
            onSaved = { j, p, s ->
                jade = j
                pass = p
                std = s
                showPlan = false
            }
        )
    }
}

@Composable
private fun PoolTab(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun RankChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}
