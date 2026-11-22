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
import com.mh.starrailcheckin.util.TaskScheduler

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
        runCatching { TaskScheduler.setupPeriodicTasks(this) }
            .onFailure { LogHelper.e("KeepAlive", "Worker 调度失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 显式收尾动作：临时豁免用完后由弹窗页回调触发，移除常驻通知并停止自身
        if (intent?.action == ACTION_STOP) {
            releaseSelf()
            return START_NOT_STICKY
        }
        // 每次拉起都刷新前台服务心跳：供 Worker / 兜底闹钟判断服务是否还活着，死亡则自愈重启
        runCatching { Prefs.setLastFgsHeartbeatAt(this, System.currentTimeMillis()) }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 主动移除前台通知并停止自身；幂等，可被重复调用。 */
    private fun releaseSelf() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }.onFailure { LogHelper.e("KeepAlive", "stopForeground 失败", it) }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        // 兜底：无论被谁停止，都确保前台通知被移除，避免通知残留占用后台
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    /** 短时前台服务超时（API 34+）：主动收尾，避免系统判超时 ANR。 */
    override fun onTimeout(startId: Int) {
        LogHelper.w("KeepAlive", "前台服务超时，主动收尾")
        releaseSelf()
        super.onTimeout(startId)
    }

    companion object {
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.mh.starrailcheckin.KEEPALIVE_STOP"

        /**
         * 安全启动前台服务。后台受限（API31+ ForegroundServiceStartNotAllowedException）时
         * 不再抛崩，降级为提醒通知兜底；其他启动错误照常记异常栈。
         */
        fun start(context: Context) {
            val i = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                val limited = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    t.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                if (limited) {
                    LogHelper.w("KeepAlive", "后台启动前台服务被系统限制，降级为通知兜底：${t.message}")
                    runCatching { NotifyHelper.showRemindNotification(context) }
                } else {
                    LogHelper.e("KeepAlive", "启动前台服务失败", t)
                }
            }
        }

        /** 通知服务主动移除前台通知并停止自身；服务不在时为 no-op。 */
        fun stop(context: Context) {
            val i = Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
                .onFailure { LogHelper.e("KeepAlive", "发送收尾动作失败", it) }
            // 兜底：若服务已被回收，stopService 直接移除残留
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
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
