package com.mh.starrailcheckin.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单：国产ROM会把未加入白名单的App在后台杀掉，
 * 导致前台服务定时器/闹钟广播到点前失效。引导用户加入白名单是保活的关键一步。
 */
object BatteryHelper {

    /** 是否已加入电池优化白名单（不受后台省电限制） */
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /** 跳转系统"忽略电池优化"请求页（针对本App） */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
}
