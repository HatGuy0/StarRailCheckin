package com.mh.starrailcheckin.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 开机后按已保存的多时段配置恢复闹钟（总开关关闭则不注册）
            Intent.ACTION_BOOT_COMPLETED,
            // 应用覆盖安装/更新后，闹钟与 Worker 调度会被系统清除，需重新注册
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmHelper.registerAllAlarms(context)
                runCatching { TaskScheduler.setupPeriodicTasks(context) }
                runCatching { AlarmHelper.ensurePeriodicTasksAlarm(context) }
                runCatching { AlarmHelper.ensureBaselineAlarm(context) }
                // 不在开机/安装时直接拉起常驻前台服务：Android 15+ 禁止从 BOOT_COMPLETED 拉起
                // dataSync 前台服务；常驻兜底改由首个 WM/闹钟回调按开关自愈，此处只重排调度。
            }
            // 时区/系统时间变更：提醒时刻可能偏移，仅重排闹钟
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                runCatching { AlarmHelper.registerAllAlarms(context) }
            }
        }
    }
}
