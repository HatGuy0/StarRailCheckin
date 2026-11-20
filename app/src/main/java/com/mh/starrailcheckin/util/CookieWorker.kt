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
        val failure = withContext(Dispatchers.IO) {
            runCatching { CookieHealthMonitor.checkIfDue(ctx) }.exceptionOrNull()
        }
        return if (failure != null) {
            LogHelper.e("CookieWorker", "Cookie 健康检查失败，安排退避重试", failure)
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_cookie_worker"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CookieWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            LogHelper.i("CookieWorker", "Cookie 周期任务已调度（15min，需联网）")
        }
    }
}
