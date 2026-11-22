package com.mh.starrailcheckin.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mh.starrailcheckin.ui.components.DialogButtonRow
import com.mh.starrailcheckin.ui.components.DialogSingleButton
import com.mh.starrailcheckin.ui.components.PermissionRow
import com.mh.starrailcheckin.service.KeepAliveAccessibilityService
import com.mh.starrailcheckin.util.PermissionHelper
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.util.CookieHealthMonitor
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.AlarmHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mh.starrailcheckin.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.mh.starrailcheckin.ui.theme.LocalSuccessColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onMoreClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 开关类用本地状态（flowOf 一次性发射，写盘后不会自动刷新，需先改本地 state）
    var remindEnabled by remember { mutableStateOf(Prefs.reminderEnabled(context)) }
    var vibrate by remember { mutableStateOf(Prefs.remindVibrate(context)) }
    var sound by remember { mutableStateOf(Prefs.soundEnabled(context)) }
    // 多时段提醒列表（与闹钟注册源 remindTimes 一致）；本地 state 保证写盘后即时刷新
    var timesState by remember { mutableStateOf(Prefs.remindTimes(context)) }
    val remindFallback = Prefs.remindHour(context) to Prefs.remindMinute(context)
    // 游玩时长目标：本地状态持有，拖动先更新本地再异步落盘，避免一次性 flow 快照导致回弹
    var playTimeTarget by remember { mutableStateOf(Prefs.playTargetMinutes(context)) }
    val cookie by Prefs.getCookie(context).collectAsState(initial = "")
    val uid by Prefs.getUid(context).collectAsState(initial = "")
    val themeModeState by Prefs.getThemeMode(context).collectAsState(initial = 0)
    val themeMode = themeModeState ?: 0
    val amoledState by Prefs.getAmoledDark(context).collectAsState(initial = false)
    val amoled = amoledState ?: false
    val snoozeInterval by Prefs.getSnoozeInterval(context).collectAsState(initial = 10)
    val remindSoundUri by Prefs.getRemindSoundUri(context).collectAsState(initial = "")

    // 单选浮层选项
    val themeItems = listOf(
        SpinnerEntry(title = "跟随系统"),
        SpinnerEntry(title = "浅色"),
        SpinnerEntry(title = "深色")
    )
    val snoozeValues = listOf(5, 10, 30)
    val snoozeItems = snoozeValues.map { SpinnerEntry(title = "${it}分钟") }

    // 根据 URI 获取铃声名称
    val ringtoneName = remember(remindSoundUri) {
        if (remindSoundUri.isBlank()) {
            "默认铃声"
        } else {
            runCatching {
                android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(remindSoundUri))?.getTitle(context)
            }.getOrNull() ?: "自定义铃声"
        }
    }

    var showTimePicker by remember { mutableStateOf(false) }
    // 正在编辑的时段原值（HH:mm）；null 表示本次为新增。修改按原值移除，
    // 不按列表索引，避免时段排序后索引错位导致"改完变成另一个时间"。
    var editingTime by remember { mutableStateOf<String?>(null) }
    // 铃声选择器
    val ringtonePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
            }
            uri?.let {
                // 持久化读取权限：否则退出重进后媒体铃声 URI 打不开，会回退到默认音
                runCatching {
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                scope.launch {
                    com.mh.starrailcheckin.util.RemindSound.setSoundUri(context, it.toString())
                }
            }
        }
    }

    // 卡片背景：选图 → UCrop 裁剪 → 落盘内部目录
    val bgCropLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val path = com.mh.starrailcheckin.util.CropHelper.outputUri(result.data)?.path
            if (!path.isNullOrBlank() && java.io.File(path).exists()) {
                Prefs.setRemindBgPath(context, path)
                Prefs.setRemindBgUri(context, null)
                android.widget.Toast.makeText(context, "背景图已设置", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val bgPickerLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val dest = com.mh.starrailcheckin.util.CropHelper.destinationFile(context, "cardbg")
            bgCropLauncher.launch(
                com.mh.starrailcheckin.util.CropHelper.buildIntent(
                    context, uri, dest,
                    com.mh.starrailcheckin.util.CropHelper.RATIO_CARD_X,
                    com.mh.starrailcheckin.util.CropHelper.RATIO_CARD_Y
                )
            )
        }
    }

    // 扫码登录弹窗状态
    var showQrDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrStatus by remember { mutableStateOf("") }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    // 授权状态刷新计数：从系统设置页返回 ON_RESUME 后递增，触发权限面板重算各开关状态
    var permVersion by remember { mutableIntStateOf(0) }
    var cookieInput by remember { mutableStateOf("") }
    var cookieImporting by remember { mutableStateOf(false) }
    var cookieHealthEnabled by remember { mutableStateOf(Prefs.cookieHealthCheckEnabled(context)) }
    var cookieAutoRenewEnabled by remember { mutableStateOf(Prefs.cookieAutoRenewEnabled(context)) }
    var cookieHealthAction by remember { mutableStateOf("") }
    var cookieHealthVersion by remember { mutableIntStateOf(0) }
    val cookieHealth = remember(cookieHealthVersion) {
        val accountId = Prefs.activeAccountId(context)
        Prefs.cookieHealthState(context).takeIf {
            accountId.isNotBlank() && it.accountId == accountId
        }
    }
    val cookieSupportsAutoRenew = remember(cookieHealthVersion) {
        MicaApi.cookieSupportsAutoRenew(Prefs.cookie(context))
    }
    val settingsLifecycleOwner = context as? LifecycleOwner
    DisposableEffect(settingsLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cookieHealthVersion++
                permVersion++
            }
        }
        settingsLifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { settingsLifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    // 自定义提醒文案
    var showRemindTextDialog by remember { mutableStateOf(false) }
    var remindTitleInput by remember { mutableStateOf("") }
    var remindMsgInput by remember { mutableStateOf("") }
    // 常驻保活开关
    var keepAlive by remember { mutableStateOf(Prefs.keepNotifyEnabled(context)) }
    // 进入 App 自动检查更新
    var autoUpdate by remember { mutableStateOf(Prefs.autoCheckUpdate(context)) }
    // 体力与派遣提醒
    var staminaNotify by remember { mutableStateOf(Prefs.staminaNotifyEnabled(context)) }
    var staminaFull by remember { mutableStateOf(Prefs.staminaFullNotify(context)) }
    var expeditionNotify by remember { mutableStateOf(Prefs.expeditionNotifyEnabled(context)) }
    var staminaThreshold by remember { mutableStateOf(Prefs.staminaThreshold(context)) }
    var staminaChecking by remember { mutableStateOf(false) }
    var staminaInterval by remember { mutableStateOf(Prefs.staminaIntervalMin(context)) }
    var staminaPreFull by remember { mutableStateOf(Prefs.staminaPreFullNotify(context)) }
    var staminaPreFullMin by remember { mutableStateOf(Prefs.staminaPreFullMin(context)) }
    var expeditionPreDone by remember { mutableStateOf(Prefs.expeditionPreDoneNotify(context)) }
    var expeditionPreDoneMin by remember { mutableStateOf(Prefs.expeditionPreDoneMin(context)) }
    var cookieInterval by remember { mutableStateOf(Prefs.cookieCheckIntervalMin(context)) }
    val staminaThresholdValues = listOf(120, 160, 180, 200, 220, 240)
    val staminaThresholdItems = staminaThresholdValues.map { SpinnerEntry(title = "$it 体力") }
    val preFullMinValues = listOf(5, 10, 15, 20, 30)
    val preFullMinItems = preFullMinValues.map { SpinnerEntry(title = "提前 $it 分钟") }
    val preDoneMinValues = listOf(5, 10, 15, 30, 60)
    val preDoneMinItems = preDoneMinValues.map { SpinnerEntry(title = "提前 $it 分钟") }

    // 后台运行状态自检（ON_RESUME 时随 cookieHealthVersion 刷新）
    val statusItems = remember(cookieHealthVersion) {
        com.mh.starrailcheckin.util.StatusHelper.collect(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 通知设置卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SwitchPreference(
                    checked = remindEnabled,
                    onCheckedChange = {
                        remindEnabled = it
                        scope.launch {
                            Prefs.setRemindEnabled(context, it)
                            AlarmHelper.registerAllAlarms(context)
                        }
                    },
                    title = "提醒通知",
                    summary = "开启每日打卡提醒"
                )
                // 多时段提醒列表：每条可修改 / 移除，未满上限可新增
                timesState.forEachIndexed { idx, slot ->
                    ArrowPreference(
                        title = "提醒时间 ${idx + 1}",
                        onClick = { editingTime = slot; showTimePicker = true },
                        endActions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = slot,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "移除",
                                    color = MiuixTheme.colorScheme.error,
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            Prefs.removeRemindTime(context, slot)
                                            // 至少保留一个提醒点：删空会让所有闹钟一起被清除，到点就彻底没有反应
                                            if (Prefs.remindTimes(context).isEmpty()) {
                                                Prefs.upsertRemindTime(
                                                    context,
                                                    Prefs.remindHour(context),
                                                    Prefs.remindMinute(context)
                                                )
                                            }
                                            timesState = Prefs.remindTimes(context)
                                            AlarmHelper.registerAllAlarms(context)
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
                if (timesState.size < Prefs.MAX_REMIND_TIMES) {
                    ArrowPreference(
                        title = "添加提醒时间",
                        summary = "最多 ${Prefs.MAX_REMIND_TIMES} 个提醒",
                        onClick = { editingTime = null; showTimePicker = true }
                    )
                }
                SwitchPreference(
                    checked = vibrate,
                    onCheckedChange = {
                        vibrate = it
                        scope.launch { Prefs.setVibrate(context, it) }
                    },
                    title = "震动反馈",
                    summary = "提醒时连续震动"
                )
                OverlaySpinnerPreference(
                    items = snoozeItems,
                    selectedIndex = snoozeValues.indexOf(snoozeInterval).coerceAtLeast(0),
                    title = "稍后提醒间隔",
                    summary = "${snoozeInterval}分钟",
                    onSelectedIndexChange = { idx ->
                        scope.launch { Prefs.setSnoozeInterval(context, snoozeValues[idx]) }
                    }
                )
                ArrowPreference(
                    title = "提醒声音",
                    onClick = {
                        // 打开系统铃声选择器
                        val intent = android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
                        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提醒声音")
                        ringtonePickerLauncher.launch(intent)
                    },
                    endActions = {
                        Text(
                            text = ringtoneName,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                )
                SwitchPreference(
                    checked = sound,
                    onCheckedChange = {
                        sound = it
                        scope.launch { Prefs.setSound(context, it) }
                    },
                    title = "声音提醒",
                    summary = "提醒时播放声音"
                )
                ArrowPreference(
                    title = "提醒文案",
                    summary = if (Prefs.remindTitle(context).isNullOrBlank() &&
                        Prefs.remindMessage(context).isNullOrBlank()
                    ) "默认文案" else "已自定义",
                    onClick = {
                        remindTitleInput = Prefs.remindTitle(context).orEmpty()
                        remindMsgInput = Prefs.remindMessage(context).orEmpty()
                        showRemindTextDialog = true
                    }
                )
                SwitchPreference(
                    checked = keepAlive,
                    onCheckedChange = {
                        keepAlive = it
                        Prefs.setKeepNotify(context, it)
                        if (it) {
                            runCatching {
                                com.mh.starrailcheckin.service.KeepAliveService.start(context)
                            }
                        } else {
                            runCatching {
                                com.mh.starrailcheckin.service.KeepAliveService.stop(context)
                            }
                        }
                    },
                    title = "常驻保活",
                    summary = "保持后台运行，降低被系统杀掉概率"
                )
                ArrowPreference(
                    title = "测试弹窗",
                    summary = "点击测试提醒弹窗（玻璃卡片）",
                    onClick = {
                        com.mh.starrailcheckin.RemindDialogActivity.launch(context, false, manualTest = true)
                    }
                )
                ArrowPreference(
                    title = "权限管理",
                    summary = "查看和申请必要权限",
                    onClick = {
                        showPermissionDialog = true
                    }
                )
                ArrowPreference(
                    title = "卡片背景",
                    summary = "选择相册图片裁剪为弹窗背景",
                    onClick = { bgPickerLauncher.launch(arrayOf("image/*")) }
                )
                ArrowPreference(
                    title = "恢复默认背景",
                    summary = "清除自定义背景图",
                    onClick = {
                        Prefs.setRemindBgPath(context, null)
                        Prefs.setRemindBgUri(context, null)
                        android.widget.Toast.makeText(context, "已恢复默认背景", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 体力与派遣提醒卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                SwitchPreference(
                    checked = staminaNotify,
                    onCheckedChange = {
                        staminaNotify = it
                        Prefs.setStaminaNotifyEnabled(context, it)
                    },
                    title = "体力与派遣提醒",
                    summary = "体力达到阈值、回满或派遣完成时通知"
                )
                OverlaySpinnerPreference(
                    items = staminaThresholdItems,
                    selectedIndex = staminaThresholdValues.indexOf(staminaThreshold)
                        .let { if (it >= 0) it else 3 },
                    title = "体力阈值",
                    summary = "$staminaThreshold 体力",
                    onSelectedIndexChange = { idx ->
                        val v = staminaThresholdValues[idx]
                        staminaThreshold = v
                        Prefs.setStaminaThreshold(context, v)
                    }
                )
                SwitchPreference(
                    checked = staminaFull,
                    onCheckedChange = {
                        staminaFull = it
                        Prefs.setStaminaFullNotify(context, it)
                    },
                    title = "体力回满提醒",
                    summary = "体力溢出前提醒一次"
                )
                SwitchPreference(
                    checked = staminaPreFull,
                    onCheckedChange = {
                        staminaPreFull = it
                        Prefs.setStaminaPreFullNotify(context, it)
                    },
                    title = "回满前预告",
                    summary = "体力即将回满时提前提醒（每天一次）"
                )
                OverlaySpinnerPreference(
                    items = preFullMinItems,
                    selectedIndex = preFullMinValues.indexOf(staminaPreFullMin)
                        .let { if (it >= 0) it else 1 },
                    title = "预告提前量",
                    summary = "回满前 $staminaPreFullMin 分钟提醒",
                    onSelectedIndexChange = { idx ->
                        val v = preFullMinValues[idx]
                        staminaPreFullMin = v
                        Prefs.setStaminaPreFullMin(context, v)
                    }
                )
                SwitchPreference(
                    checked = expeditionNotify,
                    onCheckedChange = {
                        expeditionNotify = it
                        Prefs.setExpeditionNotifyEnabled(context, it)
                    },
                    title = "派遣完成提醒",
                    summary = "全部派遣结束时通知（每天一次）"
                )
                SwitchPreference(
                    checked = expeditionPreDone,
                    onCheckedChange = {
                        expeditionPreDone = it
                        Prefs.setExpeditionPreDoneNotify(context, it)
                    },
                    title = "派遣即将完成预告",
                    summary = "单项派遣临近结束时提前提醒"
                )
                OverlaySpinnerPreference(
                    items = preDoneMinItems,
                    selectedIndex = preDoneMinValues.indexOf(expeditionPreDoneMin)
                        .let { if (it >= 0) it else 1 },
                    title = "派遣预告提前量",
                    summary = "结束前 $expeditionPreDoneMin 分钟提醒",
                    onSelectedIndexChange = { idx ->
                        val v = preDoneMinValues[idx]
                        expeditionPreDoneMin = v
                        Prefs.setExpeditionPreDoneMin(context, v)
                    }
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "检查间隔",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "每 $staminaInterval 分钟检查一次体力与派遣",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Slider(
                        value = staminaInterval.toFloat(),
                        onValueChange = { newValue ->
                            val rounded = (newValue / 5).toInt() * 5
                            staminaInterval = rounded
                            Prefs.setStaminaIntervalMin(context, rounded)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        valueRange = 5f..120f,
                        steps = 23
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("5 分钟", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                        Text("120 分钟", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                }
                ArrowPreference(
                    title = "立即检查",
                    summary = if (staminaChecking) "正在查询体力…" else "立即拉取一次当前体力状态",
                    onClick = {
                        if (!staminaChecking) {
                            scope.launch {
                                staminaChecking = true
                                val msg = withContext(Dispatchers.IO) {
                                    runCatching { com.mh.starrailcheckin.util.StaminaMonitor.checkNow(context) }
                                        .getOrDefault("体力查询失败")
                                }
                                staminaChecking = false
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 后台运行状态自检面板
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "后台运行状态",
                    style = MiuixTheme.textStyles.headline1
                )
                Spacer(modifier = Modifier.height(8.dp))
                statusItems.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.label,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Text(
                                item.detail,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                        Text(
                            if (item.ok) "正常" else "待处理",
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (item.ok) LocalSuccessColor.current else MiuixTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 主题模式卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            val themeLabel = when (themeMode) {
                0 -> "跟随系统"
                1 -> "浅色"
                2 -> "深色"
                else -> "跟随系统"
            }
            OverlaySpinnerPreference(
                items = themeItems,
                selectedIndex = themeMode.coerceIn(0, 2),
                title = "主题模式",
                summary = themeLabel,
                onSelectedIndexChange = { idx ->
                    Prefs.setThemeMode(context, idx)
                    android.widget.Toast.makeText(context, "主题已切换", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            SwitchPreference(
                checked = amoled,
                onCheckedChange = { Prefs.setAmoledDark(context, it) },
                title = "AMOLED 纯黑深色",
                summary = "深色主题下使用纯黑背景，省电更护眼"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 游玩时长目标卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "游玩时长目标",
                    style = MiuixTheme.textStyles.headline1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${playTimeTarget / 60}.${(playTimeTarget % 60) / 30 * 5} 小时 / 天",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Slider(
                    value = playTimeTarget.toFloat(),
                    onValueChange = { newValue ->
                        val rounded = (newValue / 30).toInt() * 30
                        playTimeTarget = rounded
                        scope.launch { Prefs.setPlayTimeTarget(context, rounded) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 30f..240f,
                    steps = 7
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5h", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text("4h", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 账号卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ArrowPreference(
                title = "米游社 Cookie",
                onClick = {
                    cookieInput = cookie
                    showCookieDialog = true
                },
                endActions = {
                    Text(
                        text = if (cookie.isNotEmpty()) "已导入" else "未导入",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            )
            SwitchPreference(
                checked = cookieHealthEnabled,
                onCheckedChange = {
                    cookieHealthEnabled = it
                    Prefs.setCookieHealthCheckEnabled(context, it)
                },
                title = "健康检查与失效提醒",
                summary = "每 $cookieInterval 分钟检查当前账号，登录态失效时通知"
            )
            SwitchPreference(
                checked = cookieAutoRenewEnabled,
                onCheckedChange = {
                    cookieAutoRenewEnabled = it
                    Prefs.setCookieAutoRenewEnabled(context, it)
                    if (it && !cookieSupportsAutoRenew) {
                        android.widget.Toast.makeText(
                            context,
                            "已开启，但当前 Cookie 不含 stoken，暂时无法续期；请重新登录获取 stoken",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                title = "自动续期",
                summary = if (cookieSupportsAutoRenew) "失效时使用 stoken 续期并复验"
                else "当前不含 stoken；可开启，重新登录后才能续期"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = "检查间隔",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "每 $cookieInterval 分钟检查一次登录态",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Slider(
                    value = cookieInterval.toFloat(),
                    onValueChange = { newValue ->
                        val rounded = (newValue / 15).toInt() * 15
                        cookieInterval = rounded
                        Prefs.setCookieCheckIntervalMin(context, rounded)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 15f..240f,
                    steps = 15
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("15 分钟", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    Text("240 分钟", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
            }
            ArrowPreference(
                title = "立即检查",
                summary = if (cookieHealthAction == "check") "正在检查…"
                else {
                    val state = cookieHealth
                    "最近检查：${cookieHealthTime(state?.lastCheckAt ?: 0L)}\n" +
                        (state?.let { "${cookieHealthStatusText(it.status)} · ${it.message}" }
                            ?: "当前账号尚未检查")
                },
                onClick = {
                    if (cookieHealthAction.isEmpty()) {
                        cookieHealthAction = "check"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { CookieHealthMonitor.checkNow(context) }
                                }
                                cookieHealthVersion++
                                val message = result.getOrNull()?.message
                                    ?: "检查失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                android.widget.Toast.makeText(
                                    context, message, android.widget.Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                cookieHealthAction = ""
                            }
                        }
                    }
                }
            )
            ArrowPreference(
                title = "立即续期",
                summary = if (cookieHealthAction == "renew") "正在续期…"
                else {
                    val renewTime = cookieHealth?.lastRenewAt ?: 0L
                    (if (cookieSupportsAutoRenew) "支持 stoken 自动续期"
                    else "不支持 stoken 自动续期，需重新登录获取") +
                        (if (renewTime > 0L) "\n最近续期：${cookieHealthTime(renewTime)}" else "")
                },
                onClick = {
                    if (cookieHealthAction.isEmpty()) {
                        cookieHealthAction = "renew"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { CookieHealthMonitor.renewNow(context) }
                                }
                                cookieHealthVersion++
                                val message = result.getOrNull()
                                    ?: "续期失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                                android.widget.Toast.makeText(
                                    context, message, android.widget.Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                cookieHealthAction = ""
                            }
                        }
                    }
                }
            )
            ArrowPreference(
                title = "退出登录",
                summary = "清除 Cookie 与账号缓存",
                onClick = {
                    Prefs.clearLogin(context)
                    cookieHealthVersion++
                    android.widget.Toast.makeText(context, "已退出登录", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            ArrowPreference(
                title = "复制 UID",
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UID", uid))
                },
                endActions = {
                    Text(
                        text = if (uid.isNotEmpty()) uid else "未设置",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            )
            ArrowPreference(
                title = "复制 Cookie",
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cookie", cookie))
                },
                endActions = {
                    Text(
                        text = if (cookie.isNotEmpty()) "已保存" else "未保存",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            )
            ArrowPreference(
                title = "多账号管理",
                summary = "保存多个 Cookie、快速切换、批量签到",
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            com.mh.starrailcheckin.ui.account.AccountManagerActivity::class.java
                        )
                    )
                }
            )
            ArrowPreference(
                title = "网页登录",
                summary = "用网页登录获取 Cookie",
                onClick = {
                    val intent = android.content.Intent(context, com.mh.starrailcheckin.LoginWebViewActivity::class.java)
                    context.startActivity(intent)
                }
            )
            ArrowPreference(
                title = "扫码登录",
                summary = "扫码获取 Cookie",
                onClick = {
                    qrBitmap = null
                    qrStatus = ""
                    showQrDialog = true
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 关于入口：应用信息、作者、协议与引用
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            SwitchPreference(
                checked = autoUpdate,
                onCheckedChange = {
                    autoUpdate = it
                    Prefs.setAutoCheckUpdate(context, it)
                },
                title = "自动检查更新",
                summary = "进入 App 时后台检查是否有新版本"
            )
            ArrowPreference("关于", summary = "应用信息、作者、协议与引用", onClick = {
                context.startActivity(
                    android.content.Intent(
                        context,
                        com.mh.starrailcheckin.ui.about.AboutActivity::class.java
                    )
                )
            })
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 更多入口（低频/高级设置收纳到二级页）
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ArrowPreference("更多", summary = "设备指纹、数据与日志", onClick = onMoreClick)
        }

    }

    // 时间选择对话框
    if (showTimePicker) {
        val editingSlot = editingTime?.split(":")
        val initH = editingSlot?.getOrNull(0)?.toIntOrNull() ?: remindFallback.first
        val initM = editingSlot?.getOrNull(1)?.toIntOrNull() ?: remindFallback.second
        var pickerHour by remember { mutableIntStateOf(initH) }
        var pickerMinute by remember { mutableIntStateOf(initM) }
        val dialogTitle = if (editingTime == null) "添加提醒时间" else "修改提醒时间"

        OverlayDialog(
            show = true,
            title = dialogTitle,
            onDismissRequest = { showTimePicker = false; editingTime = null },
            content = {
                TimePickerRow(
                    hour = pickerHour,
                    minute = pickerMinute,
                    onHourChange = { pickerHour = it },
                    onMinuteChange = { pickerMinute = it }
                )
                DialogButtonRow(
                    confirmText = "确定",
                    onConfirm = {
                        scope.launch {
                            // 按原值移除旧时段（不按列表索引），避免排序后索引错位；
                            // 新增时 editingTime 为 null，不移除任何旧时段
                            editingTime?.let { Prefs.removeRemindTime(context, it) }
                            Prefs.upsertRemindTime(context, pickerHour, pickerMinute)
                            timesState = Prefs.remindTimes(context)
                            AlarmHelper.registerAllAlarms(context)
                        }
                        showTimePicker = false
                        editingTime = null
                    },
                    onDismiss = { showTimePicker = false; editingTime = null }
                )
            }
        )
    }

    // 自定义提醒文案对话框
    if (showRemindTextDialog) {
        OverlayDialog(
            show = true,
            title = "自定义提醒文案",
            onDismissRequest = { showRemindTextDialog = false },
            content = {
                TextField(
                    value = remindTitleInput,
                    onValueChange = { remindTitleInput = it },
                    label = "弹窗标题（留空用默认）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = remindMsgInput,
                    onValueChange = { remindMsgInput = it },
                    label = "弹窗正文（留空用默认）",
                    modifier = Modifier.fillMaxWidth()
                )
                DialogButtonRow(
                    confirmText = "保存",
                    onConfirm = {
                        Prefs.setRemindTitle(context, remindTitleInput.ifBlank { null })
                        Prefs.setRemindMessage(context, remindMsgInput.ifBlank { null })
                        showRemindTextDialog = false
                        android.widget.Toast.makeText(context, "提醒文案已保存", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showRemindTextDialog = false }
                )
            }
        )
    }

    // 扫码登录对话框
    if (showQrDialog) {
        var polling by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            val qrInfo = withContext(Dispatchers.IO) { MicaApi.createQrLogin(context) }
            if (qrInfo == null) {
                qrStatus = "二维码生成失败，请重试"
                return@LaunchedEffect
            }
            qrBitmap = withContext(Dispatchers.IO) { generateQrBitmap(qrInfo.qrUrl, 600) }
            qrStatus = "请用米游社App扫描二维码…"

            val ticket = qrInfo.ticket
            var polls = 0
            while (polling && polls < 90) { // 最多 90 次 × 2s = 3 分钟
                delay(2000)
                polls++
                val status = withContext(Dispatchers.IO) { MicaApi.pollQrLoginStatus(context, ticket) }
                when (status) {
                    "Scanned" -> qrStatus = "已扫码，请在手机上确认"
                    "Confirmed" -> {
                        qrStatus = "登录成功！"
                        try {
                            withContext(Dispatchers.IO) {
                                val ui = MicaApi.fetchUserInfo(context)
                                if (ui != null) {
                                    Prefs.setNickname(context, ui.nickname)
                                    Prefs.setAvatarUrl(context, ui.avatarUrl)
                                }
                                val roles = MicaApi.validateAccount(context)
                                val role = roles.firstOrNull { it.uid.isNotBlank() }
                                if (role != null) {
                                    Prefs.setUid(context, role.uid)
                                    Prefs.setServer(context, role.region)
                                    if (Prefs.nickname(context).isBlank()) {
                                        Prefs.setNickname(context, role.nickname)
                                    }
                                } else {
                                    throw IllegalStateException("扫码成功，但没有读取到星铁账号。请确认米游社账号已绑定星铁。")
                                }
                                Prefs.saveCurrentAccount(context)
                            }
                            cookieHealthVersion++
                            android.widget.Toast.makeText(context, "登录成功", android.widget.Toast.LENGTH_SHORT).show()
                            delay(800)
                            showQrDialog = false
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(
                                context, e.message ?: "扫码成功，但账号验证失败", android.widget.Toast.LENGTH_LONG
                            ).show()
                            showQrDialog = false
                        }
                        return@LaunchedEffect
                    }
                    "Expired", "Timeout", "Cancelled" -> {
                        qrStatus = "二维码已过期，点击关闭后重试"
                        polling = false
                        return@LaunchedEffect
                    }
                    "RiskControl" -> {
                        qrStatus = "当前设备/网络被风控，请换网络或稍后再试"
                        polling = false
                        return@LaunchedEffect
                    }
                    "Error" -> qrStatus = "扫码查询失败，请关闭后重试"
                }
            }
            if (polling) qrStatus = "二维码已过期，点击关闭后重试"
        }

        // 屏幕居中弹窗：全屏半透明遮罩 + 居中玻璃卡片，二维码与状态文字水平垂直居中。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            BackHandler { polling = false; showQrDialog = false }
            AppCard(modifier = Modifier.width(280.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "米游社扫码登录",
                        style = MiuixTheme.textStyles.title1
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(220.dp)
                        )
                    } else {
                        InfiniteProgressIndicator(modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = qrStatus,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    DialogSingleButton(text = "关闭", onClick = { polling = false; showQrDialog = false })
                }
            }
        }
    }

    // Cookie 导入对话框
    if (showCookieDialog) {
        OverlayDialog(
            show = true,
            title = "导入米游社 Cookie",
            onDismissRequest = { showCookieDialog = false },
            content = {
                TextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = "请粘贴 Cookie",
                    // 固定高度，避免 Cookie 过长把弹窗撑高、挤掉保存按钮
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                DialogButtonRow(
                    confirmText = if (cookieImporting) "导入中…" else "保存",
                    confirmEnabled = !cookieImporting,
                    onConfirm = {
                        scope.launch {
                            cookieImporting = true
                            val (msg, ok) = withContext(Dispatchers.IO) {
                                MicaApi.importRawCookie(context, cookieInput)
                            }
                            cookieImporting = false
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            if (ok) {
                                cookieInput = ""
                                cookieHealthVersion++
                                showCookieDialog = false
                            }
                        }
                    },
                    onDismiss = { showCookieDialog = false }
                )
            }
        )
    }

    // 权限管理对话框
    if (showPermissionDialog) {
        OverlayDialog(
            show = true,
            title = "权限管理",
            onDismissRequest = { showPermissionDialog = false },
            content = {
                // 读取刷新计数建立重组依赖：从系统设置返回后重算下方各授权开关状态
                @Suppress("UNUSED_EXPRESSION") permVersion
                Text(
                    text = "需要以下权限才能正常工作：",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                PermissionRow(
                    title = "无障碍服务",
                    desc = "保持后台打卡提醒常驻，不读取界面内容",
                    enabled = PermissionHelper.isAccessibilityEnabled(context, KeepAliveAccessibilityService::class.java)
                ) { PermissionHelper.openAccessibilitySettings(context, KeepAliveAccessibilityService::class.java) }
                PermissionRow(
                    title = "使用记录权限",
                    desc = "统计游戏游玩时长",
                    enabled = PermissionHelper.hasUsageAccess(context)
                ) { PermissionHelper.openUsageAccessSettings(context) }
                PermissionRow(
                    title = "电池优化",
                    desc = "防止系统杀死应用",
                    enabled = PermissionHelper.isIgnoringBatteryOptimizations(context)
                ) { PermissionHelper.openBatteryOptimizationSettings(context) }
                PermissionRow(
                    title = "通知权限",
                    desc = "发送打卡提醒",
                    enabled = PermissionHelper.hasNotificationPermission(context)
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
                PermissionRow(
                    title = "悬浮窗权限",
                    desc = "后台/锁屏上层弹出提醒窗",
                    enabled = PermissionHelper.canDrawOverlays(context)
                ) { PermissionHelper.openOverlaySettings(context) }
                PermissionRow(
                    title = "后台弹出界面",
                    desc = "MIUI/HyperOS：允许 App 在后台弹出提醒窗（与悬浮窗是两个开关）",
                    enabled = false,
                    fixedHint = "请在应用详情中开启"
                ) { PermissionHelper.openAppDetails(context) }
                PermissionRow(
                    title = "精确闹钟",
                    desc = "按时触发提醒",
                    enabled = PermissionHelper.canScheduleExactAlarm(context)
                ) { PermissionHelper.openExactAlarmSettings(context) }
                PermissionRow(
                    title = "自启动",
                    desc = "开机后自动恢复",
                    enabled = false,
                    fixedHint = "请在系统应用详情中开启"
                ) { PermissionHelper.openAppDetails(context) }
                DialogSingleButton(text = "知道了", onClick = { showPermissionDialog = false })
            }
        )
    }

}

private fun cookieHealthTime(timestamp: Long): String =
    if (timestamp <= 0L) "从未"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        .format(java.util.Date(timestamp))

private fun cookieHealthStatusText(status: String): String = when (status) {
    CookieHealthMonitor.STATUS_HEALTHY -> "健康"
    CookieHealthMonitor.STATUS_INVALID -> "可能已失效"
    CookieHealthMonitor.STATUS_ERROR -> "检查失败"
    else -> "未检查"
}

/** Miuix 无现成 TimePicker，用小时/分钟双 NumberPicker 组合（官方示例做法） */
@Composable
private fun TimePickerRow(
    hour: Int,
    minute: Int,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NumberPicker(
            value = hour,
            onValueChange = onHourChange,
            range = 0..23,
            label = { it.toString().padStart(2, '0') },
            wrapAround = true,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = ":",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        NumberPicker(
            value = minute,
            onValueChange = onMinuteChange,
            range = 0..59,
            label = { it.toString().padStart(2, '0') },
            wrapAround = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 用 ZXing 生成二维码 Bitmap */
private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? {
    return try {
        val hints = mapOf(
            com.google.zxing.EncodeHintType.MARGIN to 1,
            com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
