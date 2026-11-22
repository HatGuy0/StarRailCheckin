package com.mh.starrailcheckin.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 当日星穹铁道使用情况 */
data class GameUsage(val opened: Boolean, val foregroundMs: Long, val launchCount: Int)

/**
 * UsageStats 查询封装
 * 查询区间严格限定当日0点~当前时间；时间段合并去重（切后台/分屏/闪退导致的重复会话）。
 * 跨零点打卡缓存由 Prefs（绑定日期）自动重置。
 */
object UsageStatsHelper {
    // 统计包名（官服 + B服 + 云版），任一打开都算打卡成功
    private const val PKG_OFFICIAL = "com.mihoyo.hkrpg"
    private const val PKG_OFFICIAL_CASE = "com.miHoYo.hkrpg"
    private const val PKG_BILI = "com.miHoYo.hkrpg.bilibili"
    private const val PKG_CLOUD = "com.miHoYo.cloudgames.hkrpg"
    private fun allGamePkgs(): List<String> =
        listOf(PKG_OFFICIAL, PKG_OFFICIAL_CASE, PKG_BILI, PKG_CLOUD)

    private fun isOfficial(pkg: String): Boolean =
        pkg.equals(PKG_OFFICIAL, true) || pkg.equals(PKG_OFFICIAL_CASE, true) || pkg.equals(PKG_BILI, true)

    /** 判断包名是否匹配任一游戏（原版+云版） */
    private fun matchPkg(context: Context, pkg: String): Boolean = isGamePackage(pkg)

    /** 公开：判断包名是否命中任一星穹铁道安装包（官服/B服/云游戏）。 */
    fun isGamePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return allGamePkgs().any { it.equals(pkg, ignoreCase = true) }
    }

    /** 区分原版/云版前台时长（用于双色柱状图） */
    fun getTodaySplitUsage(context: Context): Pair<Long, Long> {
        if (!hasUsageAccess(context)) return 0L to 0L
        val begin = todayStart()
        val end = System.currentTimeMillis()
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var official = 0L; var cloud = 0L
        runCatching {
            val stats = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            stats.forEach { s ->
                when {
                    isOfficial(s.packageName) -> official += s.totalTimeInForeground
                    s.packageName.equals(PKG_CLOUD, true) -> cloud += s.totalTimeInForeground
                }
            }
        }
        return official to cloud
    }

    /** 指定时间段原版/云版分别的前台时长 */
    fun getSplitForegroundMs(context: Context, begin: Long, end: Long): Pair<Long, Long> {
        if (!hasUsageAccess(context)) return 0L to 0L
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var official = 0L; var cloud = 0L
        runCatching {
            um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end).forEach { s ->
                when {
                    isOfficial(s.packageName) -> official += s.totalTimeInForeground
                    s.packageName.equals(PKG_CLOUD, true) -> cloud += s.totalTimeInForeground
                }
            }
        }
        return official to cloud
    }
    // 会话合并阈值：两次前台事件间隔小于该值视为同一段会话
    private const val MERGE_GAP_MS = 60_000L

    @Suppress("DEPRECATION") // AppOps 权限检查在 UsageStats 场景暂无等价新 API
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun todayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** 打卡日切点：每天 04:00（与 Prefs.todayStr 保持一致）。 */
    private fun checkinDayStart(): Long {
        val cal = Calendar.getInstance()
        if (cal.get(Calendar.HOUR_OF_DAY) < 4) cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 4); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 今日打卡判断（核心链路：闹钟触发后重新查询）
     * 判定规则：当日0点至当前时间的星穹铁道前台时长>0 或 启动次数>0 → 今日已打开
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND 前台事件类型常量暂无替代
    fun getTodayGameUsage(context: Context): GameUsage {
        if (!hasUsageAccess(context)) {
            LogHelper.w("UsageStats", "无应用使用记录权限，无法查询")
            return GameUsage(false, 0L, 0)
        }
        val begin = checkinDayStart()
        val end = System.currentTimeMillis()
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // 前台时长：当日聚合
        var foregroundMs = 0L
        runCatching {
            val stats: List<UsageStats> = um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
            foregroundMs = stats.filter { matchPkg(context, it.packageName) }
                .sumOf { it.totalTimeInForeground }
        }.onFailure { LogHelper.e("UsageStats", "queryUsageStats失败", it) }

        // 启动次数：统计前台事件并做合并去重
        var launches = 0
        var lastFg = 0L
        runCatching {
            val events = um.queryEvents(begin, end)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (matchPkg(context, ev.packageName) &&
                    ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (lastFg == 0L || ev.timeStamp - lastFg > MERGE_GAP_MS) launches++
                    lastFg = ev.timeStamp
                }
            }
        }.onFailure { LogHelper.e("UsageStats", "queryEvents失败", it) }

        val opened = launches > 0 || foregroundMs > 0
        return GameUsage(opened, foregroundMs, launches)
    }

    /** 指定时间段内的前台时长（7天统计图用） */
    fun getForegroundMs(context: Context, begin: Long, end: Long): Long {
        if (!hasUsageAccess(context)) return 0L
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return runCatching {
            um.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
                .filter { matchPkg(context, it.packageName) }
                .sumOf { it.totalTimeInForeground }
        }.getOrDefault(0L)
    }

    /** 最近 N 天每日是否打开，返回 (yyyy-MM-dd, 是否打开)，供回填本地打卡历史。 */
    fun lastNDaysOpenedWithDate(context: Context, days: Int): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
        for (i in (days - 1) downTo 0) {
            val start = now.timeInMillis - i * 86_400_000L
            val end = if (i == 0) System.currentTimeMillis() else start + 86_400_000L
            val opened = getForegroundMs(context, start, end) > 0
            out.add(fmt.format(Date(start)) to opened)
        }
        return out
    }

    /**
     * 查询星穹铁道最近一次 moveToForeground 事件的时间戳（毫秒）。
     * 返回 0 表示查询失败或没有任何打开记录。
     * Worker 每 15 分钟用它对比 Prefs.lastGameOpenAt，判断"有没有新的打开事件"。
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND 前台事件类型常量暂无替代
    fun lastGameForegroundAt(context: Context, lookbackMs: Long = 24 * 60 * 60 * 1000L): Long {
        if (!hasUsageAccess(context)) return 0L
        val end = System.currentTimeMillis()
        val begin = end - lookbackMs
        val um = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var maxTs = 0L
        runCatching {
            val events = um.queryEvents(begin, end)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (matchPkg(context, ev.packageName) &&
                    ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                    ev.timeStamp > maxTs
                ) maxTs = ev.timeStamp
            }
        }.onFailure { LogHelper.e("UsageStats", "queryEvents失败", it) }
        return maxTs
    }

    /** 最近7天（含今天）游戏时长（分钟），列表元素为 (标签, 原版分钟, 云版分钟) */
    fun last7DaysSplit(context: Context): List<Triple<String, Long, Long>> {
        val out = mutableListOf<Triple<String, Long, Long>>()
        val fmt = SimpleDateFormat("MM-dd", Locale.CHINA)
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
        for (i in 6 downTo 0) {
            val start = now.timeInMillis - i * 86_400_000L
            val end = if (i == 0) System.currentTimeMillis() else start + 86_400_000L
            val label = if (i == 0) "今天" else fmt.format(Date(start))
            val (off, cloud) = getSplitForegroundMs(context, start, end)
            out.add(Triple(label, off / 60_000L, cloud / 60_000L))
        }
        return out
    }

    /** 最近7天（含今天）游戏时长（分钟），列表元素为 (标签, 分钟数) */
    fun last7DaysUsage(context: Context): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        val fmt = SimpleDateFormat("MM-dd", Locale.CHINA)
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
        for (i in 6 downTo 0) {
            val start = now.timeInMillis - i * 86_400_000L
            val end = if (i == 0) System.currentTimeMillis() else start + 86_400_000L
            val label = if (i == 0) "今天" else fmt.format(Date(start))
            out.add(label to getForegroundMs(context, start, end) / 60_000L)
        }
        return out
    }

    /**
     * 最近N天每日是否打开（纯只读 UsageStats，不写签到历史表）
     * ⚠️ 数据隔离：此方法只读取系统使用记录，绝不读写 Prefs 签到历史表，
     *    防止 UsageStats 11天外返回 false 污染本地签到记录。
     *    签到历史只由打卡流程写入当天记录。
     */
    fun lastNDaysOpened(context: Context, days: Int): List<Triple<String, Boolean, Boolean>> {
        val out = mutableListOf<Triple<String, Boolean, Boolean>>()
        val fmt = SimpleDateFormat("MM-dd", Locale.CHINA)
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0)
        for (i in (days - 1) downTo 0) {
            val start = now.timeInMillis - i * 86_400_000L
            val end = if (i == 0) System.currentTimeMillis() else start + 86_400_000L
            val label = when {
                i == 0 -> "今天"
                i == 1 -> "昨天"
                else -> fmt.format(Date(start))
            }
            val opened = getForegroundMs(context, start, end) > 0
            out.add(Triple(label, opened, opened))
        }
        return out
    }
}
