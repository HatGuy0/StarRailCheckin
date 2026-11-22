package com.mh.starrailcheckin.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mh.starrailcheckin.MainActivity
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.RemindDialogActivity

/**
 * 通知管理：常驻保活通知 + 打卡提醒通知（无悬浮窗权限时的回退通道）
 */
object NotifyHelper {
    const val CHANNEL_KEEP = "starrail_keep_alive"
    const val CHANNEL_REMIND = "starrail_remind"
    const val CHANNEL_SIGN = "starrail_sign_result"
    const val CHANNEL_STAMINA = "starrail_stamina"
    const val CHANNEL_COOKIE_HEALTH = "starrail_cookie_health"
    const val NOTIF_KEEP = 1001
    const val NOTIF_REMIND = 1002
    const val NOTIF_SIGN = 1003
    const val NOTIF_STAMINA = 1004
    const val NOTIF_COOKIE_HEALTH = 1005

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEP, "常驻通知", NotificationManager.IMPORTANCE_LOW).apply {
                description = "前台服务常驻通知，用于保活防杀"
                setShowBadge(false)
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMIND, "打卡提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "今日未打卡时的提醒"
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SIGN, "签到结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "米游社每日签到成功/失败通知"
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STAMINA, "体力与派遣", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "开拓力达到阈值/回满、派遣完成提醒"
            })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COOKIE_HEALTH, "Cookie 健康", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cookie 失效与自动续期失败提醒"
            })
    }

    /** 仅用于已确认登录态失效；风控和普通网络错误不得调用。 */
    fun showCookieHealthNotification(context: Context, detail: String? = null) {
        ensureChannels(context)
        val base = "Cookie 可能已失效，请重新登录"
        val text = detail?.takeIf { it.isNotBlank() }?.let { "$base\n$it" } ?: base
        val pi = PendingIntent.getActivity(
            context, 6,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_COOKIE_HEALTH)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("Cookie 失效提醒")
            .setContentText(base)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_COOKIE_HEALTH, n) }
            .onFailure { LogHelper.e("Notify", "Cookie 健康通知发送失败", it) }
    }

    fun cancelCookieHealthNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_COOKIE_HEALTH) }
            .onFailure { LogHelper.e("Notify", "取消 Cookie 健康通知失败", it) }
    }

    /** 体力达到阈值、体力回满或派遣完成提醒。 */
    fun showStaminaNotification(context: Context, title: String, text: String) {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 5,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_STAMINA)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_STAMINA, n) }
            .onFailure { LogHelper.e("Notify", "体力提醒通知发送失败", it) }
    }

    /** 签到结果通知（手动签到与每日自动签到共用）。 */
    fun showSignResultNotification(
        context: Context,
        success: Boolean,
        accountLabel: String,
        message: String
    ) {
        ensureChannels(context)
        val who = accountLabel.ifBlank { "当前账号" }
        val pi = PendingIntent.getActivity(
            context, 4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SIGN)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(if (success) "签到成功 · $who" else "签到失败 · $who")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        val id = NOTIF_SIGN + (who.hashCode() and 0x3FF)
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
            .onFailure { LogHelper.e("Notify", "签到结果通知发送失败", it) }
    }

    /** 无悬浮窗权限/弹窗拉起失败时的回退提醒（全屏Intent唤醒弹窗） */
    fun showRemindNotification(context: Context, fromAlarm: Boolean = false, manualTest: Boolean = false) {
        ensureChannels(context)
        val contentPi = PendingIntent.getActivity(
            context, 1,
            Intent(context, RemindDialogActivity::class.java)
                .putExtra(RemindDialogActivity.EXTRA_FROM_ALARM, fromAlarm)
                .putExtra(RemindDialogActivity.EXTRA_MANUAL_TEST, manualTest),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fullPi = PendingIntent.getActivity(
            context, 2,
            Intent(context, RemindDialogActivity::class.java)
                .putExtra(RemindDialogActivity.EXTRA_FROM_ALARM, fromAlarm)
                .putExtra(RemindDialogActivity.EXTRA_MANUAL_TEST, manualTest),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(context.getString(R.string.remind_notification_title))
            .setContentText(context.getString(R.string.remind_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setFullScreenIntent(fullPi, true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_REMIND, n) }
            .onFailure { LogHelper.e("Notify", "提醒通知发送失败", it) }
    }

    /** 取消残留的提醒通知（今日已打卡 / 闸门判定不应提醒时调用，避免旧通知全屏拉起弹窗） */
    fun cancelRemindNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIF_REMIND)
        }.onFailure { LogHelper.e("Notify", "取消提醒通知失败", it) }
    }

    /** 前台服务常驻通知 */
    fun keepNotification(context: Context): android.app.Notification {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 3, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_KEEP)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(context.getString(R.string.keep_alive_title))
            .setContentText(context.getString(R.string.keep_alive_text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** 显示测试通知 */
    fun showTestNotification(context: Context, title: String, text: String) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(999, n) }
    }
}
