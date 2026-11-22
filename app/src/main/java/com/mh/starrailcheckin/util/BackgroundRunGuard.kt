package com.mh.starrailcheckin.util

import android.content.Context

/**
 * 跨栈执行去重守卫。
 *
 * 后台存在三条触发栈：WorkManager 周期 Worker、AlarmManager 周期兜底闹钟、网络恢复补跑。
 * 同一任务（签到 / 体力 / Cookie）在短窗口内被两条栈先后拉起时，第二栈直接跳过联网，
 * 避免对同一接口重复请求。
 *
 * 口径：
 * - 窗口取保守的 3 分钟，只消除「同轮双栈重复」，不替代各 Helper 自身的业务节流
 *   （体力默认 10min、Cookie 默认 60min、签到小时门槛 + 退避）；
 * - 窗口短于业务节流，不影响正常周期执行；
 * - 判定基于进程外持久时间戳，进程重建后仍生效。
 */
object BackgroundRunGuard {

    /** 跨栈去重窗口：3 分钟。 */
    private const val WINDOW_MS = 3 * 60_000L

    /**
     * 尝试获取某任务的本轮执行权。
     * @return true 调用方应继续执行；false 窗口内另一栈已执行，调用方应跳过本轮联网。
     */
    fun tryAcquire(context: Context, task: String): Boolean {
        val now = System.currentTimeMillis()
        val last = Prefs.lastBackgroundRunAt(context, task)
        if (last > 0L && now - last < WINDOW_MS) {
            LogHelper.i("Guard", "$task 跨栈去重：${(now - last) / 1000}s 内另一栈已执行，跳过本轮")
            return false
        }
        Prefs.setBackgroundRunAt(context, task, now)
        return true
    }
}
