package com.mh.starrailcheckin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.remind.RemindGlassDialog
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.AppForeground
import com.mh.starrailcheckin.util.AlarmHelper
import com.mh.starrailcheckin.util.GameLauncher
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.PermissionHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.RemindGate
import com.mh.starrailcheckin.util.RemindSound
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * 提醒弹窗页（全屏悬浮Activity，内容为居中半屏玻璃卡片）
 * 触发条件：定时检测判定今日未启动星穹铁道时拉起（打卡页「弹窗测试」为强制触发，不受条件限制）
 *
 * 【弹窗展示方案（本轮重构，修复：到点无弹窗）】
 *  - 前台（AppForeground=true）：直接启动 Activity 弹窗（正常交互）；
 *  - 后台/锁屏（AppForeground=false）：优先使用 WindowManager 全屏悬浮窗弹窗（OverlayReminder），
 *    它不依赖"后台启动Activity"的系统豁免，有悬浮窗权限即可稳定展示 → 不再被静默拦截；
 *  - 无悬浮窗权限时才尝试 Activity，并做显示校验（onCreate 打时间戳，3s 未展示判定被拦截），
 *    最后兜底才是通知。
 *
 * 【UI】卡片为 Compose 液态玻璃（复用工程已接入的 Kyant0 backdrop 1.0.6）；
 *       声音 / 震动 / 锁屏 / 全屏 / snooze 等链路逻辑保持不变。
 */
class RemindDialogActivity : AppCompatActivity() {

    private var fromAlarm = false
    private var manualTest = false
    /** 窗口是否真正对用户可见（onResume 并确认 resumed 后置 true）；未确认可见不算已提醒 */
    private var shownConfirmed = false

    companion object {
        const val EXTRA_FROM_ALARM = "from_alarm"
        const val EXTRA_MANUAL_TEST = "manual_test"
        // 最近一次Activity实际展示时间（onCreate更新），用于校验后台启动是否被静默拦截
        @Volatile
        private var lastShownAt = 0L

        fun launch(context: Context, fromAlarm: Boolean, manualTest: Boolean = false) {
            val appCtx = context.applicationContext
            // 拉起弹窗（Activity 启动 / 悬浮窗 addView / Compose 视图）都必须在主线程执行。
            // AlarmReceiver 的后台线程、RemindWorker 的协程线程直接调用时，悬浮窗 addView 会因
            // 非主线程抛异常被吞掉，表现为"到点无弹窗、只剩通知"。这里统一切主线程。
            Handler(Looper.getMainLooper()).post {
            if (AppForeground.isForeground) {
                // 前台：Activity 弹窗（被拦截时回退悬浮窗）
                LogHelper.i("Remind", "前台触发，使用Activity弹窗 fromAlarm=$fromAlarm manualTest=$manualTest")
                startActivityChecked(appCtx, fromAlarm, manualTest) { fallbackDisplay(appCtx, fromAlarm, manualTest) }
            } else if (isScreenLockedOrOff(appCtx)) {
                // 锁屏/熄屏：优先悬浮窗（TYPE_APPLICATION_OVERLAY + SHOW_WHEN_LOCKED/TURN_SCREEN_ON，
                // 不依赖后台启动 Activity 豁免，国产 ROM 不会静默拦截）；悬浮窗渲染失败再回退全屏 Activity。
                LogHelper.i("Remind", "锁屏触发，优先悬浮窗弹窗（自动亮屏）fromAlarm=$fromAlarm")
                if (PermissionHelper.canDrawOverlays(appCtx)) {
                    val ok = runCatching { OverlayReminder.show(appCtx, fromAlarm, manualTest) {
                        LogHelper.w("Remind", "锁屏悬浮窗未显示成功，回退全屏Activity+通知")
                        startActivityChecked(appCtx, fromAlarm, manualTest) {
                            fallbackDisplay(appCtx, fromAlarm, manualTest)
                        }
                    } }.getOrDefault(false)
                    if (!ok) {
                        startActivityChecked(appCtx, fromAlarm, manualTest) {
                            fallbackDisplay(appCtx, fromAlarm, manualTest)
                        }
                    }
                } else {
                    LogHelper.w("Remind", "锁屏无悬浮窗权限，尝试全屏Activity，失败回退通知")
                    startActivityChecked(appCtx, fromAlarm, manualTest) {
                        fallbackDisplay(appCtx, fromAlarm, manualTest)
                    }
                }
            } else if (PermissionHelper.canDrawOverlays(appCtx)) {
                // 后台（解锁状态）：优先悬浮窗（不依赖后台启动豁免）；渲染失败立即回退 Activity。
                // 拥有悬浮窗权限的应用同时具备"后台启动 Activity"的系统豁免，所以这条回退是可靠的。
                LogHelper.i("Remind", "后台触发，优先使用悬浮窗弹窗 fromAlarm=$fromAlarm")
                val ok = runCatching { OverlayReminder.show(appCtx, fromAlarm, manualTest) {
                    LogHelper.w("Remind", "悬浮窗未显示成功，回退 Activity 弹窗")
                    startActivityChecked(appCtx, fromAlarm, manualTest) {
                        fallbackDisplay(appCtx, fromAlarm, manualTest)
                    }
                } }.getOrDefault(false)
                if (!ok) {
                    // 悬浮窗连 addView 都没成功（权限被撤销/布局异常）→ 直接走 Activity + 通知
                    LogHelper.w("Remind", "悬浮窗弹窗失败，回退 Activity/通知")
                    startActivityChecked(appCtx, fromAlarm, manualTest) {
                        fallbackDisplay(appCtx, fromAlarm, manualTest)
                    }
                }
            } else {
                // 无悬浮窗权限：尝试Activity（精确闹钟临时豁免可能放行），校验失败再回退通知
                LogHelper.w("Remind", "无悬浮窗权限，尝试Activity弹窗，失败回退通知")
                startActivityChecked(appCtx, fromAlarm, manualTest) { fallbackDisplay(appCtx, fromAlarm, manualTest) }
            }
            }
        }

        /** 锁屏/熄屏检测：屏幕关闭或锁屏已锁定 → 走"自动亮屏"通道 */
        private fun isScreenLockedOrOff(context: Context): Boolean {
            val kg = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return !pm.isInteractive || kg.isKeyguardLocked
        }

        /** 启动Activity弹窗并做显示校验；被拦截（3s未实际展示）时回调 onBlocked */
        private fun startActivityChecked(context: Context, fromAlarm: Boolean, manualTest: Boolean, onBlocked: () -> Unit = {}) {
            lastShownAt = 0L
            var started = false
            runCatching {
                context.startActivity(
                    Intent(context, RemindDialogActivity::class.java)
                        .putExtra(EXTRA_FROM_ALARM, fromAlarm)
                        .putExtra(EXTRA_MANUAL_TEST, manualTest)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                started = true
            }.onFailure { e ->
                LogHelper.e("Remind", "Activity弹窗拉起异常，回退", e)
                onBlocked()
                return
            }
            if (started) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (System.currentTimeMillis() - lastShownAt > 3000L) {
                        LogHelper.w("Remind", "Activity弹窗疑似被系统静默拦截，回退")
                        onBlocked()
                    }
                }, 3000L)
            }
        }

        /** 回退链：①悬浮窗弹窗（真弹窗）→ ②通知（最后手段）→ ③拉起App主界面 */
        private fun fallbackDisplay(context: Context, fromAlarm: Boolean, manualTest: Boolean) {
            if (PermissionHelper.canDrawOverlays(context)) {
                if (!OverlayReminder.show(context, fromAlarm, manualTest)) {
                    LogHelper.w("Remind", "悬浮窗回退失败，回退到通知")
                    // 弹窗未真正展示 → 清掉已提醒标记，保留后续兜底重试 / Worker 补弹机会
                    Prefs.clearTodayReminded(context)
                    NotifyHelper.showRemindNotification(context)
                }
                return
            }
            // 无悬浮窗权限 → 通知兜底
            Prefs.clearTodayReminded(context)
            val notified = runCatching {
                NotifyHelper.showRemindNotification(context)
                true
            }.getOrDefault(false)
            // 最后兜底：拉起 App 主界面，把应用带到前台，用户打开后即可看到提醒
            if (!notified || !PermissionHelper.hasNotificationPermission(context)) {
                LogHelper.w("Remind", "通知兜底不可用，最后尝试拉起App主界面")
                runCatching {
                    context.startActivity(
                        Intent(context, com.mh.starrailcheckin.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }.onFailure { e -> LogHelper.e("Remind", "拉起App主界面失败", e) }
            }
        }
    }

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private var autoDismissTask: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromAlarm = intent.getBooleanExtra(EXTRA_FROM_ALARM, false)
        manualTest = intent.getBooleanExtra(EXTRA_MANUAL_TEST, false)

        // 兜底闸门：自动链路（闹钟/Worker/通知/残留拉起）在「总开关关闭 / 今日已打卡 /
        // 今日已开游戏 / 任一提醒点尚未到点」时一律不展示，避免未到点打开 App 就弹提醒窗。
        // 仅设置页「测试弹窗」(manualTest=true) 跳过校验。
        if (!manualTest && !RemindGate.shouldShowRemind(this)) {
            LogHelper.w("Remind", "闸门拦截：未到点/已打卡/总开关关闭，不展示提醒弹窗")
            NotifyHelper.cancelRemindNotification(this)
            finish()
            return
        }

        lastShownAt = System.currentTimeMillis()
        // 内容布局前先关闭 decor 对系统栏的适配，让遮罩/内容可延伸到状态栏区域
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // 锁屏/后台展示：亮屏 + 显示在锁屏之上 + 保持唤醒
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        enterFullscreen()

        // 文案：设置页留空(blank)时沿用默认 strings，非空则覆盖标题/正文
        val title = Prefs.remindTitle(this) ?: getString(R.string.remind_title)
        val message = Prefs.remindMessage(this) ?: getString(R.string.remind_message)
        val launchLabel = "打开" + Prefs.gameDisplayName(this)
        val background = loadCardBackground()

        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        RemindGlassDialog(
                            title = title,
                            message = message,
                            launchLabel = launchLabel,
                            background = background,
                            onLaunch = {
                                cancelAutoDismiss()
                                markRemindedIfVisible()
                                GameLauncher.launchGame(this)
                                finish()
                            },
                            onSnooze = {
                                LogHelper.i("Remind", "用户选择稍后提醒")
                                cancelAutoDismiss()
                                markRemindedIfVisible()
                                AlarmHelper.scheduleSnooze(this, Prefs.snoozeIntervalMin(this))
                                finish()
                            },
                            onClose = {
                                LogHelper.i("Remind", "用户关闭弹窗")
                                cancelAutoDismiss()
                                markRemindedIfVisible()
                                finish()
                            }
                        )
                    }
                }
            }
        }

        // 弹窗展示时点亮屏幕（持有引用，销毁时主动释放，避免仅靠超时回收）
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "StarRailCheckin:popup").apply {
                    setReferenceCounted(false)
                    acquire(10_000)
                }
            }
        }
        // 无论从哪条链路拉起，弹窗载体都无条件接管声音+震动：
        // 广播/服务上下文里的 MediaPlayer 生命周期不稳，弹窗展示后必须由 Activity 重新接管，
        // 否则会出现到点有声震、弹窗起来后声音/震动被掐断的情况。play() 内部会先 stop 再播，不会双响。
        RemindSound.play(this)
        // 此处【不】写「今日已提醒」、【不】撤通知：标记与撤通知都要等 onResume 确认窗口真正
        // 可见后再做。否则 Activity 被系统静默拦截（onCreate 跑完但窗口不上屏）时会误写标记，
        // 把当天后续提醒点与兜底重试一并被闸门封死，用户只剩通知。
        LogHelper.i("Remind", "提醒弹窗已展示 fromAlarm=$fromAlarm manualTest=$manualTest")
        // 1分钟无操作自动关闭：仅关闭弹窗，不再排 snooze 追弹；
        // 只有用户主动点「稍后提醒」才会按设定间隔再次提醒。
        autoDismissTask = Runnable {
            LogHelper.i("Remind", "弹窗1分钟无操作，自动关闭（不排snooze）")
            finish()
        }
        autoDismissHandler.postDelayed(autoDismissTask!!, 60_000L)
    }

    private fun cancelAutoDismiss() {
        autoDismissTask?.let { autoDismissHandler.removeCallbacks(it) }
        autoDismissTask = null
    }

    /** 窗口仍可见时立即标记本次已提醒，避免用户快速关闭时 onResume 的 500ms 延迟回调来不及写入。 */
    private fun markRemindedIfVisible() {
        if (!manualTest && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            shownConfirmed = true
            Prefs.setTodayReminded(this, true)
        }
    }

    /** 读取卡片背景：优先裁剪后的本地文件，失败再回退旧 content:// URI；都没有返回 null（用 Miuix 默认卡片色） */
    private fun loadCardBackground(): Bitmap? {
        Prefs.remindBgPath(this)?.let { p ->
            if (!p.isNullOrBlank() && java.io.File(p).exists()) {
                BitmapFactory.decodeFile(p)?.let { return it }
            }
        }
        Prefs.remindBgUri(this)?.let { s ->
            runCatching { MediaStore.Images.Media.getBitmap(contentResolver, Uri.parse(s)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    /** 弹窗展示期间全屏隐藏系统状态栏与导航栏，关闭后随页面恢复 */
    @Suppress("DEPRECATION")
    private fun enterFullscreen() {
        runCatching {
            // 铺满到状态栏/导航栏区域，内容不被系统栏挤出
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }.onFailure { LogHelper.e("Remind", "全屏模式设置失败", it) }
    }

    override fun onResume() {
        super.onResume()
        // 首帧布局后再确认可见：延迟 500ms 仍处于 resumed，才认定用户能看到弹窗，
        // 此时才写「今日已提醒」并撤掉兜底通知。
        Handler(Looper.getMainLooper()).postDelayed({
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                shownConfirmed = true
                if (!manualTest) Prefs.setTodayReminded(this, true)
                NotifyHelper.cancelRemindNotification(this)
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { NotifyHelper.cancelRemindNotification(this) }
                }, 1_000L)
            }
        }, 500L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        fromAlarm = intent.getBooleanExtra(EXTRA_FROM_ALARM, false)
        manualTest = intent.getBooleanExtra(EXTRA_MANUAL_TEST, false)
        // singleInstance 残留实例被再次拉起时，重新过闸门；不满足展示条件直接关闭
        if (!manualTest && !RemindGate.shouldShowRemind(this)) {
            LogHelper.w("Remind", "onNewIntent：闸门拦截，关闭残留实例")
            finish()
        }
    }

    override fun onDestroy() {
        if (!shownConfirmed && !manualTest) {
            // 弹窗始终没真正上屏 → 清掉提醒标记，让兜底重试 / Worker 还能补弹；兜底通知保留
            runCatching { Prefs.clearTodayReminded(this) }
        }
        cancelAutoDismiss()
        RemindSound.stop()
        wakeLock?.let { wl -> if (wl.isHeld) wl.release() }
        wakeLock = null
        // 未开启「常驻保活」时，到点临时拉起的前台服务仅用于获取后台启动豁免，
        // 弹窗结束后立即回收，避免不必要的后台常驻占用。
        if (!Prefs.keepNotifyEnabled(this)) {
            runCatching { com.mh.starrailcheckin.service.KeepAliveService.stop(this) }
                .onFailure { LogHelper.e("Remind", "临时保活服务回收失败", it) }
        }
        super.onDestroy()
    }
}
