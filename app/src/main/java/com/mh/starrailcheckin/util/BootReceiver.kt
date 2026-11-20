package com.mh.starrailcheckin.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mh.starrailcheckin.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 开机后按已保存的多时段配置恢复闹钟（总开关关闭则不注册）
            Intent.ACTION_BOOT_COMPLETED,
            // 应用覆盖安装/更新后，闹钟与 Worker 调度会被系统清除，需重新注册并恢复保活
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                AlarmHelper.registerAllAlarms(context)
                runCatching { com.mh.starrailcheckin.util.RemindWorker.schedule(context) }
                runCatching { AlarmHelper.ensurePeriodicTasksAlarm(context) }
                runCatching { AlarmHelper.ensureBaselineAlarm(context) }
                if (Prefs.keepNotifyEnabled(context)) {
                    runCatching { KeepAliveService.start(context) }
                }
            }
        }
    }
}
