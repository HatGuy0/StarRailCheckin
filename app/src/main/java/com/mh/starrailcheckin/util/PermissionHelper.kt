package com.mh.starrailcheckin.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 权限入口统一封装：无障碍、应用使用记录、自启动、后台无限制、状态栏常驻通知、悬浮窗、精确闹钟 */
object PermissionHelper {

    // ---------- 悬浮窗（SYSTEM_ALERT_WINDOW） ----------
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
    fun openOverlaySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { LogHelper.e("Permission", "打开悬浮窗设置失败", it) }
    }

    // ---------- 应用使用记录 ----------
    fun hasUsageAccess(context: Context): Boolean = UsageStatsHelper.hasUsageAccess(context)
    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { LogHelper.e("Permission", "打开使用记录设置失败", it) }
    }

    // ---------- 无障碍 ----------
    fun isAccessibilityEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expected = "${context.packageName}/${serviceClass.name}"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }
    @Suppress("unused")
    fun openAccessibilitySettings(context: Context, serviceClass: Class<*>) {
        runCatching {
            // 打开无障碍设置页，用户手动找到「星铁打卡小助手」服务开启（无通用API可直接跳指定服务）
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { LogHelper.e("Permission", "打开无障碍设置失败", it) }
    }

    // ---------- 后台无限制（电池优化） ----------
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
    fun openBatteryOptimizationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { LogHelper.e("Permission", "打开电池优化设置失败", it) }
    }

    // ---------- 自启动（国产ROM无统一API，跳到应用详情引导用户进入自启动管理） ----------
    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { LogHelper.e("Permission", "打开应用详情失败", it) }
    }

    // ---------- 通知权限（Android13+） ----------
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---------- 精确闹钟（Android12+） ----------
    fun canScheduleExactAlarm(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return am.canScheduleExactAlarms()
    }
    fun openExactAlarmSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { LogHelper.e("Permission", "打开精确闹钟设置失败", it) }
    }
}
