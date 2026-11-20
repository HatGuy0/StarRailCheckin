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
        val failure = withContext(Dispatchers.IO) {
            runCatching { SignHelper.autoSignIfDue(ctx) }.exceptionOrNull()
        }
        return if (failure != null) {
            LogHelper.e("SignWorker", "自动签到检查失败，安排退避重试", failure)
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_sign_worker"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SignWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            LogHelper.i("SignWorker", "签到周期任务已调度（15min，需联网）")
        }
    }
}
