package com.mh.starrailcheckin.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 独立周期任务：Cookie 健康检查 / 自动续期。内部由 [CookieHealthMonitor] 做间隔节流与离线守卫。
 *
 * 后台加固（P0）：要求联网调度；异常返回 [Result.retry]；写分项心跳。
 */
class CookieWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Prefs.setWorkerHeartbeatAt(ctx, Prefs.TASK_COOKIE, System.currentTimeMillis())
        LogHelper.i("CookieWorker", "周期任务触发：Cookie 健康检查")
        if (!BackgroundRunGuard.tryAcquire(ctx, Prefs.TASK_COOKIE)) return Result.success()
        val failure = withContext(Dispatchers.IO) {
            runCatching { CookieHealthMonitor.checkIfDue(ctx) }.exceptionOrNull()
        }
        return if (failure != null) {
            LogHelper.e("CookieWorker", "Cookie 健康检查失败，安排退避重试", failure)
            Result.retry()
        } else {
            LogHelper.i("CookieWorker", "Cookie 健康检查完成")
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_cookie_worker"

        /**
         * WM 兜底节拍（分钟）。与业务节流默认 60min 对齐（可调 15–240），15min 唤醒多数被
         * 节流挡回，故 WM 层拉长到 60min；登录态续期及时性由 Alarm 兜底栈与联网补跑保证。
         */
        private const val INTERVAL_MIN = 60L

        // Cookie 健康检查为低优先级后台任务：等 Wi-Fi（UNMETERED）且低电量时推迟，避免移动网络/低电量空跑
        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CookieWorker>(INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            LogHelper.i("CookieWorker", "Cookie 周期任务已调度（${INTERVAL_MIN}min，等 Wi-Fi，低电量推迟）")
        }
    }
}
