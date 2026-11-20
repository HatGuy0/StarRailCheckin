package com.mh.starrailcheckin.util

import android.content.Context
import java.util.Calendar

/**
 * 提醒展示闸门：闹钟 / Worker / 通知 / 弹窗载体共用同一套「是否允许弹提醒」口径。
 *
 * 作用：挡住迟到广播、Worker 冷启动补跑、残留通知全屏 Intent 等链路，
 * 在「总开关关闭 / 今日已打卡 / 任一提醒点尚未到点」时不再把弹窗拉起。
 * 设置页「测试弹窗」属于手动触发，不走此闸门。
 *
 * 口径：每个提醒时段到点都应弹窗，直到打卡成功才停止；
 * 「今日已提醒」标记只用于统计/自救，不再作为拦截后续时段的条件。
 */
object RemindGate {

    /** 今日是否已有任一提醒点过点（列表为空时回退旧单时段，默认 21:00）。 */
    fun anyTimePassed(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val times = Prefs.remindTimes(ctx)
        if (times.isNotEmpty()) {
            return times.any { t ->
                val parts = t.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: return@any false
                val m = parts.getOrNull(1)?.toIntOrNull() ?: return@any false
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis <= now
            }
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs.remindHour(ctx))
            set(Calendar.MINUTE, Prefs.remindMinute(ctx))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis <= now
    }

    /**
     * 自动链路是否允许展示提醒弹窗：
     * - 方案1（未登录/未取到天数）：总开关开启 + 今日未打卡（本地记录与 UsageStats 均未开游戏）+ 任一提醒点已过
     * - 方案2（已登录且已取到天数）：只有游玩天数 +1 验证通过才算打卡，打开游戏本身不算
     */
    fun shouldShowRemind(ctx: Context): Boolean {
        if (!Prefs.reminderEnabled(ctx)) {
            LogHelper.i("Gate", "拦截：提醒总开关关闭")
            return false
        }
        if (Prefs.checkinScheme(ctx) == 2) {
            if (Prefs.isScheme2Checked(ctx)) {
                LogHelper.i("Gate", "拦截：方案2 今日已打卡（游玩天数已 +1）")
                return false
            }
        } else {
            if (Prefs.isTodayChecked(ctx)) {
                LogHelper.i("Gate", "拦截：今日已打卡（本地记录）")
                return false
            }
            if (UsageStatsHelper.getTodayGameUsage(ctx).opened) {
                LogHelper.i("Gate", "拦截：今日已打开游戏")
                return false
            }
        }
        val passed = anyTimePassed(ctx)
        if (!passed) LogHelper.i("Gate", "拦截：今日提醒点尚未到点")
        return passed
    }
}
