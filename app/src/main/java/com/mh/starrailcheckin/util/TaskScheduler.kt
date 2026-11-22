package com.mh.starrailcheckin.util

import android.content.Context

/**
 * 后台周期任务统一编排器（C-2）。
 *
 * 单一事实源：所有需要恢复 / 重排后台调度的场景入口（冷启动、回前台、开机 / 重装、
 * 保活服务自愈）只调用 [setupPeriodicTasks]，不再直接散落调用各 Worker 调度方法与
 * 闹钟自检；后续调整周期、约束、唯一名或兜底栈时只改这一处。
 *
 * 聚合内容：
 * - 四个周期 Worker：提醒自检 / 签到 / 体力派遣 / Cookie 健康；
 * - AlarmManager 周期兜底闹钟（WM 被推迟 / 杀掉时拉起进程跑联网检查）；
 * - 凌晨基准固化闹钟自检。
 */
object TaskScheduler {

    /** 注册 / 更新全部后台周期任务与兜底闹钟，幂等可重复调用。 */
    fun setupPeriodicTasks(context: Context) {
        RemindWorker.schedule(context)
    }
}
