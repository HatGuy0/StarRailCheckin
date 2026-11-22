package com.mh.starrailcheckin

import android.app.Application
import com.mh.starrailcheckin.util.CrashHandler
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.NetworkMonitor
import com.mh.starrailcheckin.util.NotifyHelper

/**
 * Application 类
 */
class StarRailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志双写（否则日志目录为空，导出日志只有表头）
        LogHelper.init(this)
        // 全局崩溃兜底：先把完整堆栈写入本地日志，再转交系统默认崩溃流程（不做远程上报）
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previousHandler))
        // 统一创建通知渠道（与 NotifyHelper 使用的渠道 ID 保持一致，避免孤儿渠道）
        NotifyHelper.ensureChannels(this)
        // 注册网络恢复监听：离线错过的检查在联网后补跑一次
        NetworkMonitor.register(this)
    }
}
