package com.mh.starrailcheckin.ui.ledger

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.gacha.GachaHistory
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 开拓月历：近三月星琼 / 通票汇总、月度统计与来源构成。 */
class LedgerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        LedgerScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = LocalAppDarkTheme.current

    var monthsData by remember { mutableStateOf<List<MicaApi.LedgerMonth>>(emptyList()) }
    var selected by remember { mutableStateOf("") }
    var totalPulls by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadAll() {
        scope.launch {
            loading = true
            error = null
            // 注意：当前月与历史月份的网络请求都必须在 IO 线程执行；
            // 历史月份若在主线程发请求会抛 NetworkOnMainThreadException 被吞掉，导致只剩当月。
            val result = withContext(Dispatchers.IO) {
                runCatching { MicaApi.fetchLedgerMonth(context, "") }.mapCatching { current ->
                    val targets = LinkedHashSet<String>()
                    current.month.takeIf { it.isNotBlank() }?.let { targets += it }
                    targets += current.optionalMonths
                    val list = mutableListOf(current)
                    targets.forEach { m ->
                        if (m.isNotBlank() && m != current.month) {
                            runCatching { MicaApi.fetchLedgerMonth(context, m) }
                                .onSuccess { list += it }
                                .onFailure { e ->
                                    LogHelper.i("Mica", "ledger 月份 $m 拉取失败：${e.message}")
                                }
                        }
                    }
                    list
                }
            }
            result.onSuccess { list ->
                monthsData = list.sortedByDescending { it.month }
                selected = monthsData.firstOrNull()?.month ?: ""
                totalPulls = countGachaPulls(context)
            }.onFailure { e ->
                error = when (e) {
                    is MicaException -> e.message
                    else -> e.message ?: "开拓月历获取失败"
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadAll() }

    val gradient = remember(dark) {
        Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = "开拓月历",
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                loading && monthsData.isEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                ) {
                    InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("正在加载月历…", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }

                !error.isNullOrBlank() -> AppCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(error ?: "", color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                        TextButton(text = "重试", onClick = { loadAll() })
                    }
                }

                monthsData.isNotEmpty() -> {
                    LedgerRangeTitle(monthsData)
                    OverviewCard(monthsData, totalPulls)
                    MonthlyBarCard(monthsData)
                    SourceDonutCard(monthsData)
                    SelectedMonthCard(monthsData.firstOrNull { it.month == selected } ?: monthsData.first())
                }
            }
        }
    }
}

/** 读取本地最近一次抽卡快照的总抽数。 */
private fun countGachaPulls(context: android.content.Context): Int {
    return runCatching {
        val entry = GachaHistory.list(context).firstOrNull() ?: return 0
        JSONArray(entry.records).length()
    }.getOrDefault(0)
}

// ---------------- 统计区间标题 ----------------

/** 参照插件统计卡顶部：“2026年9-11月星琼统计”。 */
@Composable
private fun LedgerRangeTitle(data: List<MicaApi.LedgerMonth>) {
    if (data.isEmpty()) return
    val sorted = data.sortedByDescending { it.month }
    val latest = sorted.first().month
    val earliest = sorted.last().month
    val year = latest.takeIf { it.length >= 4 }?.substring(0, 4) ?: ""
    fun mm(m: String) = if (m.length == 6) m.substring(4, 6).trimStart('0') else m
    val title = if (latest == earliest) {
        "${year}年${mm(latest)}月星琼统计"
    } else {
        "${year}年${mm(earliest)}-${mm(latest)}月星琼统计"
    }
    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
}

// ---------------- 数据总览 ----------------

@Composable
private fun OverviewCard(data: List<MicaApi.LedgerMonth>, totalPulls: Int) {
    val totalHcoin = data.sumOf { it.hcoin }
    val totalRails = data.sumOf { it.railsPass }
    val bestHcoin = data.maxByOrNull { it.hcoin }
    val bestRails = data.maxByOrNull { it.railsPass }

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewCell(fmtWan(totalHcoin), "总星琼", Modifier.weight(1f))
                OverviewCell("${totalPulls}抽", "总抽数", Modifier.weight(1f))
                OverviewCell(fmtMonthShort(bestHcoin?.month ?: ""), "星琼最多", Modifier.weight(1f))
                OverviewCell(fmtWan(bestHcoin?.hcoin ?: 0), "当月星琼", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewCell("${totalRails}张", "专&通票", Modifier.weight(1f))
                OverviewCell(fmtMonthShort(bestRails?.month ?: ""), "通票最多", Modifier.weight(1f))
                OverviewCell("${data.size}个月", "统计区间", Modifier.weight(1f))
                OverviewCell(fmtWan(totalHcoin + totalRails * 160), "折合约", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverviewCell(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

// ---------------- 月份统计（柱状） ----------------

@Composable
private fun MonthlyBarCard(data: List<MicaApi.LedgerMonth>) {
    val primary = MiuixTheme.colorScheme.primary
    val maxHcoin = (data.maxOfOrNull { it.hcoin } ?: 1)
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text("月份统计", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("星琼", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            Spacer(Modifier.height(12.dp))
            data.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    Text(
                        fmtMonthShort(m.month),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.width(44.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(m.hcoin.toFloat() / maxHcoin)
                                .height(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(primary)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("${m.hcoin}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------------- 详细统计（环形） ----------------

private data class SourceSlice(val name: String, val num: Int, val color: Color)

@Composable
private fun SourceDonutCard(data: List<MicaApi.LedgerMonth>) {
    val aggregated = LinkedHashMap<String, Int>()
    data.forEach { month ->
        month.entries.forEach { e ->
            val name = e.action.ifBlank { "其他" }
            aggregated[name] = (aggregated[name] ?: 0) + e.num
        }
    }
    val total = aggregated.values.sum()
    val palette = remember {
        listOf(
            Color(0xFF5B8FF9), Color(0xFFE8A33D), Color(0xFF9270CA),
            Color(0xFF5AD8A6), Color(0xFF6DC8EC), Color(0xFFF08BB4),
            Color(0xFFFF9D4D), Color(0xFF9AA5B1)
        )
    }
    val slices = aggregated.entries.mapIndexed { i, (k, v) ->
        SourceSlice(k, v, palette[i % palette.size])
    }.sortedByDescending { it.num }

    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text("详细统计", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            if (total <= 0) {
                Text("本月暂无明细记录", color = MiuixTheme.colorScheme.onSurfaceSecondary, fontSize = 13.sp)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp)) {
                    Canvas(modifier = Modifier.size(140.dp)) {
                        val stroke = 26.dp.toPx()
                        val diameter = size.minDimension - stroke
                        val topLeft = androidx.compose.ui.geometry.Offset(
                            (size.width - diameter) / 2f, (size.height - diameter) / 2f
                        )
                        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                        var start = -90f
                        slices.forEach { s ->
                            if (s.num <= 0) return@forEach
                            val sweep = s.num.toFloat() / total * 360f
                            drawArc(
                                color = s.color,
                                startAngle = start,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(
                                    width = stroke, cap = StrokeCap.Butt
                                )
                            )
                            start += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("总计", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text("$total", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(s.color)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.name,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            Text(
                                "${s.num}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 选中月份来源明细 ----------------

@Composable
private fun SelectedMonthCard(data: MicaApi.LedgerMonth) {
    val primary = MiuixTheme.colorScheme.primary
    if (data.entries.isEmpty()) return
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${fmtMonthShort(data.month)} 收支来源",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            data.entries.forEach { entry ->
                Column(Modifier.padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.action.ifBlank { "其他" },
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "+${entry.num}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = primary
                        )
                    }
                    if (entry.percent > 0) {
                        Spacer(Modifier.height(5.dp))
                        FractionBar(entry.percent)
                    }
                }
            }
        }
    }
}

@Composable
private fun FractionBar(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MiuixTheme.colorScheme.primary)
        )
    }
}

private fun fmtMonthShort(m: String): String {
    if (m.length == 6) return "${m.substring(4, 6)} 月"
    return m
}

/** 过万折算为 w 展示，保留两位有效小数。 */
private fun fmtWan(v: Int): String {
    if (v < 10000) return v.toString()
    val w = v / 10000.0
    val s = String.format(java.util.Locale.ROOT, "%.2f", w).trimEnd('0').trimEnd('.')
    return "${s}w"
}
