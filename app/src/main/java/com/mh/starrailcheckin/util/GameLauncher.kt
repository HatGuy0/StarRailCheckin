package com.mh.starrailcheckin.util

import android.content.Context
import android.content.Intent
import com.mh.starrailcheckin.network.MicaApi
import kotlinx.coroutines.launch

/** 一键启动星穹铁道（根据云游戏开关切换包名） */
object GameLauncher {
    fun isGameInstalled(context: Context): Boolean {
        return Prefs.gamePackages(context).any { pkg ->
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    fun launchGame(context: Context): Boolean {
        for (pkg in Prefs.gamePackages(context)) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    context.startActivity(intent)
                    LogHelper.i("Game", "已启动游戏: $pkg")
                    // 方案2：启动游戏后立刻记录"打开前"天数，并安排 20 分钟后强制刷新校验
                    if (Prefs.checkinScheme(context) == 2) {
                        triggerGameOpenCheck(context)
                    }
                    return true
                }.onFailure { LogHelper.e("Game", "启动游戏失败: $pkg", it) }
            }
        }
        LogHelper.w("Game", "未安装" + Prefs.gameDisplayName(context))
        return false
    }

    /**
     * 主动触发方案2 的游戏打开校验流程（绕过 UsageStats 事件滞后）：
     * - 立刻 force 刷新一次拿到"打开前"天数作为基线；
     * - 安排 20 分钟后的 force 刷新校验，天数 +1 才算打卡成功。
     */
    fun triggerGameOpenCheck(context: Context) {
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        Prefs.setLastGameOpenAt(ctx, now)
        val dueAt = now + AlarmHelper.GAME_OPEN_CHECK_DELAY_MS
        Prefs.setGameOpenCheckDueAt(ctx, dueAt)
        AlarmHelper.scheduleGameOpenCheck(ctx, AlarmHelper.GAME_OPEN_CHECK_DELAY_MS)
        // 立刻发起一次 force 刷新（在 IO 线程），拿到最新"打开前"天数
        AppScope.scope.launch {
            runCatching { MicaApi.fetchPlayerStats(ctx, force = true) }
                .onSuccess { stats ->
                    Prefs.setGameOpenBaseline(ctx, stats.activeDays)
                    if (Prefs.todayBaseline(ctx) <= 0) {
                        Prefs.setTodayBaseline(ctx, stats.activeDays)
                        LogHelper.i("Game", "启动游戏，今日基线=${stats.activeDays}")
                    } else {
                        LogHelper.i("Game", "启动游戏，打开前天数=${stats.activeDays}（今日基线已有）")
                    }
                }
                .onFailure { LogHelper.e("Game", "启动游戏时 force 刷新天数失败，仍会安排 20 分钟校验", it) }
        }
    }
}
