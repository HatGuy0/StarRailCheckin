package com.mh.starrailcheckin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner

/**
 * 导航事件调度器宿主：在独立 Activity 的 setContent 根部包裹，
 * 为该页面内 Miuix 弹窗的 NavigationBackHandler 提供根 dispatcher owner，
 * 避免取空 owner 抛异常闪退。parent = null 表示在此创建根 dispatcher。
 */
@Composable
fun NavEventOwnerHost(content: @Composable () -> Unit) {
    val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
        content()
    }
}
