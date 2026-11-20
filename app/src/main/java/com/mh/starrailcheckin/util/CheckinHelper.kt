package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.network.MicaApi

/**
 * 打卡方案2 校验：
 * 登录且已成功获取游玩天数后，检测到打开游戏 → 20 分钟后强制刷新天数，
 * 天数相对基准 +1（含以上）→ 打卡成功；天数不变 → 未打卡。
 *
 * 基准（每天 04:00 换日）口径：
 * - 优先取今日已固化基准（凌晨 3:50/3:55/3:59 后台刷新成功后写入）；
 * - 今日基准缺失时，继承上一打卡日收官天数作为兜底；
 * - 只有「今天还没打开过游戏」时，同步到的天数才可建立/抬高基准，
 *   避免把隔夜未同步的 +1 误算成今天新增；
 * - 任何刷新路径都先经过 [checkAndMarkChecked] 统一判定，不在各处直接写打卡标记。
 */
object CheckinHelper {

    /**
     * 统一判定：当前天数相对基准 +1 及以上 → 落打卡标记。幂等，已打卡直接返回 true。
     * 供手动刷新、20 分钟后台校验、Worker 兜底、提醒前预热四条路径共用。
     */
    fun checkAndMarkChecked(context: Context, currentDays: Int): Boolean {
        if (!Prefs.isLoggedIn(context)) return false
        if (Prefs.checkinScheme(context) != 2) return false
        if (currentDays <= 0) return false
        if (Prefs.isScheme2Checked(context)) return true

        val baseline = effectiveBaseline(context)
        if (baseline <= 0) {
            LogHelper.i("Checkin", "基准未建立，暂不判定（current=$currentDays）")
            return false
        }
        if (currentDays >= baseline + 1) {
            Prefs.setTodayChecked(context, true)
            Prefs.saveCheckHistory(context, Prefs.todayNaturalStr(), true)
            Prefs.setScheme2Checked(context)
            NotifyHelper.cancelRemindNotification(context)
            LogHelper.i("Checkin", "天数 $baseline→$currentDays（+${currentDays - baseline}），打卡成功")
            return true
        }
        LogHelper.i("Checkin", "天数未增加（$baseline→$currentDays），今日未打卡")
        return false
    }

    /**
     * 取今日有效基准：今日固化基准 → 上一打卡日收官值继承 → 0（未建立）。
     * 纯只读，不写盘。
     */
    fun effectiveBaseline(context: Context): Int {
        val today = Prefs.todayBaseline(context)
        if (today > 0) return today
        val closed = Prefs.lastClosedDays(context)
        val closedDay = Prefs.lastClosedDay(context)
        if (closed > 0 && closedDay.isNotBlank() && closedDay != Prefs.todayStr()) {
            LogHelper.i("Checkin", "今日基准缺失，继承上一打卡日收官值=$closed（$closedDay）")
            return closed
        }
        return 0
    }

    /**
     * 记录一次与打卡判定无关的天数刷新（登录 / 我的页 / 提醒前预热）。
     * 仅在今天还没打开过游戏时抬高基线，绝不写打卡标记。
     */
    fun noteDaysFetched(context: Context, days: Int, openedToday: Boolean) {
        if (!Prefs.isLoggedIn(context) || days <= 0) return
        if (openedToday) return
        val baseline = Prefs.todayBaseline(context)
        if (baseline <= 0 || days > baseline) {
            Prefs.setTodayBaseline(context, days)
            Prefs.setLastClosedDay(context, days, Prefs.todayStr())
            LogHelper.i("Checkin", "今日基线更新为 $days（今天尚未打开游戏）")
        }
    }

    /**
     * 凌晨后台刷新成功后固化今日基准：写今日基准 + 收官值 + 刷新时刻。
     */
    fun establishBaseline(context: Context, days: Int) {
        if (!Prefs.isLoggedIn(context) || days <= 0) return
        Prefs.setTodayBaseline(context, days)
        Prefs.setLastClosedDay(context, days, Prefs.todayStr())
        Prefs.setBaselineFetchedAt(context, System.currentTimeMillis())
        Prefs.setBaselineStage(context, 0)
        LogHelper.i("Checkin", "今日基准已固化=$days")
    }

    /**
     * 凌晨三次刷新全失败后的白天兜底：今日基准仍缺失时，若今天还没打开过游戏，
     * 用本地最后一次成功刷新的游玩天数缓存建立基准；已开过游戏则缓存值可能被白天进度污染，不采用。
     */
    fun establishBaselineFromCacheIfNeeded(context: Context) {
        if (!Prefs.isLoggedIn(context)) return
        if (effectiveBaseline(context) > 0) return
        if (UsageStatsHelper.getTodayGameUsage(context).opened) {
            LogHelper.i("Checkin", "白天兜底：今天已打开过游戏，缓存天数不可作为基准")
            return
        }
        val days = Prefs.playDays(context, Prefs.uid(context))
        if (days > 0) {
            Prefs.setTodayBaseline(context, days)
            Prefs.setLastClosedDay(context, days, Prefs.todayStr())
            LogHelper.i("Checkin", "白天兜底：以最后一次成功刷新缓存 $days 建立基准")
        }
    }

    /**
     * 检测到打开游戏时：记录打开前天数 + 必要时建立今日基线。
     * 即使当前 days==0（Worker 兜底 UsageStats 路径里还没 force 刷过）也要返回，
     * 由后续 20 分钟校验里的 force 刷新拿到真实值。
     */
    fun noteGameOpened(context: Context) {
        if (!Prefs.isLoggedIn(context)) return
        val days = Prefs.playDays(context, Prefs.uid(context))
        if (days > 0) {
            Prefs.setGameOpenBaseline(context, days)
            if (Prefs.todayBaseline(context) <= 0) {
                Prefs.setTodayBaseline(context, days)
                Prefs.setLastClosedDay(context, days, Prefs.todayStr())
                LogHelper.i("Checkin", "打开游戏，今日基线=$days")
            }
        } else {
            LogHelper.i("Checkin", "打开游戏，当前缓存天数为0，将在20分钟校验时 force 刷新拿真实值")
        }
    }

    /**
     * 按系统使用记录回填最近 N 天的打卡历史：当天有前台时长即记为已打卡。
     * 只补写缺失的已打卡记录，不写 false、不覆盖已有记录；系统仅保留约 11 天使用记录。
     */
    fun reconcileRecentHistory(context: Context, days: Int = 11) {
        runCatching {
            UsageStatsHelper.lastNDaysOpenedWithDate(context, days).forEach { (date, opened) ->
                if (opened) Prefs.saveCheckHistory(context, date, true)
            }
        }.onFailure { LogHelper.e("Checkin", "回填近期打卡历史失败", it) }
    }

    /** 执行一次「打开游戏 20 分钟后」的天数校验，返回是否打卡成功。 */
    fun verifyGameOpen(context: Context): Boolean {
        if (!Prefs.isLoggedIn(context)) { Prefs.setGameOpenCheckDueAt(context, 0L); return false }
        if (Prefs.checkinScheme(context) != 2) { Prefs.setGameOpenCheckDueAt(context, 0L); return false }
        val before = Prefs.todayBaseline(context).takeIf { it > 0 }

        // 保留 dueAt 到网络请求成功返回后再清零，避免网络失败时 Worker 下次 run 已无兜底目标
        val stats = runCatching { MicaApi.fetchPlayerStats(context, force = true) }
            .onFailure {
                LogHelper.e("Checkin", "打开游戏20分钟后刷新天数失败，dueAt 保留待 Worker 兜底", it)
            }
            .getOrNull()
        if (stats == null) return false  // 不清 dueAt，保留兜底机会

        Prefs.setProfileRefreshAt(context, System.currentTimeMillis())
        Prefs.setGameOpenCheckDueAt(context, 0L)

        // 基线缺失（当天首次运行就开了游戏、网络异常导致没建立）：
        // 以本次天数建立基线，并重新安排 20 分钟校验 —— 这样下一轮有了基线才能判定
        if (before == null || before <= 0) {
            Prefs.setTodayBaseline(context, stats.activeDays)
            Prefs.setLastClosedDay(context, stats.activeDays, Prefs.todayStr())
            Prefs.setGameOpenBaseline(context, stats.activeDays)
            val nextDue = System.currentTimeMillis() + AlarmHelper.GAME_OPEN_CHECK_DELAY_MS
            Prefs.setGameOpenCheckDueAt(context, nextDue)
            AlarmHelper.scheduleGameOpenCheck(context, AlarmHelper.GAME_OPEN_CHECK_DELAY_MS)
            LogHelper.i("Checkin", "20分钟校验：基线缺失，以当前 ${stats.activeDays} 天建立基线并重新安排 20 分钟校验")
            return false
        }

        return checkAndMarkChecked(context, stats.activeDays)
    }
}
