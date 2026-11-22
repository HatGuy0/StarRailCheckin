package com.mh.starrailcheckin.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 打卡相关周期任务（每 15 分钟）：
 * 由系统托管调度，App 进程被国产 ROM 杀掉后仍会被拉起执行。
 * 本 Worker 只做打卡方案2 校验、打开游戏检测、历史回填与闹钟自愈；
 * 【不做弹窗 / 声音 / 保活补弹】——弹窗只由预设提醒时段的闹钟触发，
 * 避免本次已提醒后被周期兜底追着重复弹窗。
 * 自动签到 / 体力派遣 / Cookie 健康检查在独立的 [SignWorker] / [StaminaWorker] / [CookieWorker]。
 */
class RemindWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        Prefs.setLastWorkerHeartbeatAt(ctx, now)
        Prefs.setWorkerHeartbeatAt(ctx, Prefs.TASK_REMIND, now)
        LogHelper.i("Worker", "周期任务触发：打卡自检/打开游戏检测")

        val failure = withContext(Dispatchers.IO) {
            runCatching {
                // 周期内顺带自检闹钟：丢失则自动重排，避免闹钟被系统清除后只剩 15 分钟兜底
                runCatching { AlarmHelper.ensureAlarmScheduled(ctx) }
                    .onFailure { LogHelper.e("Worker", "闹钟自检重排失败", it) }
                // 按系统使用记录回填最近几天的打卡历史
                runCatching { CheckinHelper.reconcileRecentHistory(ctx) }
                    .onFailure { LogHelper.e("Worker", "打卡历史回填失败", it) }
                // 凌晨基准三次刷新全失败时的白天兜底：未开游戏前提下用缓存天数补建基准
                runCatching { CheckinHelper.establishBaselineFromCacheIfNeeded(ctx) }
                    .onFailure { LogHelper.e("Worker", "缓存基准补建失败", it) }

                // ========== 打卡双方案 ==========
                val scheme = Prefs.checkinScheme(ctx)
                if (scheme == 2) Prefs.sanitizeScheme2Today(ctx)

                val lastOpen = UsageStatsHelper.lastGameForegroundAt(ctx)
                val prevOpenAt = Prefs.lastGameOpenAt(ctx)
                val hasNewOpen = lastOpen > 0L && lastOpen > prevOpenAt
                if (hasNewOpen) {
                    Prefs.setLastGameOpenAt(ctx, lastOpen)
                    if (scheme == 2) {
                        CheckinHelper.noteGameOpened(ctx)
                        val dueAt = lastOpen + AlarmHelper.GAME_OPEN_CHECK_DELAY_MS
                        val delay = (dueAt - System.currentTimeMillis()).coerceAtLeast(10_000L)
                        AlarmHelper.scheduleGameOpenCheck(ctx, delay)
                        LogHelper.i("Worker", "方案2：检测到打开游戏，已安排 20 分钟后校验（基线=${Prefs.todayBaseline(ctx)}）")
                    } else {
                        LogHelper.i("Worker", "方案1：检测到打开游戏，打开即视为打卡")
                    }
                }

                // 兜底：今天 scheme==2 且已打开游戏，但 dueAt==0 且未校验 → 立即 force 刷新校验
                if (scheme == 2) {
                    val openedToday = UsageStatsHelper.getTodayGameUsage(ctx).opened
                    val dueAt = Prefs.gameOpenCheckDueAt(ctx)
                    val alreadyChecked = Prefs.isScheme2Checked(ctx)
                    if (openedToday && !alreadyChecked && dueAt == 0L) {
                        LogHelper.i("Worker", "方案2：今天已打开游戏但无待校验闹钟，立即 force 刷新校验")
                        CheckinHelper.verifyGameOpen(ctx)
                    } else if (dueAt > 0L && System.currentTimeMillis() >= dueAt) {
                        LogHelper.i("Worker", "方案2：20分钟校验到点，Worker 兜底执行")
                        CheckinHelper.verifyGameOpen(ctx)
                    }
                }

                val usage = UsageStatsHelper.getTodayGameUsage(ctx)
                // 当日打开过游戏即写入打卡历史（两方案统一）
                if (usage.opened) Prefs.saveCheckHistory(ctx, Prefs.todayNaturalStr(), true)
                val done = if (scheme == 2) Prefs.isScheme2Checked(ctx) else usage.opened
                if (done) {
                    if (scheme == 2) Prefs.setScheme2Checked(ctx)
                    Prefs.setTodayChecked(ctx, true)
                    Prefs.saveCheckHistory(ctx, Prefs.todayNaturalStr(), true)
                    NotifyHelper.cancelRemindNotification(ctx)
                }
                // 口径：只在预设提醒时段到点由闹钟弹窗；本周期任务不做弹窗 / 声音 / 保活补弹，
                // 避免本次已提醒后被 15 分钟周期兜底追着重复弹窗。
                // 保活服务死亡自愈：开关开启但前台服务心跳过期时重新拉起
                KeepAliveHelper.ensureIfEnabled(ctx)
            }.exceptionOrNull()
        }

        return if (failure != null) {
            LogHelper.e("Worker", "打卡自检周期任务失败，安排退避重试", failure)
            Result.retry()
        } else {
            LogHelper.i("Worker", "打卡自检周期任务完成")
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_remind_worker"

        /**
         * WM 节拍（分钟）。Remind 是提醒主路径的自愈兜底（闹钟自检重排、游戏打开检测、
         * 历史回填、缓存基准补建），保持 15min 高频，不随低频联网任务拉长。
         */
        private const val INTERVAL_MIN = 15L

        /** 注册/更新全部后台周期任务（提醒 + 签到 + 体力 + Cookie）。 */
        fun schedule(context: Context) {
            val remindReq = PeriodicWorkRequestBuilder<RemindWorker>(INTERVAL_MIN, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, remindReq
            )
            SignWorker.schedule(context)
            StaminaWorker.schedule(context)
            CookieWorker.schedule(context)
            // 闹钟双栈兜底：WM 被系统推迟/杀掉时，周期闹钟仍可拉起进程跑联网检查
            AlarmHelper.ensurePeriodicTasksAlarm(context)
            // 凌晨基准固化闹钟自检（3:50/3:55/3:59）
            AlarmHelper.ensureBaselineAlarm(context)
            LogHelper.i("Worker", "全部后台周期任务已调度（Remind/体力 15min，签到/Cookie 60min）")
        }
    }
}
