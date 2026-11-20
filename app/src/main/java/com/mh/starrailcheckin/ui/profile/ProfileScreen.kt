package com.mh.starrailcheckin.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.mh.starrailcheckin.ui.components.DialogButtonRow
import com.mh.starrailcheckin.ui.components.DialogSingleButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.GameRole
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import com.mh.starrailcheckin.network.UserInfo
import com.mh.starrailcheckin.util.CheckinHelper
import com.mh.starrailcheckin.util.CropHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.UsageStatsHelper
import androidx.activity.compose.rememberLauncherForActivityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.mh.starrailcheckin.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onGachaClick: () -> Unit = {},
    onEnkaClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    onOfficialToolsClick: () -> Unit = {},
    onChallengeClick: () -> Unit = {},
    onLedgerClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }

    var userInfo by remember { mutableStateOf<UserInfo?>(null) }
    var roles by remember { mutableStateOf<List<GameRole>>(emptyList()) }
    var avatarBmp by remember { mutableStateOf<Bitmap?>(null) }
    var stamina by remember { mutableStateOf<MicaApi.StaminaInfo?>(null) }
    var staminaError by remember { mutableStateOf<String?>(null) }
    var playDays by remember { mutableStateOf<Int?>(null) }
    var stats by remember { mutableStateOf<MicaApi.PlayerStats?>(null) }
    var statsRefreshing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var staminaLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 相册自定义头像（本地文件优先于米游社头像）
    var customPath by remember { mutableStateOf(Prefs.customAvatarPath(context)) }
    var customBmp by remember { mutableStateOf<Bitmap?>(null) }
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // 裁剪结果落内部 filesDir（持久，不随 cache 清理）
    val avatarCropLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val path = CropHelper.outputUri(result.data)?.path
            if (!path.isNullOrBlank() && File(path).exists()) {
                Prefs.setCustomAvatarPath(context, path)
                customPath = path
                Toast.makeText(context, "头像已设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val avatarPickerLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            val dest = File(dir, "avatar_custom.jpg")
            avatarCropLauncher.launch(
                CropHelper.buildIntent(
                    context, uri, dest,
                    CropHelper.RATIO_AVATAR_X, CropHelper.RATIO_AVATAR_Y
                )
            )
        }
    }

    // 解码本地裁剪头像
    LaunchedEffect(customPath) {
        customBmp = withContext(Dispatchers.IO) {
            runCatching {
                val f = customPath?.let { File(it) }
                if (f != null && f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }.getOrNull()
        }
    }

    /** 读取本账号上次缓存，重进/切页时直接展示，不发起网络请求。 */
    fun readStaminaCache(uid: String): MicaApi.StaminaInfo? {
        if (uid.isBlank()) return null
        val raw = Prefs.staminaCache(context)
        if (raw.isBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optString("uid") != uid) return null
        val expArr = o.optJSONArray("expeditions")
        val expeditions = (0 until (expArr?.length() ?: 0)).mapNotNull { i ->
            val e = expArr?.optJSONObject(i) ?: return@mapNotNull null
            MicaApi.ExpeditionInfo(
                remainingTime = e.optLong("remainingTime", 0),
                status = e.optString("status", ""),
                name = e.optString("name", "")
            )
        }
        return MicaApi.StaminaInfo(
            current = o.optInt("current", 0),
            max = o.optInt("max", 240),
            recoverTimeSec = o.optLong("recover", 0),
            expeditions = expeditions,
            reserve = o.optInt("reserve", 0),
            reserveMax = o.optInt("reserveMax", 2400),
            trainScore = o.optInt("train", 0),
            trainMax = o.optInt("trainMax", 500),
            cocoonCnt = o.optInt("cocoon", 0),
            cocoonLimit = o.optInt("cocoonLimit", 3),
            rogueScore = o.optInt("rogue", 0),
            rogueMax = o.optInt("rogueMax", 18000)
        )
    }

    fun saveStaminaCache(uid: String, info: MicaApi.StaminaInfo) {
        if (uid.isBlank()) return
        val expArr = org.json.JSONArray()
        info.expeditions.forEach { e ->
            expArr.put(org.json.JSONObject()
                .put("name", e.name)
                .put("status", e.status)
                .put("remainingTime", e.remainingTime))
        }
        val json = runCatching {
            JSONObject()
                .put("uid", uid)
                .put("current", info.current)
                .put("max", info.max)
                .put("recover", info.recoverTimeSec)
                .put("expeditions", expArr)
                .put("reserve", info.reserve)
                .put("reserveMax", info.reserveMax)
                .put("train", info.trainScore)
                .put("trainMax", info.trainMax)
                .put("cocoon", info.cocoonCnt)
                .put("cocoonLimit", info.cocoonLimit)
                .put("rogue", info.rogueScore)
                .put("rogueMax", info.rogueMax)
                .toString()
        }.getOrNull() ?: return
        Prefs.setStaminaCache(context, json, uid)
    }

    /** 只读本地资料：昵称/头像/UID 来自登录时写入的 Prefs，导入 Cookie 后不再刷新。 */
    fun loadLocalProfile() {
        val savedNickname = Prefs.nickname(context)
        val savedAvatar = Prefs.avatarUrl(context)
        userInfo = if (savedNickname.isNotBlank() || savedAvatar.isNotBlank()) {
            UserInfo(savedNickname, savedAvatar)
        } else {
            null
        }
        val uid = Prefs.uid(context)
        playDays = Prefs.playDays(context, uid).takeIf { it > 0 }
        stats = MicaApi.PlayerStats(
            activeDays = playDays ?: 0,
            avatarNum = Prefs.avatarNum(context, uid),
            achievementNum = Prefs.achievementNum(context, uid),
            chestNum = Prefs.chestNum(context, uid),
            fetchedAt = Prefs.statsFetchedAt(context, uid)
        ).takeIf { it.activeDays > 0 || it.avatarNum > 0 || it.achievementNum > 0 || it.chestNum > 0 }
        stamina = readStaminaCache(uid)
        loading = false
    }

    /**
     * 刷新体力卡片与游玩天数。
     * force=false 时严格按 1 小时时间戳节流：重进软件、切换页面都不会触发刷新。
     */
    fun refreshProfileData(force: Boolean) {
        if (!Prefs.isLoggedIn(context)) {
            staminaLoading = false
            return
        }
        if (staminaLoading) return
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.profileRefreshAt(context) < 60 * 60 * 1000L) return
        scope.launch {
            staminaLoading = true
            withContext(Dispatchers.IO) {
                val uid = Prefs.uid(context)
                val openedToday = UsageStatsHelper.getTodayGameUsage(context).opened
                runCatching { MicaApi.fetchPlayerStats(context, force = force) }
                    .onSuccess { s ->
                        stats = s
                        playDays = s.activeDays
                        // 先维护今日基线（今天没开游戏才抬高，防隔夜未同步污染），再走统一判定：
                        // 手动刷新看到相对基准 +1 及以上立即落打卡标记，不等 20 分钟后台验证
                        CheckinHelper.noteDaysFetched(context, s.activeDays, openedToday)
                        CheckinHelper.checkAndMarkChecked(context, s.activeDays)
                    }
                runCatching { MicaApi.fetchStamina(context) }
                    .onSuccess {
                        stamina = it
                        staminaError = null
                        saveStaminaCache(uid, it)
                    }
                    .onFailure { e -> staminaError = staminaMessage(e) }
                Prefs.setProfileRefreshAt(context, System.currentTimeMillis())
            }
            staminaLoading = false
        }
    }

    LaunchedEffect(reload) {
        loadLocalProfile()
        // 仅切换账号（缓存 UID 不一致）时强制拉一次；
        // 普通重进 / 首次进入 都只读本地缓存不联网（刷新仅由手动按钮触发或 Worker 检测到游戏打开触发）
        val switchedAccount = Prefs.staminaCacheUid(context).isNotBlank() &&
            Prefs.staminaCacheUid(context) != Prefs.uid(context)
        if (switchedAccount) refreshProfileData(force = true)
    }

    // 从多账号页/登录页返回时只同步本地资料，不发起网络刷新
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        // 新 observer 添加时 Lifecycle 会立即补发一次 ON_RESUME，跳过它避免首屏重复加载
        var firstEvent = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstEvent) firstEvent = false else reload++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val avatarUrl = userInfo?.avatarUrl?.takeIf { it.isNotBlank() } ?: Prefs.avatarUrl(context)

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isBlank()) {
            avatarBmp = null
            return@LaunchedEffect
        }
        avatarBmp = withContext(Dispatchers.IO) {
            val cacheFile = File(context.filesDir, "avatar_remote.img")
            // 同一 URL 已有本地缓存：直接解码，不重复下载（导入 Cookie 后头像不再刷新）
            if (cacheFile.exists() && Prefs.remoteAvatarCachedUrl(context) == avatarUrl) {
                val cached = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
                if (cached != null) return@withContext cached
            }
            runCatching {
                val req = Request.Builder().url(avatarUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://bbs.mihoyo.com/")
                    .header("Cookie", Prefs.cookie(context))
                    .get().build()
                avatarClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else BitmapFactory.decodeStream(resp.body?.byteStream())
                }
            }.getOrNull()?.also { bmp ->
                runCatching {
                    FileOutputStream(cacheFile).use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    Prefs.setRemoteAvatarCachedUrl(context, avatarUrl)
                }
            }
        }
    }

    fun refreshStamina() = refreshProfileData(force = true)

    /** 双击统计卡：强制刷新活跃天数 / 角色 / 战利品 / 成就数。 */
    fun refreshStats() {
        if (statsRefreshing) return
        if (!Prefs.isLoggedIn(context)) {
            Toast.makeText(context, "请先导入 Cookie 登录", Toast.LENGTH_SHORT).show()
            return
        }
        statsRefreshing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val openedToday = UsageStatsHelper.getTodayGameUsage(context).opened
                runCatching { MicaApi.fetchPlayerStats(context, force = true) }
                    .onSuccess { s ->
                        // 维护基线后走统一判定：手动刷新 +1 及以上立即落打卡标记
                        CheckinHelper.noteDaysFetched(context, s.activeDays, openedToday)
                        CheckinHelper.checkAndMarkChecked(context, s.activeDays)
                    }
            }
            statsRefreshing = false
            result.onSuccess { s ->
                stats = s
                playDays = s.activeDays
            }.onFailure { e ->
                val msg = when (e) {
                    is MicaException -> e.message ?: "刷新失败"
                    else -> e.message ?: "网络异常，请稍后重试"
                }
                Toast.makeText(context, "统计刷新失败：$msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val nickname = userInfo?.nickname?.takeIf { it.isNotBlank() }
        ?: Prefs.nickname(context).takeIf { it.isNotBlank() }
        ?: "星铁开拓者"
    val uid = Prefs.uid(context).takeIf { it.isNotBlank() }
    val regionLabel = Prefs.server(context).takeIf { it.isNotBlank() }?.let { MicaApi.regionName(it) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // 头部卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.primary)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable { showAvatarDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = customBmp ?: avatarBmp
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "头像",
                            modifier = Modifier.size(60.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "头像",
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nickname,
                        style = smallStyle(MiuixTheme.textStyles.title1, 18),
                        color = MiuixTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            loading -> "加载中…"
                            !uid.isNullOrBlank() -> buildString {
                                append("UID: $uid")
                                if (!regionLabel.isNullOrBlank()) append("  ·  $regionLabel")
                            }
                            Prefs.cookie(context).isBlank() -> "未导入 Cookie"
                            else -> "未读取到星穹铁道角色"
                        },
                        style = smallStyle(MiuixTheme.textStyles.body2, 13),
                        color = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 体力卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_gold_sparkle),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "实时便笺",
                        style = smallStyle(MiuixTheme.textStyles.title1, 17),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "刷新",
                        style = smallStyle(MiuixTheme.textStyles.body2, 13),
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = !staminaLoading) { refreshStamina() }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    staminaLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在刷新…", style = smallStyle(MiuixTheme.textStyles.body2, 13), color = MiuixTheme.colorScheme.onSurfaceSecondary)
                    }
                    stamina != null -> stamina?.let { StaminaBody(it) }
                    !staminaError.isNullOrBlank() -> Text(
                        text = staminaError ?: "",
                        style = smallStyle(MiuixTheme.textStyles.body2, 13),
                        color = MiuixTheme.colorScheme.error
                    )
                    else -> Text(
                        text = "暂未获取到体力数据",
                        style = smallStyle(MiuixTheme.textStyles.body2, 13),
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 账号统计：活跃天数 / 已解锁角色 / 战利品开启 / 达成成就数（双击卡片刷新）
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { refreshStats() })
                }
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProfileStatCell(
                        stats?.activeDays?.toString() ?: playDays?.toString() ?: "—",
                        "活跃天数", Modifier.weight(1f)
                    )
                    ProfileStatCell(
                        stats?.avatarNum?.toString() ?: "—",
                        "已解锁角色", Modifier.weight(1f)
                    )
                    ProfileStatCell(
                        stats?.chestNum?.toString() ?: "—",
                        "战利品开启", Modifier.weight(1f)
                    )
                    ProfileStatCell(
                        stats?.achievementNum?.toString() ?: "—",
                        "达成成就数", Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (statsRefreshing) "刷新中…" else "（双击卡片可刷新）",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }

        error?.let { msg ->
            Spacer(modifier = Modifier.height(12.dp))
            AppCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = msg,
                        style = smallStyle(MiuixTheme.textStyles.body2, 13),
                        color = MiuixTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(text = "重新加载", onClick = { reload++ })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 功能列表卡片
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                ProfileMenuItem(R.drawable.ic_menu_challenge, "逐光捡金-战绩查询", "异相仲裁 · 忘却之庭 · 虚构叙事 · 末日幻影", onClick = onChallengeClick)
                ProfileMenuItem(R.drawable.ic_menu_toolbox, "开拓月历", "本月星琼 · 通票 · 专票收支明细", onClick = onLedgerClick)
                ProfileMenuItem(R.drawable.ic_menu_gacha, "抽卡记录", "查看跃迁记录统计", onClick = onGachaClick)
                ProfileMenuItem(R.drawable.ic_menu_character, "角色面板", "Enka 角色橱窗", onClick = onEnkaClick)
                ProfileMenuItem(R.drawable.ic_menu_accounts, "多账号管理", "切换账号、批量签到", onClick = onAccountsClick)
                ProfileMenuItem(R.drawable.ic_menu_toolbox, "养成工具箱", "官方养成建议 · 材料计算 · 阵容", onClick = onOfficialToolsClick)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 退出登录
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogoutDialog = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("退出登录", style = smallStyle(MiuixTheme.textStyles.title3, 15), color = MiuixTheme.colorScheme.error)
            }
        }
    }

    // 头像选择：相册裁剪 / 恢复默认（Miuix OverlayDialog）
    if (showAvatarDialog) {
        OverlayDialog(
            show = true,
            title = "头像",
            onDismissRequest = { showAvatarDialog = false },
            content = {
                TextButton(
                    text = "从相册选择并裁剪",
                    onClick = {
                        showAvatarDialog = false
                        avatarPickerLauncher.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!customPath.isNullOrBlank()) {
                    TextButton(
                        text = "恢复默认头像",
                        onClick = {
                            Prefs.setCustomAvatarPath(context, null)
                            customPath = null
                            customBmp = null
                            showAvatarDialog = false
                            Toast.makeText(context, "已恢复默认头像", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                DialogSingleButton(text = "取消", onClick = { showAvatarDialog = false }, primary = false)
            }
        )
    }

    // 退出登录确认（Miuix OverlayDialog）
    if (showLogoutDialog) {
        OverlayDialog(
            show = true,
            title = "退出登录",
            onDismissRequest = { showLogoutDialog = false },
            content = {
                Text(
                    text = "确定要清除本地 Cookie 与账号缓存并退出登录吗？",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                DialogButtonRow(
                    confirmText = "退出",
                    onConfirm = {
                        Prefs.clearLogin(context)
                        Prefs.setCustomAvatarPath(context, null)
                        userInfo = null
                        roles = emptyList()
                        avatarBmp = null
                        stamina = null
                        staminaError = null
                        playDays = null
                        error = null
                        customPath = null
                        customBmp = null
                        showLogoutDialog = false
                        Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { showLogoutDialog = false }
                )
            }
        )
    }
}

@Composable
private fun StaminaBody(stamina: MicaApi.StaminaInfo) {
    val primary = MiuixTheme.colorScheme.primary
    val labelColor = MiuixTheme.colorScheme.onSurface
    val subColor = MiuixTheme.colorScheme.onSurfaceSecondary
    val divider = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ResourceLine(
                iconRes = R.drawable.ic_trailblaze_power,
                value = "${stamina.current}/${stamina.max}",
                label = staminaEta(stamina.recoverTimeSec),
                labelColor = if (stamina.recoverTimeSec <= 0) primary else subColor
            )
            Spacer(modifier = Modifier.height(16.dp))
            ResourceLine(
                iconRes = R.drawable.ic_reserve_power,
                value = "${stamina.reserve}",
                label = "后备开拓力",
                labelColor = subColor
            )
        }

        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .width(1.dp)
                .height(96.dp)
                .background(divider)
        )

        Column(
            modifier = Modifier.weight(1.05f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatLine("每日实训", "${stamina.trainScore}/${stamina.trainMax}", labelColor, primary)
            MiniBar(stamina.trainScore, stamina.trainMax, primary)
            StatLine("历战余响剩余", "${stamina.cocoonCnt}/${stamina.cocoonLimit}", labelColor, primary)
            MiniBar(stamina.cocoonCnt, stamina.cocoonLimit, primary)
            StatLine("周期积分", "${stamina.rogueScore}/${stamina.rogueMax}", labelColor, primary)
            MiniBar(stamina.rogueScore, stamina.rogueMax, primary)
        }
    }

    if (stamina.expeditions.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(divider))
        Spacer(modifier = Modifier.height(10.dp))
        Text("派遣委托", style = smallStyle(MiuixTheme.textStyles.body1, 14), color = labelColor)
        Spacer(modifier = Modifier.height(6.dp))
        stamina.expeditions.forEachIndexed { i, exp ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    exp.name.ifBlank { "委托 ${i + 1}" },
                    fontSize = 13.sp,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    expeditionEta(exp.remainingTime),
                    fontSize = 12.sp,
                    color = subColor
                )
            }
        }
    }
}

/** 细进度条：当前/上限。 */
@Composable
private fun MiniBar(current: Int, max: Int, color: Color) {
    val frac = if (max <= 0) 0f else (current.toFloat() / max).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(frac)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}

/** 体力恢复倒计时文案。 */
private fun staminaEta(sec: Long): String {
    if (sec <= 0) return "已回满"
    return "约 ${durationText(sec)}后恢复"
}

/** 派遣剩余时间文案。 */
private fun expeditionEta(sec: Long): String {
    if (sec <= 0) return "已完成"
    return "剩余 ${durationText(sec)}"
}

private fun durationText(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return when {
        h > 0 -> "${h}时${m}分"
        m > 0 -> "${m}分${sec % 60}秒"
        else -> "${sec}秒"
    }
}

@Composable
private fun ResourceLine(
    iconRes: Int,
    value: String,
    label: String,
    labelColor: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = value,
                style = smallStyle(MiuixTheme.textStyles.title3, 18),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = label,
                style = smallStyle(MiuixTheme.textStyles.footnote1, 12),
                color = labelColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatLine(title: String, value: String, titleColor: Color, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = smallStyle(MiuixTheme.textStyles.body1, 14), color = titleColor, maxLines = 1)
        Text(
            text = value,
            style = smallStyle(MiuixTheme.textStyles.title3, 16),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
    }
}

/** 体力异常 → 可读提示（10041/10045 为设备/网络风控，单独说明）。 */
private fun staminaMessage(e: Throwable): String {
    return when (e) {
        is MicaException -> when (e.retcode) {
            10041, 10045 -> "接口风控限制，暂无法获取实时开拓力（可在游戏内查看）"
            else -> MicaApi.retcodeMessage(e.retcode).ifBlank { e.message ?: "体力刷新失败" }
        }
        else -> e.message ?: "体力刷新失败"
    }
}

/** 头像下载专用客户端（短超时，失败回退占位图标）。 */
private val avatarClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** 秒 → HH:mm:ss（不足 1 小时则 mm:ss）。 */
private fun formatDuration(sec: Long): String {
    val h = TimeUnit.SECONDS.toHours(sec)
    val m = TimeUnit.SECONDS.toMinutes(sec) % 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, s)
    else String.format(Locale.CHINA, "%02d:%02d", m, s)
}

@Composable
private fun ProfileStatCell(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun ProfileMenuItem(iconRes: Int, title: String, subtitle: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(30.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = smallStyle(MiuixTheme.textStyles.title3, 15)
            )
            Text(
                text = subtitle,
                style = smallStyle(MiuixTheme.textStyles.footnote1, 12),
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }

        Text(
            text = ">",
            style = smallStyle(MiuixTheme.textStyles.title1, 16),
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}

/** 我的页统一压缩字号：保留原 TextStyle 字重/颜色，仅缩小字号与行高。 */
private fun smallStyle(base: TextStyle, size: Int): TextStyle =
    base.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp)
