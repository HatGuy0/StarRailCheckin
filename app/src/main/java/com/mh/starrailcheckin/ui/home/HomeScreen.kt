package com.mh.starrailcheckin.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.mh.starrailcheckin.util.AlarmHelper
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.GameLauncher
import com.mh.starrailcheckin.util.GameUsage
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.PermissionHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.mh.starrailcheckin.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val remindEnabled by Prefs.getRemindEnabled(context).collectAsState(initial = true)
    // 打卡日期列表用本地状态持有：Prefs 快照不会在写盘后自动重发，轮询里重新读取刷新，
    // 否则连续签到天数 / 日历在写入当天历史后不会更新。
    var checkinDates by remember { mutableStateOf(Prefs.checkinDateList(context)) }

    val streakDays = Prefs.getStreakDays(checkinDates)
    // 今日游玩情况（UsageStats）
    var todayUsage by remember { mutableStateOf<GameUsage?>(null) }
    // 今日是否已打卡，按方案区分口径：
    // - 方案2：scheme2 已验证标记，或本地缓存天数相对基准 +1 及以上（手动刷新即时判定的兜底展示）；打开过游戏不算；
    // - 方案1：本地历史记过 或 当日使用记录里打开过游戏，即视为已打卡。
    val todayCheckedIn = if (Prefs.checkinScheme(context) == 2) {
        val scheme2Uid = Prefs.uid(context)
        val scheme2Days = Prefs.playDays(context, scheme2Uid)
        Prefs.isScheme2Checked(context) ||
            Prefs.isPlayDaysAboveBaseline(context, scheme2Uid, scheme2Days)
    } else {
        Prefs.isDayChecked(context, Prefs.todayNaturalStr()) || todayUsage?.opened == true
    }

    // 权限缺失主动引导：进入首页按悬浮窗 → 电池优化 → 精确闹钟顺序，首个缺失项弹窗提示
    var permTip by remember { mutableStateOf<Triple<String, String, (android.content.Context) -> Unit>?>(null) }
    var permChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (permChecked) return@LaunchedEffect
        permChecked = true
        permTip = when {
            !PermissionHelper.canDrawOverlays(context) -> Triple(
                "缺少悬浮窗权限",
                "到点弹窗需要悬浮窗权限（用于在后台/锁屏上层弹出提醒），请在下一步系统设置中开启",
                PermissionHelper::openOverlaySettings
            )
            !PermissionHelper.isIgnoringBatteryOptimizations(context) -> Triple(
                "建议关闭电池优化",
                "到点提醒需要后台持续运行。请到下一步把本应用加入电池优化白名单（允许后台活动不受限制），否则系统可能在到点前杀掉进程，导致不弹窗不响铃。",
                PermissionHelper::openBatteryOptimizationSettings
            )
            !PermissionHelper.canScheduleExactAlarm(context) -> Triple(
                "缺少精确闹钟权限",
                "按时触发提醒需要精确闹钟权限，请在下一步系统设置中开启",
                PermissionHelper::openExactAlarmSettings
            )
            else -> null
        }
    }

    LaunchedEffect(Unit) {
        // 进入首页先按系统使用记录回填最近几天的打卡历史（系统仅保留约 11 天）
        withContext(Dispatchers.IO) { CheckinHelper.reconcileRecentHistory(context) }
        while (true) {
            val usage = withContext(Dispatchers.IO) { UsageStatsHelper.getTodayGameUsage(context) }
            todayUsage = usage
            // 方案2 仅清旧的「天数 +1 已验证」标记，不再影响打卡历史
            if (Prefs.checkinScheme(context) == 2) Prefs.sanitizeScheme2Today(context)
            // 两种方案统一：当日打开过游戏即写入打卡历史，保证本月天数 / 连续签到正确累积；
            // 方案2 的「天数 +1」验证只用于判断是否还需要弹提醒，不再作为写历史的前提。
            if (usage.opened) {
                Prefs.saveCheckHistory(context, Prefs.todayNaturalStr(), true)
                NotifyHelper.cancelRemindNotification(context)
            }
            // 重新读取本地打卡历史，刷新连续天数 / 日历展示
            checkinDates = Prefs.checkinDateList(context)
            delay(30_000)
        }
    }

    // 提醒时段：优先多时段列表，为空回退旧单时段
    val remindTimes = Prefs.remindTimes(context)
    val fallbackTime = Prefs.remindHour(context) to Prefs.remindMinute(context)

    // 倒计时
    var remainingTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(remindEnabled, remindTimes.joinToString()) {
        while (true) {
            if (remindEnabled) {
                val slots = if (remindTimes.isNotEmpty()) {
                    remindTimes
                } else {
                    listOf("%02d:%02d".format(fallbackTime.first, fallbackTime.second))
                }
                val next = slots.mapNotNull { t ->
                    val parts = t.split(":")
                    val h = parts.getOrNull(0)?.toIntOrNull()
                    val m = parts.getOrNull(1)?.toIntOrNull()
                    if (h != null && m != null) AlarmHelper.nextTriggerMillis(h, m) else null
                }.minOrNull()
                remainingTime = if (next != null) next - System.currentTimeMillis() else 0L
            } else {
                remainingTime = 0L
            }
            delay(1000)
        }
    }

    val hours = TimeUnit.MILLISECONDS.toHours(remainingTime)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingTime) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingTime) % 60

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 签到提醒卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "签到提醒",
                        style = MiuixTheme.textStyles.title1
                    )
                    Text(
                        text = if (remindEnabled) "已开启" else "已关闭",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                Switch(
                    checked = remindEnabled,
                    onCheckedChange = {
                        scope.launch { Prefs.setRemindEnabled(context, it) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 连续签到卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MiuixTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "连续签到",
                        style = MiuixTheme.textStyles.title1
                    )
                }
                Text(
                    text = "$streakDays 天",
                    style = MiuixTheme.textStyles.title1,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 今日已打卡卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // 今日已打卡标签
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (todayCheckedIn) Color(0xFFE8F5E9)
                            else Color(0xFFFFEBEE)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (todayCheckedIn) "✅ 今日已打卡" else "❌ 今日未打卡",
                        color = if (todayCheckedIn) Color(0xFF4CAF50) else MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2
                    )
                }

                // 今日游玩状态（UsageStats）
                todayUsage?.let { u ->
                    Spacer(modifier = Modifier.height(10.dp))
                    val min = u.foregroundMs / 60_000
                    Text(
                        text = when {
                            u.opened -> "今日已游玩 ${min} 分钟，启动 ${u.launchCount} 次"
                            else -> "今日尚未打开游戏"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "距离提醒时间",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )

                Text(
                    text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 一键启动按钮（包名跟随云游戏开关）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primaryContainer)
                        .clickable {
                            val ok = GameLauncher.launchGame(context)
                            if (!ok) Toast.makeText(context, "未找到已安装的游戏", Toast.LENGTH_SHORT).show()
                        }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "一键启动${Prefs.gameDisplayName(context)}",
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                        style = MiuixTheme.textStyles.title3
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 公告 / 活动卡片
        AnnounceCard()
    }

    // 权限缺失主动引导弹窗（Miuix OverlayDialog）
    permTip?.let { tip ->
        OverlayDialog(
            show = true,
            title = tip.first,
            onDismissRequest = { permTip = null },
            content = {
                Text(
                    text = tip.second,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                DialogButtonRow(
                    confirmText = "去开启",
                    onConfirm = {
                        tip.third.invoke(context)
                        permTip = null
                    },
                    onDismiss = { permTip = null }
                )
            }
        )
    }
}
