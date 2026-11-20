package com.mh.starrailcheckin.ui.check

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mh.starrailcheckin.ui.components.DialogButtonRow
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import com.mh.starrailcheckin.network.SignInfo
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.SignHelper
import com.mh.starrailcheckin.util.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.mh.starrailcheckin.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 打卡日期 / 近 7 天时长均用本地状态持有，进入页面后定时重新读取，
    // 避免一次性快照导致日历、连续签到、时长图表在写入后不刷新。
    var checkinDates by remember { mutableStateOf(Prefs.checkinDateList(context)) }
    val streakDays = Prefs.getStreakDays(checkinDates)
    val todayChecked = Prefs.todayNaturalStr() in checkinDates

    // 近 7 天时长 + 周目标
    var weekUsage by remember { mutableStateOf<List<Triple<String, Long, Long>>>(emptyList()) }
    var goalHours by remember { mutableStateOf(10f) }
    var showGoalDialog by remember { mutableStateOf(false) }
    // 日历日详情/备注弹窗
    var noteDate by remember { mutableStateOf<String?>(null) }
    // 清空打卡记录二次确认
    var showClearCheckin by remember { mutableStateOf(false) }

    // 米游社签到状态
    var signInfo by remember { mutableStateOf<SignInfo?>(null) }
    var signLoading by remember { mutableStateOf(true) }
    var signing by remember { mutableStateOf(false) }
    var signError by remember { mutableStateOf<String?>(null) }
    // 每日自动签到
    var autoSign by remember { mutableStateOf(Prefs.autoSignEnabled(context)) }
    var autoSignHour by remember { mutableIntStateOf(Prefs.autoSignHour(context)) }

    fun loadSignInfo(force: Boolean = false) {
        scope.launch {
            signLoading = true
            signError = null
            val result = withContext(Dispatchers.IO) {
                runCatching { MicaApi.fetchSignInfo(context, force = force) }
            }
            result.onSuccess { signInfo = it }
                .onFailure { e ->
                    signError = if (e is MicaException) e.message else (e.message ?: "签到信息获取失败")
                }
            signLoading = false
        }
    }

    fun doSign() {
        scope.launch {
            signing = true
            val result = withContext(Dispatchers.IO) {
                runCatching { SignHelper.signAndNotify(context) }
            }
            result.onSuccess { r ->
                    Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                    loadSignInfo()
                }
                .onFailure { e ->
                    val msg = if (e is MicaException) e.message else (e.message ?: "签到失败")
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            signing = false
        }
    }

    LaunchedEffect(Unit) { loadSignInfo() }

    fun exportCheckinCsv() {
        val dates = Prefs.checkinDateList(context)
        if (dates.isEmpty()) {
            Toast.makeText(context, "暂无打卡记录可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = buildString {
            appendLine("日期,打卡状态")
            dates.forEach { appendLine("$it,已打卡") }
        }
        val send = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, csv)
            type = "text/csv"
        }
        runCatching { context.startActivity(Intent.createChooser(send, "导出打卡记录CSV")) }
            .onFailure { Toast.makeText(context, "暂无可用的分享应用", Toast.LENGTH_SHORT).show() }
    }

    fun clearCheckin() {
        scope.launch {
            Prefs.clearCheckinDates(context)
            checkinDates = Prefs.checkinDateList(context)
            Toast.makeText(context, "打卡记录已清空", Toast.LENGTH_SHORT).show()
        }
    }

    /** 手动标记 / 取消某一天的打卡（本地持久化，不依赖系统使用记录）。 */
    fun toggleCheckin(date: String, checked: Boolean) {
        Prefs.saveCheckHistory(context, date, checked)
        checkinDates = Prefs.checkinDateList(context)
        Toast.makeText(
            context,
            if (checked) "已标记 $date 打卡" else "已取消 $date 打卡",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(Unit) {
        // 进入打卡页先按系统使用记录回填最近几天的打卡历史（系统仅保留约 11 天）
        withContext(Dispatchers.IO) { CheckinHelper.reconcileRecentHistory(context) }
        while (true) {
            weekUsage = withContext(Dispatchers.IO) { UsageStatsHelper.last7DaysSplit(context) }
            goalHours = Prefs.weeklyGoalHours(context)
            // 检测到当天打开过游戏（界面判定已打卡）就落库记一天，保证本月天数 / 连续签到同步 +1
            val usage = withContext(Dispatchers.IO) { UsageStatsHelper.getTodayGameUsage(context) }
            if (usage.opened) Prefs.saveCheckHistory(context, Prefs.todayNaturalStr(), true)
            checkinDates = Prefs.checkinDateList(context)
            delay(30_000)
        }
    }

    val calendar = Calendar.getInstance()
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    val today = calendar.get(Calendar.DAY_OF_MONTH)

    var selectedMonth by remember { mutableIntStateOf(month) }
    var selectedYear by remember { mutableIntStateOf(year) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 标题
        Text(
            text = "打卡日历",
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 米游社签到卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MiuixTheme.colorScheme.primary)
                            .width(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "米游社签到", style = MiuixTheme.textStyles.title1)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "刷新",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { loadSignInfo(force = true) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    signLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在获取签到状态…", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    signInfo != null -> SignBody(
                        info = signInfo!!,
                        signing = signing,
                        onSign = { doSign() }
                    )
                    else -> {
                        Text(
                            text = signError ?: "签到数据不可用",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }

                signError?.let { msg ->
                    if (signInfo == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = msg, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 每日自动签到
        val autoSignHours = remember { (0..23).map { SpinnerEntry(title = "%02d:00".format(it)) } }
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MiuixTheme.colorScheme.primary)
                            .width(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "自动签到", style = MiuixTheme.textStyles.title1)
                }
                SwitchPreference(
                    checked = autoSign,
                    onCheckedChange = {
                        autoSign = it
                        Prefs.setAutoSignEnabled(context, it)
                        Toast.makeText(
                            context,
                            if (it) "已开启每日自动签到" else "已关闭每日自动签到",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    title = "每日自动签到",
                    summary = "到达设定时间后自动完成米游社签到并通知结果"
                )
                OverlaySpinnerPreference(
                    items = autoSignHours,
                    selectedIndex = autoSignHour.coerceIn(0, 23),
                    title = "自动签到时间",
                    summary = "%02d:00".format(autoSignHour),
                    onSelectedIndexChange = { idx ->
                        autoSignHour = idx
                        Prefs.setAutoSignHour(context, idx)
                    }
                )
                Text(
                    text = "提示：受系统省电策略影响，自动签到可能略有延迟；失败会自动重试一次。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 日历卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // 月份选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "<",
                        style = MiuixTheme.textStyles.title1,
                        modifier = Modifier.clickable {
                            if (selectedMonth == 0) {
                                selectedMonth = 11
                                selectedYear--
                            } else {
                                selectedMonth--
                            }
                        }
                    )
                    Text(
                        text = "${selectedYear}年 ${selectedMonth + 1}月",
                        style = MiuixTheme.textStyles.title1
                    )
                    Text(
                        text = ">",
                        style = MiuixTheme.textStyles.title1,
                        modifier = Modifier.clickable {
                            if (selectedMonth == 11) {
                                selectedMonth = 0
                                selectedYear++
                            } else {
                                selectedMonth++
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 星期头
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 日期网格
                val daysInMonth = getDaysInMonth(selectedYear, selectedMonth)
                val firstDayOfWeek = getFirstDayOfWeek(selectedYear, selectedMonth)
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                Column {
                    var dayCounter = 1 - firstDayOfWeek
                    repeat(6) { week ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            repeat(7) { day ->
                                if (dayCounter in 1..daysInMonth) {
                                    val dateStr = sdf.format(Calendar.getInstance().apply {
                                        set(selectedYear, selectedMonth, dayCounter)
                                    }.time)
                                    val isChecked = dateStr in checkinDates
                                    val isToday = dayCounter == today && selectedMonth == month && selectedYear == year

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when {
                                                    isToday -> MiuixTheme.colorScheme.primary
                                                    isChecked -> MiuixTheme.colorScheme.primaryContainer
                                                    else -> Color.Transparent
                                                }
                                            )
                                            .clickable { noteDate = dateStr },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$dayCounter",
                                            style = MiuixTheme.textStyles.body2,
                                            color = when {
                                                isToday -> Color.White
                                                isChecked -> MiuixTheme.colorScheme.primary
                                                else -> MiuixTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(36.dp))
                                }
                                dayCounter++
                            }
                        }
                        if (dayCounter > daysInMonth) return@repeat
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 统计
                val monthCheckedCount = checkinDates.count {
                    it.startsWith("$selectedYear-${String.format("%02d", selectedMonth + 1)}")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${monthCheckedCount}天",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.primary
                        )
                        Text(
                            text = "本月已打卡",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${streakDays}天",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.primary
                        )
                        Text(
                            text = "连续签到",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${daysInMonth - monthCheckedCount}天",
                            style = MiuixTheme.textStyles.title1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Text(
                            text = "本月未打卡",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 近7天游玩时长 + 周目标卡片
        val weekTotalMin = weekUsage.sumOf { it.second + it.third }
        val goalMin = (goalHours * 60).coerceAtLeast(1f)
        val goalProgress = (weekTotalMin / goalMin).coerceIn(0f, 1f)
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MiuixTheme.colorScheme.primary)
                                .width(4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "近 7 天游玩时长",
                            style = MiuixTheme.textStyles.title1
                        )
                    }
                    Text(
                        text = "本周 %.1fh / 目标 %.1fh".format(weekTotalMin / 60f, goalHours),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showGoalDialog = true }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = goalProgress,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "提示：安卓系统限制，仅可读取最近11天应用使用记录",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 柱状图（今天 → 6 天前；紫=原版，青=云游戏，堆叠）
                val display = weekUsage.reversed()
                val maxMin = (display.maxOfOrNull { it.second + it.third } ?: 0L).coerceAtLeast(1L)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    display.forEach { (label, off, cloud) ->
                        val total = off + cloud
                        val barHeight = if (total <= 0L) 12.dp else 24.dp + 96.dp * (total.toFloat() / maxMin)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${total}m",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(width = 32.dp, height = barHeight)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MiuixTheme.colorScheme.primary)
                            ) {
                                if (cloud > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height((cloud.toFloat() / maxMin * 96.dp.value).dp)
                                            .align(Alignment.BottomCenter)
                                            .background(MiuixTheme.colorScheme.secondary)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }
            }
        }

        // 打卡记录管理（由「更多」页迁入）
        Spacer(modifier = Modifier.height(16.dp))
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleCheckin(Prefs.todayNaturalStr(), !todayChecked) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (todayChecked) "取消今日打卡" else "今日打卡", style = MiuixTheme.textStyles.title3)
                    Text(
                        if (todayChecked) "✅ 今日已打卡" else "未自动识别，点击手动标记",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { exportCheckinCsv() }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("导出打卡记录", style = MiuixTheme.textStyles.title3)
                    Text("分享为 CSV 表格", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showClearCheckin = true }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("清空打卡记录", style = MiuixTheme.textStyles.title3)
                    Text("删除所有打卡数据", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }

        // 日历日详情 / 备注弹窗
        noteDate?.let { date ->
            NoteDialog(
                date = date,
                checked = date in checkinDates,
                initialNote = Prefs.checkinNote(context, date),
                onDismiss = { noteDate = null },
                onSave = { note ->
                    Prefs.setCheckinNote(context, date, note)
                    noteDate = null
                },
                onToggleChecked = { toggleCheckin(date, date !in checkinDates) }
            )
        }

        // 清空打卡记录二次确认
        if (showClearCheckin) {
            OverlayDialog(
                show = true,
                title = "确认清空",
                onDismissRequest = { showClearCheckin = false },
                content = {
                    Text(
                        text = "将删除全部本地打卡日期与备注，此操作不可恢复。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    DialogButtonRow(
                        confirmText = "清空",
                        onConfirm = {
                            clearCheckin()
                            showClearCheckin = false
                        },
                        onDismiss = { showClearCheckin = false }
                    )
                }
            )
        }

        // 周目标编辑弹窗
        if (showGoalDialog) {
            GoalDialog(
                initialHours = goalHours,
                onDismiss = { showGoalDialog = false },
                onSave = { h ->
                    goalHours = h
                    Prefs.setWeeklyGoalHours(context, h)
                    showGoalDialog = false
                }
            )
        }

    }
}

@Composable
private fun NoteDialog(
    date: String,
    checked: Boolean,
    initialNote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onToggleChecked: () -> Unit
) {
    var note by remember { mutableStateOf(initialNote) }
    var checkedState by remember { mutableStateOf(checked) }
    OverlayDialog(
        show = true,
        title = date,
        onDismissRequest = onDismiss,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (checkedState) "✅ 当日已打卡" else "❌ 当日未打卡",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Button(
                    onClick = {
                        checkedState = !checkedState
                        onToggleChecked()
                    }
                ) {
                    Text(if (checkedState) "取消打卡" else "标记打卡")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = "打卡备注",
                modifier = Modifier.fillMaxWidth()
            )
            DialogButtonRow(
                confirmText = "保存",
                onConfirm = { onSave(note) },
                onDismiss = onDismiss
            )
        }
    )
}

@Composable
private fun GoalDialog(
    initialHours: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit
) {
    var hours by remember { mutableStateOf(initialHours.toString()) }
    OverlayDialog(
        show = true,
        title = "每周游玩目标",
        onDismissRequest = onDismiss,
        content = {
            Text(
                text = "设置本周累计游玩时长目标（小时，1–168）",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextField(
                value = hours,
                onValueChange = { hours = it.filter { c -> c.isDigit() || c == '.' } },
                label = "目标小时数",
                modifier = Modifier.fillMaxWidth()
            )
            DialogButtonRow(
                confirmText = "保存",
                onConfirm = {
                    val h = hours.toFloatOrNull()?.coerceIn(1f, 168f) ?: initialHours
                    onSave(h)
                },
                onDismiss = onDismiss
            )
        }
    )
}

/** 奖励名 → 本地打包的 WebP 图标（assets/item/）。 */
private val AWARD_ICON_ASSETS = mapOf(
    "冒险记录" to "item/adventure_log.webp",
    "凝缩以太" to "item/condensed_aether.webp",
    "信用点" to "item/credits.webp",
    "罐装快乐水" to "item/soda.webp",
    "星琼" to "item/stellar_jade.webp",
    "遗失碎金" to "item/lost_gold.webp",
    "能量饮料（无糖）" to "item/energy_drink.webp",
    "一次性动力臂" to "item/power_arm.webp",
    "提纯以太" to "item/refined_aether.webp",
    "遗失晶块" to "item/lost_crystal.webp",
    "漫游指南" to "item/guide.webp"
)

/** 签到奖励图标：优先读取本地打包图标，本地缺失再按直链下载（内存/磁盘缓存）。无底色徽章。 */
@Composable
private fun AwardIcon(
    name: String,
    iconUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bmp by remember(name, iconUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(name, iconUrl) {
        bmp = withContext(Dispatchers.IO) {
            val local = AWARD_ICON_ASSETS[name]
            val localBmp = if (local != null) {
                runCatching {
                    context.assets.open(local).use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            } else null
            localBmp ?: EnkaImageLoader.bitmapForUrl(context, iconUrl, maxEdge = 128)
        }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun SignBody(info: SignInfo, signing: Boolean, onSign: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (info.signed) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (info.signed) "✅ 今日已签到" else "❌ 今日未签到",
                    color = if (info.signed) Color(0xFF4CAF50) else MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "累计 ${info.totalSignDay} 天",
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.primary
            )
        }

        if (info.todayReward.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "今日奖励：${info.todayReward}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onSign,
            enabled = !info.signed && !signing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (signing) {
                InfiniteProgressIndicator(modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("签到中…")
            } else {
                Text(if (info.signed) "今日已签到" else "一键签到")
            }
        }

        if (info.awards.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本月奖励日历",
                    style = MiuixTheme.textStyles.title3,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "已领取 ${info.totalSignDay.coerceAtMost(info.awards.size)} / ${info.awards.size}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val todayIdx = if (info.signed) info.totalSignDay - 1 else info.totalSignDay
            info.awards.chunked(5).forEachIndexed { rowIdx, row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { colIdx, award ->
                        val idx = rowIdx * 5 + colIdx
                        val claimed = idx < info.totalSignDay
                        val isToday = idx == todayIdx
                        val tileColor = when {
                            isToday -> Color.White
                            claimed -> MiuixTheme.colorScheme.primary
                            else -> MiuixTheme.colorScheme.onSurfaceSecondary
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isToday -> MiuixTheme.colorScheme.primary
                                        claimed -> MiuixTheme.colorScheme.primaryContainer
                                        else -> MiuixTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .padding(vertical = 5.dp, horizontal = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AwardIcon(
                                name = award.name,
                                iconUrl = award.icon,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${idx + 1}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = tileColor
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = award.name,
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = tileColor
                            )
                        }
                    }
                    repeat(5 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun getDaysInMonth(year: Int, month: Int): Int {
    val calendar = Calendar.getInstance()
    calendar.set(year, month, 1)
    return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
}

private fun getFirstDayOfWeek(year: Int, month: Int): Int {
    val calendar = Calendar.getInstance()
    calendar.set(year, month, 1)
    return calendar.get(Calendar.DAY_OF_WEEK) - 1
}
