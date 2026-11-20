package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.service.KeepAliveService

/**
 * 前台保活服务自愈：常驻开关开启，但服务心跳已过期（进程/服务被系统杀掉）时重新拉起。
 * 由周期 Worker 与周期兜底闹钟在每轮执行时调用，幂等——服务仍在跑时重复 start 只会走 onStartCommand 刷新心跳。
 */
object KeepAliveHelper {

    /** 前台服务心跳超过该时长视为已死亡（10 分钟）。 */
    private const val STALE_MS = 10 * 60_000L

    fun ensureIfEnabled(context: Context) {
        if (!Prefs.keepNotifyEnabled(context)) return
        val beat = Prefs.lastFgsHeartbeatAt(context)
        val stale = beat <= 0L || System.currentTimeMillis() - beat > STALE_MS
        if (stale) {
            LogHelper.i("KeepAliveHelper", "保活服务心跳过期，自愈重新拉起")
            runCatching { KeepAliveService.start(context) }
                .onFailure { LogHelper.e("KeepAliveHelper", "保活服务自愈拉起失败", it) }
        }
    }
}
