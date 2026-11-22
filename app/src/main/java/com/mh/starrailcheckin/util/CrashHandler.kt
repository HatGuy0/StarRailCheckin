package com.mh.starrailcheckin.util

import android.os.Process
import kotlin.system.exitProcess

/**
 * 全局未捕获异常兜底。
 *
 * 流程：先把线程、时间与完整堆栈写入本地文件日志（随「导出日志」一并带出复盘），
 * 再转交系统默认 handler 走原生崩溃流程。不做远程上报。
 *
 * @param default 注册前已有的默认 handler（通常为系统 RuntimeInit 安装的实现），可能为 null。
 */
internal class CrashHandler(
    private val default: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 落盘必须包一层，避免崩溃处理流程自身再抛异常导致无法转交系统。
        runCatching {
            LogHelper.crash("Crash", throwable)
        }
        default?.uncaughtException(thread, throwable)
        // 清理进程，避免残留带异常的主线程反复拉起崩溃。
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
