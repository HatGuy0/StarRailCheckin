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
 * 独立周期任务：每日自动签到（与提醒/体力/Cookie 检查拆分，单个失败不拖累其他任务）。
 * 内部由 [SignHelper] 做小时门槛、退避重试、离线守卫与节流。
 *
 * 后台加固（P0）：要求联网调度；异常返回 [Result.retry]；写分项心跳。
 */
class SignWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Prefs.setWorkerHeartbeatAt(ctx, Prefs.TASK_SIGN, System.currentTimeMillis())
        LogHelper.i("SignWorker", "周期任务触发：签到检查")
        if (!BackgroundRunGuard.tryAcquire(ctx, Prefs.TASK_SIGN)) return Result.success()
        val failure = withContext(Dispatchers.IO) {
            runCatching { SignHelper.autoSignIfDue(ctx) }.exceptionOrNull()
        }
        return if (failure != null) {
            LogHelper.e("SignWorker", "自动签到检查失败，安排退避重试", failure)
            Result.retry()
        } else {
            LogHelper.i("SignWorker", "签到检查完成")
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_sign_worker"

        /**
         * WM 兜底节拍（分钟）。签到业务每日一次且有小时门槛 + 退避，多数 15min 唤醒会被
         * 业务节流挡回，故 WM 层拉长到 60min 减少空唤醒；到点可靠性由 Alarm 周期兜底栈
         * （15min）与 NetworkMonitor 联网补跑保证，WM 仅作进程被杀后的系统托管兜底。
         */
        private const val INTERVAL_MIN = 60L

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SignWorker>(INTERVAL_MIN, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            LogHelper.i("SignWorker", "签到周期任务已调度（${INTERVAL_MIN}min，需联网）")
        }
    }
}
