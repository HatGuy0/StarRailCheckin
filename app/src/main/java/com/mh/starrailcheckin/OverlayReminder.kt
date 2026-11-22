package com.mh.starrailcheckin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.mh.starrailcheckin.util.BitmapSampler
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.platform.ComposeView
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.util.ViewTreeOwners
import com.mh.starrailcheckin.ui.remind.RemindGlassDialog
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.AlarmHelper
import com.mh.starrailcheckin.util.GameLauncher
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.PermissionHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.RemindGate
import com.mh.starrailcheckin.util.RemindSound
import java.lang.ref.WeakReference

/**
 * 悬浮窗弹窗（WindowManager.TYPE_APPLICATION_OVERLAY，依赖 SYSTEM_ALERT_WINDOW 权限）
 * 用途：后台/锁屏时的主弹窗通道 —— 真正的"全屏/半屏弹窗"，不是通知；
 *       不依赖"后台启动Activity"豁免，有悬浮窗权限即可在锁屏/后台稳定展示。
 *
 * UI：与 Activity 弹窗复用同一套 Compose Miuix 组件 [RemindGlassDialog]，
 *      卡片背景 / 主按钮配色 / 文案完全一致；声音 / 震动 / 亮屏 / snooze 由本载体负责。
 *
 * 【到点不弹窗修复要点】
 * 1. 「今日已提醒」标记改为**确认渲染出画面后**才写；渲染失败立即回退 Activity，
 *    不再出现"addView 成功但一片空白，却把当天标记成已提醒 → 之后再也不补弹"。
 * 2. 回退链：悬浮窗 → 后台 Activity（有悬浮窗权限即具备后台启动豁免）→ 高优先级通知。
 */
object OverlayReminder {

    private var viewRef: WeakReference<View>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissTask: Runnable? = null
    private var settleTask: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    /** 防止可见性校验失败时 onFail 被重复回调多次（轮询多阶段 + 重复 addView 场景） */
    private var failFired = false
    /** 悬浮窗 ComposeView 绑定的生命周期 / 状态宿主，随视图销毁而释放 */
    private var host: OverlayHost? = null

    /** 裸 ComposeView 挂 WindowManager 时缺少 Activity 提供的 owner，需自建同时实现两个接口并绑定。 */
    private class OverlayHost : LifecycleOwner, SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            // 无恢复状态可提供，传 null；必须 performRestore 后 registry 才可用
            controller.performRestore(null)
        }
    }

    /**
     * @param onFail 悬浮窗没能真正显示出来时的回退入口（由调用方决定改用 Activity / 通知）
     * @return 是否成功 addView。真正的"是否显示成功"以 onFail 未被调用为准。
     */
    @SuppressLint("InflateParams")
    @Suppress("DEPRECATION") // 悬浮窗锁屏显示/点亮屏幕仍需 FLAG_SHOW_WHEN_LOCKED/TURN_SCREEN_ON，Window 级 API 不适用悬浮窗
    fun show(
        context: Context,
        fromAlarm: Boolean,
        manualTest: Boolean = false,
        onFail: (() -> Unit)? = null
    ): Boolean {
        val appCtx = context.applicationContext
        removeExisting(context.applicationContext)

        // 兜底闸门：自动链路未到点 / 已打卡 / 总开关关闭时不展示；手动测试跳过校验
        if (!manualTest && !RemindGate.shouldShowRemind(appCtx)) {
            LogHelper.w("Overlay", "闸门拦截：未到点/已打卡/总开关关闭，不展示悬浮窗")
            NotifyHelper.cancelRemindNotification(appCtx)
            return false
        }

        if (!PermissionHelper.canDrawOverlays(appCtx)) {
            LogHelper.w("Overlay", "无悬浮窗权限，无法展示悬浮窗弹窗")
            return false
        }

        // 声音/震动不在开窗前提前播放：等下方可见性校验确认画面真正渲染出来后再接管，
        // 避免"窗还没出来先响一通"；窗口失败则不响，由回退链路的通知系统音兜底。

        // 文案：设置页留空沿用默认 strings，非空覆盖标题/正文（与主弹窗一致）
        val title = Prefs.remindTitle(appCtx) ?: appCtx.getString(R.string.remind_title)
        val message = Prefs.remindMessage(appCtx) ?: appCtx.getString(R.string.remind_message)
        val launchLabel = "打开" + Prefs.gameDisplayName(appCtx)
        val background = loadCardBackground(appCtx)

        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val kg = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val view = ComposeView(appCtx)
        // 裸 ComposeView 挂 WindowManager 不会自动取 Activity 的 owner，必须显式绑定，
        // 否则 Compose 不组合、视图 0×0，悬浮窗看起来"没弹出来"。
        val overlayHost = OverlayHost()
        ViewTreeOwners.setLifecycleOwner(view, overlayHost)
        ViewTreeOwners.setSavedStateRegistryOwner(view, overlayHost)
        this.host = overlayHost

        view.setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    RemindGlassDialog(
                        title = title,
                        message = message,
                        launchLabel = launchLabel,
                        background = background,
                        onLaunch = {
                            LogHelper.i("Overlay", "用户点击打开游戏")
                            if (!manualTest) Prefs.setTodayReminded(appCtx, true)
                            runCatching { GameLauncher.launchGame(appCtx) }
                            dismiss(wm, view, snooze = false)
                        },
                        onSnooze = {
                            LogHelper.i("Overlay", "用户选择稍后提醒")
                            if (!manualTest) Prefs.setTodayReminded(appCtx, true)
                            dismiss(wm, view, snooze = true)
                        },
                        onClose = {
                            LogHelper.i("Overlay", "用户关闭弹窗（我知道）")
                            if (!manualTest) Prefs.setTodayReminded(appCtx, true)
                            dismiss(wm, view, snooze = false)
                        }
                    )
                }
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val added = runCatching { wm.addView(view, lp) }.isSuccess
        if (!added) {
            LogHelper.e("Overlay", "悬浮窗弹窗添加失败，回退")
            releaseOwners()
            onFail?.invoke()
            return false
        }
        overlayHost.registry.currentState = Lifecycle.State.RESUMED

        viewRef = WeakReference(view)
        failFired = false

        // 展示校验：冷进程/后台唤醒时 Compose 首帧可能很慢，过短的窗口容易把尚未布局完成的
        // 视图误判为 0 尺寸而拆掉 → 分 500 / 1200 / 2000ms 三阶段轮询，任一阶段确认附着且
        // 有尺寸即视为展示成功；直到 2s 仍无画面才回退 Activity/通知，并记录每阶段状态便于排查。
        val stages = longArrayOf(500L, 1200L, 2000L)
        var stage = 0
        val settle = object : Runnable {
            override fun run() {
                val attached = runCatching { view.isAttachedToWindow }.getOrDefault(false)
                val shown = runCatching { view.isShown }.getOrDefault(false)
                val w = runCatching { view.width }.getOrDefault(0)
                val h = runCatching { view.height }.getOrDefault(0)
                val locked = runCatching { kg.isKeyguardLocked }.getOrDefault(false)
                val off = runCatching { !pm.isInteractive }.getOrDefault(false)
                LogHelper.i("Overlay", "可见性校验 stage=${stage + 1}/3 attached=$attached shown=$shown size=${w}x$h locked=$locked off=$off")
                if (attached && w > 0 && h > 0) {
                    settleTask = null
                    // 画面确认渲染出来后再接管声音+震动，与 Activity 上屏后播放的口径一致
                    runCatching { RemindSound.play(appCtx) }
                    if (!manualTest) Prefs.setTodayReminded(appCtx, true)
                    NotifyHelper.cancelRemindNotification(appCtx)
                    if (!shown) {
                        // 附着且有尺寸但视图标记不可见：常见于被锁屏/上层窗口遮挡，记录便于回归定位
                        LogHelper.w("Overlay", "悬浮窗已挂载但 shown=false（疑似被遮挡/锁屏覆盖）")
                    }
                    LogHelper.i("Overlay", "悬浮窗弹窗已展示 fromAlarm=$fromAlarm manualTest=$manualTest")
                    return
                }
                if (stage < stages.size - 1) {
                    stage++
                    handler.postDelayed(this, stages[stage] - stages[stage - 1])
                } else if (!failFired) {
                    failFired = true
                    LogHelper.w("Overlay", "悬浮窗 2s 内仍未渲染出画面(attached=$attached shown=$shown ${w}x$h locked=$locked off=$off)，回退 Activity/通知")
                    settleTask = null
                    removeViewQuietly(wm, view)
                    onFail?.invoke()
                }
            }
        }
        settleTask = settle
        handler.postDelayed(settle, stages[0])

        // 锁屏时点亮屏幕（持有引用，关闭时主动释放）
        runCatching {
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "StarRailCheckin:overlay").apply {
                    setReferenceCounted(false)
                    acquire(10_000)
                }
            }
        }
        // 1分钟无操作自动关闭：仅关闭弹窗，不再排 snooze 追弹；
        // 只有用户主动点「稍后提醒」才会按设定间隔再次提醒。
        autoDismissTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            if (viewRef?.get() === view) {
                LogHelper.i("Overlay", "弹窗1分钟无操作，自动关闭（不排snooze）")
                dismiss(wm, view, snooze = false)
            }
        }
        autoDismissTask = task
        handler.postDelayed(task, 60_000L)
        return true
    }

    private fun dismiss(wm: WindowManager, view: View, snooze: Boolean) {
        autoDismissTask?.let { handler.removeCallbacks(it) }
        autoDismissTask = null
        runCatching { wm.removeView(view) }.onFailure { }
        if (viewRef?.get() === view) viewRef?.clear()
        releaseWakeLock()
        releaseOwners()
        // 未开启「常驻保活」时回收到点临时拉起的前台服务，避免后台常驻占用
        val appCtx = view.context.applicationContext
        if (!Prefs.keepNotifyEnabled(appCtx)) {
            runCatching { com.mh.starrailcheckin.service.KeepAliveService.stop(appCtx) }
        }
        if (snooze) {
            runCatching { AlarmHelper.scheduleSnooze(view.context, Prefs.snoozeIntervalMin(view.context)) }
        }
        RemindSound.stop()
    }

    /** 失败回退时只移除视图，不回收保活服务（否则会削弱紧接着要用的后台启动豁免） */
    private fun removeViewQuietly(wm: WindowManager, view: View) {
        autoDismissTask?.let { handler.removeCallbacks(it) }
        autoDismissTask = null
        settleTask?.let { handler.removeCallbacks(it) }
        settleTask = null
        runCatching { wm.removeView(view) }.onFailure { }
        if (viewRef?.get() === view) viewRef?.clear()
        releaseWakeLock()
        releaseOwners()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
        wakeLock = null
    }

    private fun releaseOwners() {
        host?.let { h ->
            runCatching { h.registry.currentState = Lifecycle.State.DESTROYED }
        }
        host = null
    }

    private fun removeExisting(context: Context) {
        autoDismissTask?.let { handler.removeCallbacks(it) }
        autoDismissTask = null
        settleTask?.let { handler.removeCallbacks(it) }
        settleTask = null
        releaseWakeLock()
        val old = viewRef?.get()
        if (old != null) {
            runCatching {
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(old)
            }.onFailure { }
            viewRef?.clear()
        }
        releaseOwners()
    }

    /** 读取卡片背景：优先裁剪后的本地文件，失败回退旧 content:// URI；都没有返回 null（用 Miuix 默认卡片色） */
    private fun loadCardBackground(ctx: Context): Bitmap? {
        Prefs.remindBgPath(ctx)?.let { p ->
            if (!p.isNullOrBlank() && java.io.File(p).exists()) {
                BitmapSampler.decodeFile(p, 1080)?.let { return it }
            }
        }
        Prefs.remindBgUri(ctx)?.let { s ->
            runCatching {
                ctx.contentResolver.openInputStream(Uri.parse(s))?.use { BitmapSampler.decodeStream(it, 1080) }
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
