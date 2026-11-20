package com.mh.starrailcheckin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.RemindWorker

/**
 * 前台保活服务
 * 作用：
 * 1. 后台启动 Activity 豁免（Android 10+ 有前台服务时允许后台启动 Activity）
 * 2. 保活防杀
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notif = buildNotification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            LogHelper.i("KeepAlive", "前台服务已前台化，后台启动Activity豁免生效")
        }.onFailure { e ->
            // startForeground 失败时服务会被系统杀死；记录日志便于排查"到点不弹窗"
            LogHelper.e("KeepAlive", "startForeground 失败，后台启动Activity豁免将失效", e)
        }
        runCatching { Prefs.setLastFgsHeartbeatAt(this, System.currentTimeMillis()) }
        runCatching { RemindWorker.schedule(this) }
            .onFailure { LogHelper.e("KeepAlive", "Worker 调度失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次拉起都刷新前台服务心跳：供 Worker / 兜底闹钟判断服务是否还活着，死亡则自愈重启
        runCatching { Prefs.setLastFgsHeartbeatAt(this, System.currentTimeMillis()) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        private fun buildNotification(context: Context): Notification {
            // 确保渠道存在
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NotifyHelper.CHANNEL_KEEP,
                    "常驻通知",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "前台服务常驻通知，用于保活防杀"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }

            return NotificationCompat.Builder(context, NotifyHelper.CHANNEL_KEEP)
                .setSmallIcon(R.drawable.ic_stat_check)
                .setContentTitle("星铁打卡小助手")
                .setContentText("正在后台运行")
                .setOngoing(true)
                .build()
        }
    }
}
