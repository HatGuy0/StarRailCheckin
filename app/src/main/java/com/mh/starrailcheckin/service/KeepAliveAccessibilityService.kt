package com.mh.starrailcheckin.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.GameLauncher
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.UsageStatsHelper

/**
 * 无障碍服务（保活 + 游戏打开检测）：
 * 1. 常驻提升后台存活率；
 * 2. 监听窗口切换事件，识别从桌面直接启动星穹铁道（官服/B服/云游戏任一），
 *    作为 UsageStats 事件滞后 / 无使用记录权限时的兜底检测层：
 *    - 方案2：记录打开前天数基线并安排 20 分钟后强制刷新校验；
 *    - 方案1：打开即写入打卡历史。
 * 不读取任何界面内容（canRetrieveWindowContent=false）。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        /** 同一游戏窗口事件的内存防抖窗口：避免游戏内反复切页重复触发校验。 */
        private const val DEBOUNCE_MS = 30_000L
    }

    @Volatile
    private var lastGameEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!UsageStatsHelper.isGamePackage(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastGameEventAt < DEBOUNCE_MS) return
        lastGameEventAt = now

        val ctx = applicationContext
        Thread {
            runCatching {
                if (Prefs.checkinScheme(ctx) == 2) {
                    CheckinHelper.noteGameOpened(ctx)
                    GameLauncher.triggerGameOpenCheck(ctx)
                    LogHelper.i("Accessibility", "检测到游戏窗口（$pkg），已安排 20 分钟后校验")
                } else {
                    Prefs.saveCheckHistory(ctx, Prefs.todayNaturalStr(), true)
                    LogHelper.i("Accessibility", "检测到游戏窗口（$pkg），方案1 已记打卡历史")
                }
            }.onFailure { LogHelper.e("Accessibility", "游戏窗口事件处理失败", it) }
        }.start()
    }

    override fun onInterrupt() {
        // 不需要处理
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 服务被解绑后重启
        val restartIntent = android.content.Intent(this, KeepAliveAccessibilityService::class.java)
        startService(restartIntent)
        return super.onUnbind(intent)
    }
}
