package com.mh.starrailcheckin.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 联网状态判断：后台任务发起请求前先检查，离线/未验证网络直接跳过本轮，
 * 不写「已尝试/已检查」节流标记，避免把当天任务封死。
 * 蜂窝与 WiFi 均允许（不做计费网络限制）。
 */
object NetworkHelper {

    /** 当前是否有可联网的活动网络；系统服务不可用时放行（不阻断原有逻辑）。 */
    fun isOnline(context: Context): Boolean {
        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull() ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
