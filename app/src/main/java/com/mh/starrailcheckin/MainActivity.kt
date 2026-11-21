package com.mh.starrailcheckin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.mh.starrailcheckin.ui.check.CheckScreen
import com.mh.starrailcheckin.ui.gacha.GachaInputActivity
import com.mh.starrailcheckin.ui.home.HomeScreen
import com.mh.starrailcheckin.ui.more.MoreScreen
import com.mh.starrailcheckin.ui.profile.EnkaInputActivity
import com.mh.starrailcheckin.ui.profile.ProfileScreen
import com.mh.starrailcheckin.ui.settings.SettingsScreen
import com.mh.starrailcheckin.ui.tabbar.LiquidBottomTab
import com.mh.starrailcheckin.ui.tabbar.LiquidBottomTabs
import com.mh.starrailcheckin.ui.theme.AmoledBackgroundGradient
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.NotifyHelper
import com.mh.starrailcheckin.util.AppForeground
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.UsageStatsHelper
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.UpdateChecker
import com.mh.starrailcheckin.network.UpdateCheckResult
import com.mh.starrailcheckin.network.RemoteRelease
import com.mh.starrailcheckin.ui.about.AboutActivity
import androidx.core.content.pm.PackageInfoCompat
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化通知渠道
        NotifyHelper.ensureChannels(this)

        // Android 13+ 主动申请通知权限：提醒兜底依赖通知，未授权时弹窗被拦就彻底无感
        runCatching { com.mh.starrailcheckin.util.PermissionHelper.requestNotificationPermission(this) }

        // 周期内顺带自检闹钟：丢失则自动重排，避免闹钟被系统清除后只剩 15 分钟兜底
        runCatching { com.mh.starrailcheckin.util.AlarmHelper.registerAllAlarms(this) }

        // 打开 App 时顺带检查每日自动签到（到达设定时间且今天未成功才执行）
        Thread {
            runCatching { com.mh.starrailcheckin.util.SignHelper.autoSignIfDue(this) }
        }.start()

        // 冷启动方案2 补判：未打卡则 force 刷新一次天数，走统一判定（+1 及以上立即落标记）
        Thread {
            runCatching {
                if (Prefs.checkinScheme(this) == 2 && !Prefs.isScheme2Checked(this)) {
                    val stats = MicaApi.fetchPlayerStats(this, force = true)
                    val openedToday = UsageStatsHelper.getTodayGameUsage(this).opened
                    CheckinHelper.noteDaysFetched(this, stats.activeDays, openedToday)
                    CheckinHelper.checkAndMarkChecked(this, stats.activeDays)
                }
            }
        }.start()

        // 15 分钟周期兜底任务（WorkManager 托管，进程被杀后仍会被拉起）
        runCatching { com.mh.starrailcheckin.util.RemindWorker.schedule(this) }
        // 凌晨基准固化闹钟自检（3:50/3:55/3:59）
        runCatching { com.mh.starrailcheckin.util.AlarmHelper.ensureBaselineAlarm(this) }

        // 常驻保活服务：后台启动 Activity 豁免 + 保活；跟随常驻通知开关
        if (Prefs.keepNotifyEnabled(this)) {
            runCatching { com.mh.starrailcheckin.service.KeepAliveService.start(this) }
        }

        setContent {
            val themeModeState by Prefs.getThemeMode(this).collectAsState(initial = 0)
            val themeMode = themeModeState ?: 0
            val darkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // 根部创建导航事件调度器 owner，供 Miuix 弹窗内 NavigationBackHandler 读取；
            // parent = null 表示在此创建根 dispatcher，避免取空 owner 抛异常闪退。
            val navOwner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navOwner) {
                StarRailCheckinTheme(darkTheme = darkTheme) {
                    AppMain()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForeground.isForeground = true
        // 回前台自愈：WM 周期任务 / 兜底闹钟若被系统清除，此处幂等重排
        runCatching { com.mh.starrailcheckin.util.RemindWorker.schedule(this) }
        runCatching { com.mh.starrailcheckin.util.AlarmHelper.ensurePeriodicTasksAlarm(this) }
        runCatching { com.mh.starrailcheckin.util.AlarmHelper.ensureBaselineAlarm(this) }
        // 回前台本地兜底判定：未打卡则用本地缓存天数走一次统一判定；联网刷新交给 Worker / 我的页
        runCatching {
            if (Prefs.checkinScheme(this) == 2 && !Prefs.isScheme2Checked(this)) {
                val days = Prefs.playDays(this, Prefs.uid(this))
                if (days > 0) CheckinHelper.checkAndMarkChecked(this, days)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        AppForeground.isForeground = false
    }
}

@Composable
fun AppMain() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentScreen by remember { mutableIntStateOf(0) } // 0=主页, 2=个人, 3=设置, 4=引导页, 6=更多
    val context = LocalContext.current
    val guideFinished by Prefs.getGuideFinished(context).collectAsState(initial = null)
    val amoledState by Prefs.getAmoledDark(context).collectAsState(initial = false)
    val amoled = amoledState ?: false
    // 启动自动检查到的新版本（非空时弹更新提示）
    var autoUpdateRelease by remember { mutableStateOf<RemoteRelease?>(null) }

    // 处理返回键：二级页返回主界面
    BackHandler(enabled = currentScreen != 0) {
        currentScreen = 0
    }

    // 第一次打开时显示引导页（本地配置读取后判断一次）；否则直接进主页，无启动页
    LaunchedEffect(guideFinished) {
        if (guideFinished == false) {
            currentScreen = 4
        }
    }

    // 进入 App 后台自动检查更新（受设置页开关控制；引导页阶段不检查）
    LaunchedEffect(guideFinished) {
        if (guideFinished != true || !Prefs.autoCheckUpdate(context)) return@LaunchedEffect
        val pi = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val localCode = pi?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
        val localName = pi?.versionName ?: ""
        delay(1500)
        val result = withContext(Dispatchers.IO) { UpdateChecker.check(localCode, localName) }
        if (result is UpdateCheckResult.HasUpdate) autoUpdateRelease = result.release
    }

    // 背景录制层：根渐变背景录进 backdrop；内容录制层：页面卡片/文字录进 contentBackdrop。
    // 底栏采样两者合并结果；底栏本身不挂录制层，避免自采样重影。
    val backdrop = rememberLayerBackdrop()
    val contentBackdrop = rememberLayerBackdrop()
    val sampledBackdrop = rememberCombinedBackdrop(backdrop, contentBackdrop)

    // 底栏配色：选中取 Miuix primary；未选中浅色黑、深色白
    val tabDark = LocalAppDarkTheme.current
    val tabSelectedColor = MiuixTheme.colorScheme.primary
    val tabUnselectedColor = if (tabDark) Color.White else Color.Black

    // Miuix Scaffold 内置 MiuixPopupHost，作为 OverlayDialog 弹窗宿主；不传顶栏/底栏，content 铺满
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 根背景录制层：低饱和渐变背景（随深浅切换），录进 backdrop 供底栏玻璃采样
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(
                    Brush.verticalGradient(
                        colors = when {
                            tabDark && amoled -> AmoledBackgroundGradient
                            tabDark -> DarkBackgroundGradient
                            else -> LightBackgroundGradient
                        }
                    )
                )
        )

        when (currentScreen) {
            0 -> {
                // 内容录制层：只录页面卡片/文字，不含底栏，避免底栏自采样重影；
                // statusBarsPadding 让页面内容避开沉浸式状态栏，避免顶部标题/卡片被遮挡。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .layerBackdrop(contentBackdrop)
                ) {
                when (selectedTab) {
                    0 -> HomeScreen(modifier = Modifier.fillMaxSize())
                    1 -> CheckScreen(modifier = Modifier.fillMaxSize())
                    2 -> ProfileScreen(
                        modifier = Modifier.fillMaxSize(),
                        onGachaClick = {
                        context.startActivity(Intent(context, GachaInputActivity::class.java))
                    },
                        onEnkaClick = {
                            context.startActivity(Intent(context, EnkaInputActivity::class.java))
                        },
                        onAccountsClick = {
                            context.startActivity(
                                Intent(
                                    context,
                                    com.mh.starrailcheckin.ui.account.AccountManagerActivity::class.java
                                )
                            )
                        },
                        onOfficialToolsClick = {
                            context.startActivity(
                                Intent(context, com.mh.starrailcheckin.ui.tools.OfficialToolsActivity::class.java)
                            )
                        },
                        onChallengeClick = {
                            context.startActivity(
                                Intent(context, com.mh.starrailcheckin.ui.challenge.ChallengeRecordsActivity::class.java)
                            )
                        },
                        onLedgerClick = {
                            context.startActivity(
                                Intent(context, com.mh.starrailcheckin.ui.ledger.LedgerActivity::class.java)
                            )
                        }
                    )
                    3 -> SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        onMoreClick = { currentScreen = 6 }
                    )
                }
                }

                // 底栏兄弟层叠加，采样 背景 + 内容 合并结果，使后方卡片/文字被磨砂透出
                LiquidBottomTabs(
                    selectedTabIndex = { selectedTab },
                    onTabSelected = { selectedTab = it },
                    backdrop = sampledBackdrop,
                    tabsCount = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    LiquidBottomTab(onClick = { selectedTab = 0 }) {
                        val active = selectedTab == 0
                        Icon(
                            painter = painterResource(id = R.drawable.ic_home),
                            contentDescription = "首页",
                            modifier = Modifier.size(24.dp),
                            tint = if (active) tabSelectedColor else tabUnselectedColor
                        )
                        Text(
                            text = "首页",
                            color = if (active) tabSelectedColor else tabUnselectedColor
                        )
                    }
                    LiquidBottomTab(onClick = { selectedTab = 1 }) {
                        val active = selectedTab == 1
                        Icon(
                            painter = painterResource(id = R.drawable.ic_checkin),
                            contentDescription = "打卡",
                            modifier = Modifier.size(24.dp),
                            tint = if (active) tabSelectedColor else tabUnselectedColor
                        )
                        Text(
                            text = "打卡",
                            color = if (active) tabSelectedColor else tabUnselectedColor
                        )
                    }
                    LiquidBottomTab(onClick = { selectedTab = 2 }) {
                        val active = selectedTab == 2
                        Icon(
                            painter = painterResource(id = R.drawable.ic_profile),
                            contentDescription = "我的",
                            modifier = Modifier.size(24.dp),
                            tint = if (active) tabSelectedColor else tabUnselectedColor
                        )
                        Text(
                            text = "我的",
                            color = if (active) tabSelectedColor else tabUnselectedColor
                        )
                    }
                    LiquidBottomTab(onClick = { selectedTab = 3 }) {
                        val active = selectedTab == 3
                        Icon(
                            painter = painterResource(id = R.drawable.ic_settings),
                            contentDescription = "设置",
                            modifier = Modifier.size(24.dp),
                            tint = if (active) tabSelectedColor else tabUnselectedColor
                        )
                        Text(
                            text = "设置",
                            color = if (active) tabSelectedColor else tabUnselectedColor
                        )
                    }
                }
            }
            6 -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                MoreScreen(onBack = { currentScreen = 0 })
            }
        }

        // 启动自动检查到新版本：弹窗提示，去更新跳关于页走完整下载安装流程
        autoUpdateRelease?.let { rel ->
            OverlayDialog(
                show = true,
                title = "发现新版本",
                onDismissRequest = { autoUpdateRelease = null },
                content = {
                    Text(
                        text = "新版本 ${rel.versionName} 已发布",
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    if (rel.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = rel.releaseNotes,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(text = "暂不", onClick = { autoUpdateRelease = null })
                        Spacer(Modifier.width(8.dp))
                        TextButton(text = "去更新", onClick = {
                            autoUpdateRelease = null
                            context.startActivity(Intent(context, AboutActivity::class.java))
                        })
                    }
                }
            )
        }
    }
    }
}
