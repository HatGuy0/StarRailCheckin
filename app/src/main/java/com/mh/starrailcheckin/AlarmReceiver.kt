package com.mh.starrailcheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.util.AlarmHelper
import com.mh.starrailcheckin.util.BackgroundRunGuard
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.CookieHealthMonitor
import com.mh.starrailcheckin.util.GameUsage
import com.mh.starrailcheckin.util.KeepAliveHelper
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.RemindGate
import com.mh.starrailcheckin.util.SignHelper
import com.mh.starrailcheckin.util.StaminaMonitor
import com.mh.starrailcheckin.util.UsageStatsHelper

/**
 * 每日闹钟广播接收器 —— 完整链路：闹钟注册 → 广播接收 → 打卡判断 → 满足条件拉起悬浮Activity弹窗
 * 打卡双方案（自动切换）：
 * - 方案1（未登录 / 尚未获取到游玩天数）：今日打开过游戏 → 已完成
 * - 方案2（已登录且已获取到游玩天数）：打开游戏 20 分钟后天数 +1 → 已完成
 * - 方案2 另在提醒时间前 1 分钟预热刷新天数
 *
 * 【到点不弹窗修复要点】
 * 1. goAsync 第一时间调用、闹钟重排挪到后台线程：之前在主线程做几十次 PendingIntent IPC +
 *    setAlarmClock，广播 10s 超时被丢弃时整个提醒链路直接断掉（表现就是到点毫无反应）。
 * 2. 每个副作用（保活服务 / 声音 / 通知 / 弹窗）单独 runCatching 隔离，任何一步失败都不阻断弹窗。
 * 3. 弹窗先拉起、通知后补，弹窗真正展示成功后由载体自行取消通知。
 * 4. 另注册 2 分钟一次性兜底重试，闹钟广播被系统吞掉时还能二次触发。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        LogHelper.i("Alarm", "闹钟触发 action=${intent.action}")

        // 先把广播控制权交出去（必须在 onReceive 内尽早调用），
        // 后面所有可能耗时的 IPC / 网络 / 弹窗逻辑都放到后台线程执行，避免 ANR 丢广播。
        val pending = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // WakeLock 分级：联网长任务（周期兜底/基准固化/预热刷新/游戏校验）持锁 30s 覆盖一次往返；
        // 弹窗 / 重排 / 兜底重试等短任务只做 IPC 与拉起载体，持锁 10s 即可，避免短任务占着 CPU 锁。
        val longWake = intent.action in LONG_WAKE_ACTIONS
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarRailCheckin:alarm")
            .apply { setReferenceCounted(false); acquire(if (longWake) WAKE_LOCK_LONG_MS else WAKE_LOCK_SHORT_MS) }

        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        receiverScope.launch {
            try {
                when (intent.action) {
                    // 提醒前 1 分钟：只预热游玩天数，不弹窗、不重排
                    AlarmHelper.ACTION_PRE_REFRESH -> refreshPlayDays(context)
                    // 方案2：打开游戏 20 分钟后校验游玩天数是否 +1
                    AlarmHelper.ACTION_GAME_OPEN_CHECK -> CheckinHelper.verifyGameOpen(context)
                    // 周期兜底闹钟：驱动签到 / 便笺 / Cookie 联网检查，跑完自排下一轮
                    AlarmHelper.ACTION_PERIODIC_TASKS -> runPeriodicTasks(context)
                    // 凌晨基准固化：刷新游玩天数作为今日打卡基准，失败按阶段重试
                    AlarmHelper.ACTION_BASELINE_REFRESH -> refreshBaseline(context)
                    // snooze：不重排每日闹钟，直接执行打卡判断
                    AlarmHelper.ACTION_SNOOZE -> fireRemind(context)
                    // 到点后的兜底重试：仅用于「本次弹窗被拦截、没真正上屏」的补救。
                    // 若本次已成功展示（reminded=true，用户已看到/已关闭），不再补弹，
                    // 也不再链式注册下一次重试；主时段闹钟 / snooze 不受此标记约束。
                    AlarmHelper.ACTION_RETRY -> {
                        if (Prefs.isTodayReminded(context)) {
                            LogHelper.i("Alarm", "兜底重试：本次已成功提醒，跳过")
                        } else if (RemindGate.shouldShowRemind(context)) {
                            LogHelper.w("Alarm", "兜底重试：本次未上屏，补弹")
                            fireRemind(context)
                        } else {
                            LogHelper.i("Alarm", "兜底重试：已打卡/未到点/开关关闭，跳过")
                        }
                    }
                    else -> {
                        // 一次性闹钟触发后必须重排次日（按多时段全量注册）；放后台线程避免主线程超时
                        runCatching { AlarmHelper.registerAllAlarms(context) }
                            .onFailure { LogHelper.e("Alarm", "重排次日闹钟失败", it) }
                        fireRemind(context)
                    }
                }
            } catch (t: Throwable) {
                LogHelper.e("Alarm", "检测任务异常", t)
            } finally {
                runCatching { if (wl.isHeld) wl.release() }
                runCatching { pending.finish() }
            }
        }
    }

    /** 周期兜底闹钟：依次跑签到 / 便笺 / Cookie 检查，跑完自排下一轮。 */
    private fun runPeriodicTasks(context: Context) {
        val now = System.currentTimeMillis()
        Prefs.setWorkerHeartbeatAt(context, Prefs.TASK_SIGN, now)
        Prefs.setWorkerHeartbeatAt(context, Prefs.TASK_STAMINA, now)
        Prefs.setWorkerHeartbeatAt(context, Prefs.TASK_COOKIE, now)
        if (BackgroundRunGuard.tryAcquire(context, Prefs.TASK_SIGN)) {
            runCatching { SignHelper.autoSignIfDue(context) }
                .onFailure { LogHelper.e("Alarm", "兜底闹钟：签到检查失败", it) }
        }
        if (BackgroundRunGuard.tryAcquire(context, Prefs.TASK_STAMINA)) {
            runCatching { StaminaMonitor.check(context) }
                .onFailure { LogHelper.e("Alarm", "兜底闹钟：体力检查失败", it) }
        }
        if (BackgroundRunGuard.tryAcquire(context, Prefs.TASK_COOKIE)) {
            runCatching { CookieHealthMonitor.checkIfDue(context) }
                .onFailure { LogHelper.e("Alarm", "兜底闹钟：Cookie 检查失败", it) }
        }
        // 保活服务死亡自愈
        KeepAliveHelper.ensureIfEnabled(context)
        AlarmHelper.schedulePeriodicTasksAlarm(context)
    }

    /** 凌晨基准固化：force 刷新游玩天数并固化为今日基准；失败按 3:55 / 3:59 阶段重试。 */
    private fun refreshBaseline(context: Context) {
        val stage = Prefs.baselineStage(context)
        val stats = runCatching { MicaApi.fetchPlayerStats(context, force = true) }
            .onFailure { LogHelper.e("Alarm", "基准刷新异常 stage=$stage", it) }
            .getOrNull()
        if (stats != null) {
            CheckinHelper.establishBaseline(context, stats.activeDays)
            LogHelper.i("Alarm", "基准固化成功 stage=$stage days=${stats.activeDays}")
        } else {
            val next = stage + 1
            Prefs.setBaselineStage(context, next)
            if (next < 3) {
                LogHelper.w("Alarm", "基准刷新失败 stage=$stage，排第 $next 次重试")
                AlarmHelper.scheduleBaselineAttempt(context, next)
            } else {
                LogHelper.w("Alarm", "三次基准刷新均失败，等待白天兜底")
            }
        }
    }

    /** 提醒前 1 分钟刷新游玩天数（方案2 的定时刷新点之一），只建立/抬高基线或读基线判定。 */
    private fun refreshPlayDays(context: Context) {
        if (!Prefs.isLoggedIn(context)) return
        if (Prefs.checkinScheme(context) != 2) return
        val uid = Prefs.uid(context)
        val openedToday = UsageStatsHelper.getTodayGameUsage(context).opened
        val stats = runCatching { MicaApi.fetchPlayerStats(context, force = true) }
            .onFailure { LogHelper.e("Alarm", "提醒前预热刷新游玩天数异常", it) }
            .getOrNull() ?: return
        Prefs.setProfileRefreshAt(context, System.currentTimeMillis())
        // 今天还没开过游戏 → 本次天数只可能来自历史同步，可安全抬高基线
        CheckinHelper.noteDaysFetched(context, stats.activeDays, openedToday)
        if (Prefs.isPlayDaysAboveBaseline(context, uid, stats.activeDays)) {
            // 基线已建立且天数更高（白天已登录过游戏）→ 直接判定打卡成功，避免到点误弹
            Prefs.setTodayChecked(context, true)
            Prefs.saveCheckHistory(context, Prefs.todayNaturalStr(), true)
            Prefs.setScheme2Checked(context)
            Prefs.setLastClosedDay(context, stats.activeDays, Prefs.todayStr())
            NotifyHelper.cancelRemindNotification(context)
            LogHelper.i("Alarm", "提醒前预热：天数高于基线（基线=${Prefs.todayBaseline(context)}，当前=${stats.activeDays}），打卡成功")
        } else {
            LogHelper.i("Alarm", "提醒前预热游玩天数=${stats.activeDays}（基线=${Prefs.todayBaseline(context)}，未增加）")
        }
    }

    /** 打卡判断+提醒（每日闹钟/snooze/兜底重试共用）——按当前生效方案分别判定。 */
    private fun fireRemind(context: Context) {
        val uid = Prefs.uid(context)
        val scheme = runCatching { Prefs.checkinScheme(context) }.getOrDefault(1)
        val usage = runCatching { UsageStatsHelper.getTodayGameUsage(context) }
            .getOrDefault(USAGE_FALLBACK)
        val today = Prefs.todayNaturalStr()

        // 当日打开过游戏即写入打卡历史（两方案统一）；done 仅用于判断是否还需弹提醒
        if (usage.opened) Prefs.saveCheckHistory(context, today, true)

        // 打卡判定失败时一律按「未打卡」处理：宁可多弹一次，也不能因为异常把当天提醒吞掉
        val done: Boolean = runCatching {
            if (scheme == 2) {
                // 方案2：只信「20分钟校验写的 scheme2_checked」或「提醒前预热写的 scheme2_checked」
                // 不再自己拿缓存 playDays 跟基线比，避免缓存还是旧值导致漏判
                val d = Prefs.isScheme2Checked(context)
                if (d) Prefs.setScheme2Checked(context)
                d
            } else {
                // 方案1：今日打开过游戏即打卡成功
                usage.opened
            }
        }.getOrDefault(false)

        if (done) {
            runCatching {
                Prefs.setTodayChecked(context, true)
                Prefs.saveCheckHistory(context, today, true)
            }.onFailure { LogHelper.e("Alarm", "写打卡记录失败", it) }
            NotifyHelper.cancelRemindNotification(context)
            val why = if (scheme == 2) "方案2：游玩天数已增加" else "方案1：今日已打开游戏"
            LogHelper.i("Alarm", "$why，不弹窗")
            return
        }

        // 未完成 → 提醒弹窗（不向打卡历史写 false 记录，避免污染日历/连续天数/导出）
        Prefs.setTodayChecked(context, false)
        val why = if (scheme == 2) "方案2：游玩天数未增加" else "方案1：今日未打开游戏"
        LogHelper.i("Alarm", "$why，拉起提醒弹窗")

        // 1) 先启动前台保活服务，获取"后台启动 Activity"系统豁免
        runCatching { ensureKeepAliveService(context) }
            .onFailure { LogHelper.e("Alarm", "保活服务启动失败，弹窗可能受限", it) }
        // 2) 弹窗（前台=Activity，后台/锁屏=悬浮窗或Activity）。
        //    声音/震动由真正上屏的载体（Activity onCreate / 悬浮窗渲染成功后）接管播放，
        //    不在广播层提前响，避免"窗还没出来先响一通"；载体全失败时由兜底通知的系统音兜底。
        //    弹窗链路确认失败后由 fallbackDisplay 再发通知兜底，主链路不提前发全屏通知，
        //    避免通知 fullScreenIntent 与悬浮窗叠加成两个弹窗、各自残留 snooze。
        runCatching { RemindDialogActivity.launch(context, fromAlarm = true) }
            .onFailure { LogHelper.e("Alarm", "拉起提醒弹窗失败", it) }
        // 3) 2 分钟后兜底重试（若仍未标记「今日已提醒」会自动补弹）
        runCatching { AlarmHelper.scheduleRetry(context) }
            .onFailure { LogHelper.e("Alarm", "兜底重试注册失败", it) }
    }

    /**
     * 确保前台保活服务运行（后台启动Activity豁免 + 保活）。
     * 提醒时无条件启动：Android 10+ 没有前台服务时后台拉起 Activity 会被静默拦截，
     * 这是导致"到点不弹窗"的最常见原因。常驻通知开关仅控制是否长期驻留，提醒时必须临时拉起。
     */
    private fun ensureKeepAliveService(context: Context) {
        // 走统一安全入口：后台受限会降级为通知兜底，不抛崩
        com.mh.starrailcheckin.service.KeepAliveService.start(context)
        LogHelper.i("Alarm", "已启动保活服务（获取后台启动Activity豁免）")
    }

    private companion object {
        /** UsageStats 查询异常时的兜底：按「今日未打开游戏」处理，保证提醒不会被异常吞掉。 */
        val USAGE_FALLBACK = GameUsage(false, 0L, 0)

        /** 联网长任务 WakeLock 上限（覆盖一次网络往返）。 */
        const val WAKE_LOCK_LONG_MS = 30_000L

        /** 弹窗 / IPC 短任务 WakeLock 上限。 */
        const val WAKE_LOCK_SHORT_MS = 10_000L

        /** 需要持长锁的联网类动作。 */
        val LONG_WAKE_ACTIONS = setOf(
            AlarmHelper.ACTION_PERIODIC_TASKS,
            AlarmHelper.ACTION_BASELINE_REFRESH,
            AlarmHelper.ACTION_PRE_REFRESH,
            AlarmHelper.ACTION_GAME_OPEN_CHECK
        )
    }
}
