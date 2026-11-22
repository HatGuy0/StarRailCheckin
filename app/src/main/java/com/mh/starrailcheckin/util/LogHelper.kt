package com.mh.starrailcheckin.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 运行日志：logcat + 文件双写
 * 记录：应用启动、前台服务、闹钟、检测结果、API请求与报错（设置页可一键导出）
 */
object LogHelper {
    private const val TAG = "StarRailCheckin"
    private var logDir: File? = null
    private val lock = ReentrantLock()

    private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        pruneOldLogs()
        i("Log", "日志模块初始化: ${logDir?.absolutePath}")
    }

    /** 清理 7 天前的旧日志/归档文件，避免长期累积占满存储。 */
    private fun pruneOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".log") && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    fun i(tag: String, msg: String) = log('I', tag, msg)
    fun w(tag: String, msg: String) = log('W', tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, "$tag: $msg", tr)
        log('E', tag, msg + (tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    /** 崩溃堆栈落盘：完整栈轨迹写入文件日志，供导出日志复盘（不做远程上报）。 */
    fun crash(tag: String, tr: Throwable) {
        Log.e(TAG, "$tag: 未捕获异常", tr)
        val stack = android.util.Log.getStackTraceString(tr)
        log('E', tag, "未捕获异常: ${tr.javaClass.name}: ${tr.message}\n$stack")
    }

    private fun log(level: Char, tag: String, msg: String) {
        Log.println(level.code, TAG, "$tag: $msg")
        val dir = logDir ?: return
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        val f = File(dir, "checkin_$date.log")
        lock.withLock {
            runCatching {
                FileWriter(f, true).use { it.append("$time [$level][$tag] $msg\n") }
            }.onFailure { Log.e(TAG, "log write fail", it) }
        }
        // 单文件超过2MB归档，防止无限膨胀
        if (f.length() > 2 * 1024 * 1024) {
            f.renameTo(File(dir, "checkin_${date}_archived.log"))
        }
    }

    /** 汇总所有日志为一个txt，供导出分享 */
    fun collect(context: Context): File {
        val dir = logDir ?: File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val outDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val out = File(outDir, "StarRailCheckin_logs_$stamp.txt")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "2.0"
        val sb = StringBuilder()
        sb.append("===== 星铁打卡小助手 运行日志导出 =====\n")
            .append("时间: ").append(stamp)
            .append(" | 版本: ").append(version).append("\n\n")
        dir.listFiles()?.filter { it.name.endsWith(".log") }?.sortedBy { it.name }?.forEach { f ->
            sb.append("----- ").append(f.name).append(" -----\n")
            sb.append(f.readText()).append("\n")
        }
        out.writeText(sb.toString())
        return out
    }
}
