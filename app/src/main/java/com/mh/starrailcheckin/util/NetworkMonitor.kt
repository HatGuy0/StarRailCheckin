package com.mh.starrailcheckin.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mh.starrailcheckin.util.CookieHealthMonitor
import com.mh.starrailcheckin.util.SignHelper
import com.mh.starrailcheckin.util.StaminaMonitor

/**
 * 网络恢复监听：离线期间错过的签到 / 体力 / Cookie 检查，在网络重新可用后
 * 立即补跑一次（各 Helper 内部仍有节流与离线守卫，不会重复打扰）。
 * 仅在进程存活期间注册；进程重建后由周期任务兜底。
 */
object NetworkMonitor {

    @Volatile
    private var lastRestoreRunAt = 0L
    private const val RESTORE_THROTTLE_MS = 60_000L

    fun register(context: Context) {
        val appCtx = context.applicationContext
        val cm = runCatching {
            appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }.getOrNull() ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastRestoreRunAt < RESTORE_THROTTLE_MS) return
                lastRestoreRunAt = now
                Thread {
                    runCatching { SignHelper.autoSignIfDue(appCtx) }
                        .onFailure { LogHelper.e("NetworkMonitor", "网络恢复补跑签到失败", it) }
                    runCatching { StaminaMonitor.check(appCtx) }
                        .onFailure { LogHelper.e("NetworkMonitor", "网络恢复补跑体力失败", it) }
                    runCatching { CookieHealthMonitor.checkIfDue(appCtx) }
                        .onFailure { LogHelper.e("NetworkMonitor", "网络恢复补跑 Cookie 失败", it) }
                }.start()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }
}
