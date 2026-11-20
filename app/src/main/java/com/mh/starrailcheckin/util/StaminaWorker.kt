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
 * 独立周期任务：体力 / 派遣提醒检查。内部由 [StaminaMonitor] 做间隔节流与离线守卫。
 *
 * 后台加固（P0）：
 * - 要求有可用网络才调度，避免 Doze/离线空跑占掉周期；
 * - 执行抛异常时返回 [Result.retry] 交系统退避重试，瞬时网络失败不丢本轮；
 * - 写分项心跳，供后台状态面板区分各任务存活情况。
 */
class StaminaWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()
        Prefs.setWorkerHeartbeatAt(ctx, Prefs.TASK_STAMINA, now)
        val failure = withContext(Dispatchers.IO) {
            runCatching { StaminaMonitor.check(ctx) }.exceptionOrNull()
        }
        return if (failure != null) {
            LogHelper.e("StaminaWorker", "体力检查失败，安排退避重试", failure)
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE = "starrail_stamina_worker"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<StaminaWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            LogHelper.i("StaminaWorker", "体力周期任务已调度（15min，需联网）")
        }
    }
}
