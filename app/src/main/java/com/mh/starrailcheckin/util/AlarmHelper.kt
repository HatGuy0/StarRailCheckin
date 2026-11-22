package com.mh.starrailcheckin.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mh.starrailcheckin.AlarmReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 每日精确闹钟管理（多时段）
 * - 每个已保存时段分配独立 requestCode 精确闹钟；Receiver 触发后幂等全量重排次日
 * - 总开关关闭：清空所有闹钟但保留已配置时段
 * - 触发后由 AlarmReceiver 立即重排，避免一次性闹钟丢失
 */
object AlarmHelper {
    const val ACTION_DAILY = "com.mh.starrailcheckin.ALARM_DAILY"
    const val ACTION_SNOOZE = "com.mh.starrailcheckin.ALARM_SNOOZE"
    const val ACTION_PRE_REFRESH = "com.mh.starrailcheckin.ALARM_PRE_REFRESH"
    /** 方案2：检测到打开游戏 20 分钟后校验游玩天数 */
    const val ACTION_GAME_OPEN_CHECK = "com.mh.starrailcheckin.GAME_OPEN_CHECK"
    /** 方案2：打开游戏后固定校验延时（20 分钟，不可配） */
    const val GAME_OPEN_CHECK_DELAY_MS = 20 * 60_000L
    /** 到点弹窗后的一次性兜底重试（弹窗被系统拦截/进程被回收时二次触发） */
    const val ACTION_RETRY = "com.mh.starrailcheckin.ALARM_RETRY"
    /** 周期兜底：驱动签到 / 便笺 / Cookie 联网检查（WorkManager 被杀后的闹钟双栈） */
    const val ACTION_PERIODIC_TASKS = "com.mh.starrailcheckin.PERIODIC_TASKS"
    /** 凌晨基准固化：3:50 / 3:55 / 3:59 三次后台刷新游玩天数作为今日基准 */
    const val ACTION_BASELINE_REFRESH = "com.mh.starrailcheckin.BASELINE_REFRESH"
    private const val REQUEST_CODE = 0x5A10
    private const val REQUEST_SNOOZE = 0x5A11
    private const val REQUEST_GAME_OPEN_CHECK = 0x5C10
    private const val REQUEST_RETRY = 0x5A12
    private const val REQUEST_PERIODIC_TASKS = 0x5C21
    private const val REQUEST_BASELINE_REFRESH = 0x5C22
    /** 多时段每日闹钟 requestCode 基址（与主/贪睡 code 错开，每个时段占一个 code） */
    private const val MULTI_BASE = 0x5A20
    /** 提醒前 1 分钟的游玩天数预热闹钟 requestCode 基址 */
    private const val PRE_BASE = 0x5B20
    private const val MULTI_MAX = 10
    /** 周期任务兜底闹钟间隔（保持 15min 精确兜底；WM 栈已按任务差异化，闹钟栈不降频以保证不漏跑） */
    private val PERIODIC_TASKS_INTERVAL_MS = 15 * 60_000L

    /** 弹窗「N 分钟后提醒我」：注册一次性延时闹钟 */
    fun scheduleSnooze(context: Context, minutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_SNOOZE,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            LogHelper.i("Alarm", "snooze已注册：${minutes}分钟后再次提醒")
        }.onFailure { LogHelper.e("Alarm", "snooze注册失败", it) }
    }

    /**
     * 到点后的一次性兜底重试：2 分钟后若仍未标记「今日已提醒」，
     * 再走一遍同样的弹窗链路。用于闹钟广播被系统丢弃、或弹窗载体被拦截却没能补弹的情况。
     */
    fun scheduleRetry(context: Context, delayMs: Long = 120_000L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_RETRY,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            LogHelper.i("Alarm", "已注册 ${delayMs / 1000}s 后的兜底重试")
        }.onFailure { LogHelper.e("Alarm", "兜底重试注册失败", it) }
    }

    /**
     * 方案2：游戏打开 20 分钟后校验游玩天数。
     * 每次都覆盖同一 PendingIntent，避免多次打开产生多个校验。
     */
    fun scheduleGameOpenCheck(context: Context, delayMs: Long = GAME_OPEN_CHECK_DELAY_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(10_000L)
        Prefs.setGameOpenCheckDueAt(context, triggerAt)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_GAME_OPEN_CHECK,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_GAME_OPEN_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            LogHelper.i("Alarm", "已注册游戏打开校验：${delayMs / 60_000}分钟后刷新游玩天数")
        }.onFailure { LogHelper.e("Alarm", "游戏打开校验注册失败", it) }
    }

    private fun periodicTasksPendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST_PERIODIC_TASKS,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_PERIODIC_TASKS),
            flags or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 周期任务兜底闹钟：驱动签到 / 便笺 / Cookie 联网检查。
     * 与 WorkManager 双栈——WM 进程被杀被推迟时，闹钟广播仍可拉起进程执行。
     * 用允许空闲的非精确闹钟，Doze 待机下由系统批量触发。
     */
    fun schedulePeriodicTasksAlarm(context: Context, delayMs: Long = PERIODIC_TASKS_INTERVAL_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(60_000L)
        val pi = periodicTasksPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            LogHelper.i("Alarm", "周期任务兜底闹钟已注册：${delayMs / 60_000}分钟后")
        }.onFailure { LogHelper.e("Alarm", "周期任务兜底闹钟注册失败", it) }
    }

    /** 自检：兜底闹钟缺失则补排；已存在则不动。 */
    fun ensurePeriodicTasksAlarm(context: Context) {
        val pending = runCatching {
            periodicTasksPendingIntent(context, PendingIntent.FLAG_NO_CREATE)
        }.getOrNull()
        if (pending == null) {
            LogHelper.i("Alarm", "周期任务兜底闹钟缺失，自动重新注册")
            schedulePeriodicTasksAlarm(context)
        } else {
            LogHelper.i("Alarm", "周期任务兜底闹钟自检：已调度")
        }
    }

    /** 周期兜底闹钟是否已注册（探测异常时保守视为已调度，避免状态面板误报）。 */
    fun isPeriodicTasksAlarmScheduled(context: Context): Boolean = runCatching {
        periodicTasksPendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null
    }.getOrDefault(true)

    /** 取消周期任务兜底闹钟。 */
    fun cancelPeriodicTasksAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            periodicTasksPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)?.let { pi ->
                am.cancel(pi); pi.cancel()
            }
        }
    }

    /** 凌晨三次基准固化的时点：stage 0→3:50，1→3:55，2→3:59。 */
    private fun baselineCal(stage: Int): Calendar = Calendar.getInstance().apply {
        val minute = when (stage) { 0 -> 50; 1 -> 55; else -> 59 }
        set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** 确保当天 3:50 的基准固化闹钟已排；当天已排或时点已过则跳过。 */
    fun ensureBaselineAlarm(context: Context) {
        if (Prefs.baselineAlarmDay(context) == Prefs.todayStr()) return
        val cal = baselineCal(0)
        if (cal.timeInMillis <= System.currentTimeMillis()) return
        scheduleBaselineAt(context, cal.timeInMillis)
        Prefs.setBaselineAlarmDay(context, Prefs.todayStr())
        LogHelper.i("Alarm", "已排今日 3:50 基准固化闹钟")
    }

    /** 基准刷新失败后按阶段排下一次重试；时点已过则放弃，等待白天兜底。 */
    fun scheduleBaselineAttempt(context: Context, stage: Int) {
        val cal = baselineCal(stage)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            LogHelper.w("Alarm", "基准重试时点已过，等待白天兜底")
            return
        }
        scheduleBaselineAt(context, cal.timeInMillis)
    }

    private fun scheduleBaselineAt(context: Context, triggerAt: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_BASELINE_REFRESH,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_BASELINE_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure { LogHelper.e("Alarm", "基准固化闹钟注册失败", it) }
    }

    /** 下一次触发时刻（若今天已过点则排到明天同一时刻） */
    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** 闹钟状态自检：旧主闹钟或任一多时段槽位存在即视为已调度。 */
    fun isAlarmScheduled(context: Context): Boolean {
        return runCatching {
            fun piFor(code: Int): PendingIntent? = PendingIntent.getBroadcast(
                context, code,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (piFor(REQUEST_CODE) != null) return true
            for (i in 0 until MULTI_MAX) {
                if (piFor(MULTI_BASE + i) != null) return true
            }
            false
        }.getOrDefault(true)
    }

    /** 自检+自愈：打开 App / 服务 onStart 时调用；丢失则自动重排。 */
    fun ensureAlarmScheduled(context: Context): Boolean {
        val ok = isAlarmScheduled(context)
        if (!ok) {
            LogHelper.w("Alarm", "检测到闹钟丢失，自动重新注册")
            registerAllAlarms(context)
        } else {
            LogHelper.i("Alarm", "闹钟状态自检：已调度")
        }
        return ok
    }

    /** 多时段：为保存的每个 HH:mm 分配独立 requestCode 精确闹钟。 */
    fun registerAllAlarms(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 总开关关闭：清空所有闹钟但保留已配置时段
        if (!Prefs.reminderEnabled(context)) {
            cancelSingleLegacy(context, am)
            cancelPreSlots(context, am)
            for (i in 0 until MULTI_MAX) {
                runCatching {
                    val old = PendingIntent.getBroadcast(
                        context, MULTI_BASE + i,
                        Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.cancel(old); old.cancel()
                }
            }
            LogHelper.i("Alarm", "提醒总开关已关闭，清空所有闹钟（时段配置保留）")
            return
        }
        // 时段列表为空（历史版本里用户把时段全删了）→ 回退旧单时段，
        // 绝不允许「总开关开着但一个闹钟都没注册」，否则到点永远不会有任何反应
        val times = Prefs.remindTimes(context).take(MULTI_MAX).ifEmpty {
            listOf("%02d:%02d".format(Prefs.remindHour(context), Prefs.remindMinute(context)))
        }.also { if (Prefs.remindTimes(context).isEmpty()) LogHelper.w("Alarm", "提醒时段为空，回退默认时段 ${it.first()}") }
        cancelSingleLegacy(context, am)
        cancelPreSlots(context, am)
        // 先清空所有多时段槽位（防止删除某条后旧槽位残留继续响）
        for (i in 0 until MULTI_MAX) {
            runCatching {
                val old = PendingIntent.getBroadcast(
                    context, MULTI_BASE + i,
                    Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(old); old.cancel()
            }
        }
        if (times.isEmpty()) {
            LogHelper.w("Alarm", "无可解析的提醒时段，跳过注册（不应发生：已回退默认时段）")
            return
        }
        times.forEachIndexed { idx, t ->
            val parts = t.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return@forEachIndexed
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return@forEachIndexed
            val code = MULTI_BASE + idx
            val triggerAt = nextTriggerMillis(h, m)
            val pi = PendingIntent.getBroadcast(
                context, code,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 统一用 setAlarmClock（闹钟类精确触发，优先级最高、系统可见）；
            // 不再对同一 PendingIntent 重复注册 setExact，避免触发时刻/优先级相互覆盖。
            runCatching {
                val showPi = PendingIntent.getActivity(
                    context, code + 1000,
                    Intent(context, com.mh.starrailcheckin.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPi), pi)
            }.onFailure { e ->
                LogHelper.e("Alarm", "setAlarmClock 注册失败($t)，回退精确闹钟", e)
                runCatching {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }.onFailure { LogHelper.e("Alarm", "回退精确闹钟也失败($t)", it) }
            }
            LogHelper.i("Alarm",
                "多时段闹钟 #$idx $t -> ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(triggerAt))}")

            // 提醒前 1 分钟预热：刷新游玩天数，保证到点判定使用最新数据
            val preAt = (triggerAt - 60_000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
            val prePi = PendingIntent.getBroadcast(
                context, PRE_BASE + idx,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_PRE_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preAt, prePi)
            }.onFailure { LogHelper.e("Alarm", "提醒前预热闹钟注册失败($t)", it) }
        }
    }

    private fun cancelPreSlots(context: Context, am: AlarmManager) {
        for (i in 0 until MULTI_MAX) {
            runCatching {
                val old = PendingIntent.getBroadcast(
                    context, PRE_BASE + i,
                    Intent(context, AlarmReceiver::class.java).setAction(ACTION_PRE_REFRESH),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(old); old.cancel()
            }
        }
    }

    private fun cancelSingleLegacy(context: Context, am: AlarmManager) {
        runCatching {
            val legacy = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(legacy); legacy.cancel()
        }
    }

    /** 保留旧入口名：内部改为按保存的时段列表全量注册。 */
    fun registerDailyAlarm(context: Context) = registerAllAlarms(context)

    /** 旧签名兼容：忽略传入时分，按当前保存的时段列表注册，避免覆盖多时段配置。 */
    fun registerDailyAlarm(context: Context, hour: Int, minute: Int) = registerAllAlarms(context)

    /** 取消所有每日闹钟（主 + 多时段槽位）。 */
    fun cancelDailyAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelSingleLegacy(context, am)
        cancelPreSlots(context, am)
        for (i in 0 until MULTI_MAX) {
            runCatching {
                val old = PendingIntent.getBroadcast(
                    context, MULTI_BASE + i,
                    Intent(context, AlarmReceiver::class.java).setAction(ACTION_DAILY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(old); old.cancel()
            }
        }
    }
}
