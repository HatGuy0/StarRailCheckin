package com.mh.starrailcheckin.util

/**
 * 进程内前台状态跟踪
 * 用途：弹窗展示决策 —— 前台 → Activity 弹窗（正常交互、返回键可关闭）；
 *       后台/锁屏 → WindowManager 全屏悬浮窗弹窗（不依赖"后台启动Activity"豁免，国产ROM上最可靠）
 * 进程被杀后重建时默认视为后台（false），自动走悬浮窗弹窗路径
 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
