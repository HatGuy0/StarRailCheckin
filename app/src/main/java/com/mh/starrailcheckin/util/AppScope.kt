package com.mh.starrailcheckin.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级后台协程作用域。
 *
 * 替代历史遗留的裸 `Thread {}.start()`：
 * - SupervisorJob 保证单个子任务失败不取消兄弟任务；
 * - IO 调度器承载后台联网 / 文件 / 统计任务；
 * - 进程级生命周期，不绑定某个 Activity（后台补判、网络恢复补跑等离开页面后仍需执行）。
 *
 * 注意：BroadcastReceiver 内不使用本作用域——接收器需配合 `goAsync()` 在任务完成后
 * 显式 `finish()`，故在接收器内建局部结构化作用域。
 */
object AppScope {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.IO)
}
