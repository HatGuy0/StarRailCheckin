package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.service.KeepAliveAccessibilityService
import com.mh.starrailcheckin.util.AlarmHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 后台运行状态自检项：标题 + 是否正常 + 补充说明。 */
data class StatusItem(
    val label: String,
    val ok: Boolean,
    val detail: String
)

/**
 * 留存/后台运行状态汇总：供设置页「后台运行状态」面板展示。
 * 汇总闹钟、周期任务心跳、前台服务心跳、各项关键权限与下次提醒时刻。
 */
object StatusHelper {

    /** Worker/FGS 心跳超过该时长视为「很久没存活」（2 小时）。 */
    private const val STALE_MS = 2 * 60 * 60 * 1000L

    fun collect(context: Context): List<StatusItem> {
        val items = mutableListOf<StatusItem>()

        // 闹钟是否已注册
        val alarmOk = AlarmHelper.isAlarmScheduled(context)
        items += StatusItem(
            "提醒闹钟",
            alarmOk,
            if (alarmOk) "精确闹钟已注册" else "未检测到闹钟，可能已被系统清除"
        )

        // 周期任务兜底闹钟（WM 双栈）
        val periodicOk = AlarmHelper.isPeriodicTasksAlarmScheduled(context)
        items += StatusItem(
            "周期兜底闹钟",
            periodicOk,
            if (periodicOk) "签到/便笺/Cookie 兜底闹钟已注册" else "未检测到兜底闹钟，可能已被系统清除"
        )

        // 周期任务心跳（分任务展示：最近执行时刻 + 最近一次联网成功时刻）
        items += taskItem(context, "提醒兜底", Prefs.TASK_REMIND, "打卡校验 / 打开检测")
        items += taskItem(context, "便笺刷新", Prefs.TASK_STAMINA, "体力 / 派遣检查")
        items += taskItem(context, "自动签到", Prefs.TASK_SIGN, "每日签到")
        items += taskItem(context, "Cookie 检查", Prefs.TASK_COOKIE, "登录态健康检查")

        // 前台保活服务心跳（仅常驻模式有意义）
        if (Prefs.keepNotifyEnabled(context)) {
            val fgsAt = Prefs.lastFgsHeartbeatAt(context)
            val fgsOk = fgsAt > 0L && System.currentTimeMillis() - fgsAt < STALE_MS
            items += StatusItem(
                "前台保活服务",
                fgsOk,
                heartbeatText(fgsAt, "常驻保活通知")
            )
        }

        // 关键权限
        items += StatusItem(
            "电池优化白名单",
            PermissionHelper.isIgnoringBatteryOptimizations(context),
            if (PermissionHelper.isIgnoringBatteryOptimizations(context)) "未被后台省电限制"
            else "建议加入忽略电池优化名单"
        )
        items += StatusItem(
            "悬浮窗权限",
            PermissionHelper.canDrawOverlays(context),
            if (PermissionHelper.canDrawOverlays(context)) "可在后台上层弹出提醒"
            else "后台弹窗可能被拦截"
        )
        items += StatusItem(
            "精确闹钟权限",
            PermissionHelper.canScheduleExactAlarm(context),
            if (PermissionHelper.canScheduleExactAlarm(context)) "允许精确定时"
            else "到点提醒可能被推迟"
        )
        items += StatusItem(
            "通知权限",
            PermissionHelper.hasNotificationPermission(context),
            if (PermissionHelper.hasNotificationPermission(context)) "允许发送通知"
            else "提醒通知无法送达"
        )
        items += StatusItem(
            "使用记录权限",
            PermissionHelper.hasUsageAccess(context),
            if (PermissionHelper.hasUsageAccess(context)) "可统计游戏游玩时长"
            else "打卡时长统计不可用"
        )
        val a11y = PermissionHelper.isAccessibilityEnabled(context, KeepAliveAccessibilityService::class.java)
        items += StatusItem(
            "无障碍服务",
            a11y,
            if (a11y) "保活与游戏前台监听已启用"
            else "未开启无障碍，后台留存与桌面启动检测受限"
        )

        // 下次提醒时刻
        val next = nextRemindText(context)
        items += StatusItem("下次提醒", true, next)

        return items
    }

    private fun taskItem(context: Context, label: String, task: String, what: String): StatusItem {
        val now = System.currentTimeMillis()
        val beat = Prefs.workerHeartbeatAt(context, task)
        val success = Prefs.workerSuccessAt(context, task)
        val ok = beat > 0L && now - beat < STALE_MS
        val detail = buildString {
            append(heartbeatText(beat, what))
            if (success > 0L) append("；最近成功：${successText(success)}")
        }
        return StatusItem(label, ok, detail)
    }

    private fun successText(at: Long): String {
        val agoMin = (System.currentTimeMillis() - at) / 60_000L
        return when {
            agoMin < 1 -> "刚刚"
            agoMin < 60 -> "${agoMin} 分钟前"
            else -> "${agoMin / 60} 小时前"
        }
    }

    private fun heartbeatText(at: Long, what: String): String {
        if (at <= 0L) return "尚未记录 $what 心跳"
        val agoMin = (System.currentTimeMillis() - at) / 60_000L
        val ago = when {
            agoMin < 1 -> "刚刚"
            agoMin < 60 -> "${agoMin} 分钟前"
            else -> "${agoMin / 60} 小时前"
        }
        return "上次执行：$ago（$what）"
    }

    private fun nextRemindText(context: Context): String {
        val times = Prefs.remindTimes(context)
        if (times.isEmpty()) return "未配置提醒时段"
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val nextAt = times.mapNotNull { t ->
            t.split(":").let { p ->
                val h = p.getOrNull(0)?.toIntOrNull() ?: return@let null
                val m = p.getOrNull(1)?.toIntOrNull() ?: return@let null
                AlarmHelper.nextTriggerMillis(h, m)
            }
        }.minOrNull() ?: return "未配置提醒时段"
        return fmt.format(Date(nextAt))
    }
}
