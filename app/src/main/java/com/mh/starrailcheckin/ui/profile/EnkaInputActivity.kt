package com.mh.starrailcheckin.ui.profile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.LogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 角色橱窗查询二级页：输入 UID → 调 enka.network(Mihomo) → 跳 EnkaShowcaseActivity。
 * Compose + Miuix 重写；查询/重试/适配层/历史快照/缓存回退逻辑与旧 View 版一致。
 */
class EnkaInputActivity : LightBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色星空背景：状态栏图标用浅色
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme(darkTheme = false, forceDark = true) {
                    // Scaffold 内置 Miuix 弹窗宿主，否则 OverlayDialog 无处挂载、点击无响应
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        EnkaInputScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun EnkaInputScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uidInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(EnkaHistory.list(context)) }
    var showManage by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EnkaHistory.Entry?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var cachedFallback by remember { mutableStateOf<EnkaHistory.Entry?>(null) }

    // 回填上次查询的 UID
    LaunchedEffect(Unit) {
        uidInput = context.getSharedPreferences("enka_prefs", Context.MODE_PRIVATE).getString("last_uid", "") ?: ""
    }

    fun refreshHistory() {
        history = EnkaHistory.list(context)
    }

    fun openShowcase(rawJson: String) {
        if (rawJson.isBlank()) {
            Toast.makeText(context, "该记录数据已损坏", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, EnkaShowcaseActivity::class.java)
        intent.putExtra("raw_json", rawJson)
        context.startActivity(intent)
    }

    fun queryEnka() {
        val uid = uidInput.trim()
        if (uid.length < 9) {
            status = "请输入9位数字UID"
            return
        }
        loading = true
        status = "正在查询角色橱窗…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    // 先尝试刷新远端角色/光锥字典（失败静默回退内置与缓存）
                    runCatching { EnkaNameMap.refreshRemote(context) }
                    var lastErr: Throwable? = null
                    var success: EnkaResult? = null
                    repeat(3) { attempt ->
                        val r = runCatching { fetchEnka(context, uid) }
                        if (r.isSuccess) {
                            success = r.getOrThrow()
                            return@repeat
                        }
                        lastErr = r.exceptionOrNull()
                        if (!isRetryable(lastErr) || attempt == 2) return@repeat
                        delay(if (attempt == 0) 3000 else 5000)
                    }
                    success ?: throw (lastErr ?: Exception("查询失败，请稍后重试"))
                }
            }.getOrElse {
                LogHelper.e("Enka", "查询失败", it)
                status = mapEnkaError(it)
                // 联网失败时，若该 UID 有历史快照，允许回退查看缓存
                val cached = EnkaHistory.list(context).firstOrNull { e -> e.uid == uid }
                if (cached != null) cachedFallback = cached
                null
            }
            loading = false
            if (result == null) return@launch
            context.getSharedPreferences("enka_prefs", Context.MODE_PRIVATE).edit().putString("last_uid", uid).apply()
            status = result.status
            runCatching { EnkaHistory.save(context, uid, result.nickname, result.raw) }
                .onFailure { LogHelper.e("Enka", "历史保存失败", it) }
            refreshHistory()
            openShowcase(result.raw)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 星空碎石背景（竖屏裁切适配）
        Image(
            painter = painterResource(R.drawable.enka_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 深色压暗遮罩：保留星空氛围，顶部加深保证浅色标题可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xE60A0D18),
                            Color(0x990A0D18),
                            Color(0xB30A0D18)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.Top
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "角色橱窗查询",
                fontSize = 20.sp,
                color = Color.White
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            AppCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = uidInput,
                        onValueChange = { uidInput = it },
                        label = "输入 9 位 UID",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { queryEnka() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("查询角色橱窗")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "查询说明",
                            onClick = { showHelp = true },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "历史管理",
                            onClick = { showManage = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // 全角色面板查询入口（Cookie 战绩接口，与 Enka 橱窗相互独立）
            Spacer(Modifier.height(12.dp))
            AppCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(
                                Intent(context, AllCharactersActivity::class.java)
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "全角色面板查询",
                            fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "需要登录且获取到Cookie",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Text(
                        text = "›",
                        fontSize = 22.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
        }
    }

    // 查询说明弹窗
    if (showHelp) {
        OverlayDialog(
            show = true,
            title = "查询说明",
            onDismissRequest = { showHelp = false },
            content = {
                Text(
                    text = "1. 在游戏内个人头像页查看 9 位 UID\n" +
                        "2. 该玩家需在米游社开启【角色详情对外展示】\n" +
                        "3. 数据来源于 Mihomo（鳄梨），国内访问速度快\n" +
                        "4. 面板为站街白板数值，含光锥常驻被动与遗器套装效果\n" +
                        "5. 首次加载可能需要几秒钟，请耐心等待",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(text = "知道了", onClick = { showHelp = false })
                }
            }
        )
    }

    // 历史管理弹窗
    if (showManage) {
        OverlayDialog(
            show = true,
            title = "管理记录",
            onDismissRequest = { showManage = false },
            content = {
                Column {
                    if (history.isEmpty()) {
                        Text(
                            text = "暂无历史记录，成功查询后会自动保存角色快照",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        history.forEach { entry ->
                            val who = if (entry.nickname.isBlank()) "未知" else entry.nickname
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openShowcase(entry.rawJson) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "UID ${entry.uid.ifBlank { "未知" }} · $who",
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = entry.date,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                Text(
                                    text = "删除",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { pendingDelete = entry }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "清空全部",
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "关闭",
                            onClick = { showManage = false },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }

    // 清空二次确认
    if (showClearConfirm) {
        OverlayDialog(
            show = true,
            title = "确认清空",
            onDismissRequest = { showClearConfirm = false },
            content = {
                Text(
                    text = "确定清空全部角色橱窗查询历史？",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { showClearConfirm = false },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "清空",
                        onClick = {
                            EnkaHistory.clear(context)
                            refreshHistory()
                            showClearConfirm = false
                            showManage = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }

    // 单条删除二次确认
    pendingDelete?.let { entry ->
        val who = if (entry.nickname.isBlank()) "未知" else entry.nickname
        OverlayDialog(
            show = true,
            title = "删除记录",
            onDismissRequest = { pendingDelete = null },
            content = {
                Text(
                    text = "确定删除 UID ${entry.uid.ifBlank { "未知" }} · $who 的查询记录？",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { pendingDelete = null },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "删除",
                        onClick = {
                            EnkaHistory.delete(context, entry.index)
                            refreshHistory()
                            pendingDelete = null
                            if (history.isEmpty()) showManage = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }

    // 联网失败缓存回退弹窗
    cachedFallback?.let { cached ->
        OverlayDialog(
            show = true,
            title = "查看缓存",
            onDismissRequest = { cachedFallback = null },
            content = {
                Text(
                    text = "联网获取失败，是否查看上次保存的面板缓存？",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { cachedFallback = null },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "查看缓存",
                        onClick = {
                            openShowcase(cached.rawJson)
                            cachedFallback = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }
}

// ===== 以下为纯数据层：与旧 View 版适配/错误映射保持一致，不依赖 Activity =====

private data class EnkaResult(val status: String, val nickname: String, val raw: String)

private class EnkaApiException(val httpCode: Int, val detail: String) : Exception(detail)

/** 单次拉取 Mihomo 面板并适配为内部 Enka 结构。 */
private suspend fun fetchEnka(ctx: Context, uid: String): EnkaResult {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val req = okhttp3.Request.Builder()
        .url("https://api.mihomo.me/sr_info_parsed/$uid?lang=cn")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.5359.128 Mobile Safari/537.36")
        .header("Accept", "application/json, text/plain, */*")
        .header("Referer", "https://enka.network/")
        .get().build()
    return client.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) throw EnkaApiException(resp.code, "响应为空")
        // 响应可能被截断/损坏，JSON 解析失败视为可重试
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw EnkaApiException(resp.code, "响应不完整，请重试")
        }
        val detail = json.optString("detail", "")
        if (detail.isNotBlank()) throw EnkaApiException(resp.code, detail)
        // parsed 结构必须带 characters；缺角色明细属于账号未展示，不重试
        val chars = json.optJSONArray("characters")
        if (chars == null || chars.length() == 0) {
            throw EnkaApiException(resp.code, "该玩家未开启角色橱窗或无角色展示")
        }
        // Mihomo 格式 → Enka 格式适配
        val adapted = adaptMihomoToEnka(json)
        val adaptedChars = adapted.optJSONObject("detailInfo")?.optJSONArray("avatarDetailList")
        if (adaptedChars == null || adaptedChars.length() == 0) {
            throw EnkaApiException(resp.code, "面板数据解析为空")
        }
        // 沉淀 parsed 已识别的角色名到本地字典
        for (i in 0 until chars.length()) {
            val c = chars.optJSONObject(i) ?: continue
            EnkaNameMap.rememberCharName(ctx, c.optInt("id", 0), c.optString("name", ""))
        }
        // raw 接口兜底：补回 parsed 字典尚未收录、被剔除的新角色骨架
        mergeRawFallback(client, adapted, uid)
        val nickname = adapted.optJSONObject("detailInfo")?.optString("nickname", "未知") ?: "未知"
        val pLevel = adapted.optJSONObject("detailInfo")?.optInt("level", 0) ?: 0
        EnkaResult("玩家：$nickname（开拓等级$pLevel）", nickname, adapted.toString())
    }
}

/**
 * raw 接口（sr_info）直出游戏原始数据，不做服务端字典翻译。
 * 当 parsed 字典未收录新角色而将其剔除时，用 raw 结果把这些角色以骨架形式补回面板，
 * 仅带等级/光锥位，遗器与技能留空由展示页兜底；失败不影响主流程。
 */
private fun mergeRawFallback(client: okhttp3.OkHttpClient, adapted: JSONObject, uid: String) {
    runCatching {
        val rawReq = okhttp3.Request.Builder()
            .url("https://api.mihomo.me/sr_info/$uid")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .header("Referer", "https://enka.network/")
            .get().build()
        val rawBody = client.newCall(rawReq).execute().use { it.body?.string().orEmpty() }
        if (rawBody.isBlank()) {
            LogHelper.w("Enka", "raw 兜底响应为空，未补回未收录角色")
            return
        }
        val rawJson = try { JSONObject(rawBody) } catch (_: Exception) {
            LogHelper.w("Enka", "raw 兜底 JSON 解析失败")
            return
        }
        val rawChars = rawJson.optJSONObject("detailInfo")?.optJSONArray("avatarDetailList") ?: run {
            LogHelper.w("Enka", "raw 兜底无 avatarDetailList")
            return
        }
        val detail = adapted.optJSONObject("detailInfo") ?: return
        val adaptedChars = detail.optJSONArray("avatarDetailList") ?: return
        val known = mutableSetOf<Int>()
        for (i in 0 until adaptedChars.length()) {
            known.add(adaptedChars.optJSONObject(i)?.optInt("avatarId", 0) ?: 0)
        }
        LogHelper.i("Enka", "raw 兜底：上游 ${rawChars.length()} 个角色，parsed 已收录 ${known.size} 个")
        for (i in 0 until rawChars.length()) {
            val rc = rawChars.optJSONObject(i) ?: continue
            val aid = rc.optInt("avatarId", 0)
            if (aid == 0 || known.contains(aid)) continue
            LogHelper.i("Enka", "raw 兜底补回新角色 aid=$aid")
            val skeleton = JSONObject()
            skeleton.put("avatarId", aid)
            skeleton.put("level", rc.optInt("level", 0))
            skeleton.put("promotion", rc.optInt("promotion", 0))
            skeleton.put("name", EnkaNameMap.charName(aid))
            val eq = rc.optJSONObject("equipment")
            if (eq != null) {
                val eqOut = JSONObject()
                eqOut.put("tid", eq.optInt("tid", 0))
                eqOut.put("level", eq.optInt("level", 0))
                eqOut.put("rank", eq.optInt("rank", 0))
                skeleton.put("equipment", eqOut)
            }
            // 骨架先入列：保证角色至少像 stage33 一样出现在头像栏，后续翻译失败不致其消失
            adaptedChars.put(skeleton)
            known.add(aid)
            // 遗器翻译 / 属性累加 / 元素命途标签在独立容错块内，单项失败回退空遗器骨架
            runCatching {
                val relicArr = org.json.JSONArray()
                val rawRelics = rc.optJSONArray("relicList")
                if (rawRelics != null) {
                    for (k in 0 until rawRelics.length()) {
                        val rr = rawRelics.optJSONObject(k) ?: continue
                        runCatching { EnkaNameMap.translateRawRelic(rr) }
                            .getOrNull()?.let { relicArr.put(it) }
                    }
                }
                skeleton.put("relicList", relicArr)
                // 遗器增量（绿色）+ 基础白值 → 组装骨架最终面板
                val relicBonus = EnkaNameMap.buildStatsFromRelics(relicArr)
                val baseAttrs = EnkaNameMap.combinedBaseAttrs(aid, rc.optInt("promotion", 0), rc.optInt("level", 0), eq)
                baseAttrs?.let { skeleton.put("_baseAttrs", it) }
                skeleton.put("_additions", EnkaNameMap.buildSkeletonAdditions(relicBonus))
                skeleton.put("_stats", EnkaNameMap.buildSkeletonStats(baseAttrs, relicBonus))
                skeleton.put("elementName", EnkaNameMap.elementNameOf(EnkaNameMap.charElement(aid)))
                skeleton.put("pathName", EnkaNameMap.pathNameOf(EnkaNameMap.charPath(aid)))
            }.onFailure { LogHelper.e("Enka", "骨架遗器翻译失败 aid=$aid", it) }
            // 行迹节点透传 + 技能详情（在线表收录该角色后自动带出）
            runCatching {
                val treeList = rc.optJSONArray("skillTreeList")
                skeleton.put("skillTreeList", treeList ?: org.json.JSONArray())
                skeleton.put("skillsDetail", EnkaNameMap.buildSkillsDetail(treeList, aid))
            }.onFailure { LogHelper.e("Enka", "骨架白值/技能组装失败 aid=$aid", it) }
        }
    }.onFailure { LogHelper.e("Enka", "raw 兜底合并失败", it) }
}

/** 排队超时 / 5xx / 读超时属于可恢复错误，值得重试；UID/DNS 类问题不重试。 */
private fun isRetryable(e: Throwable?): Boolean {
    return when (e) {
        is EnkaApiException -> {
            if (e.detail.contains("未开启") || e.detail.contains("未展示") ||
                e.detail.contains("无效") || e.detail.contains("为空")
            ) return false
            e.httpCode in 500..599 || e.detail.contains("queue", true)
        }
        is java.net.SocketTimeoutException -> true
        else -> false
    }
}

private fun stripDiscord(msg: String): String =
    msg.replace(Regex("https://discord\\.gg/\\S+", RegexOption.IGNORE_CASE), "").trim()

private fun mapEnkaError(e: Throwable): String {
    return when (e) {
        is EnkaApiException -> when {
            e.detail.contains("queue", true) -> "面板服务繁忙（排队超时），请稍后重试"
            e.httpCode in 500..599 -> "面板服务暂时不可用（${e.httpCode}），请稍后重试"
            e.detail.contains("wrong_uid", true) -> "UID 无效或该玩家未开启角色橱窗"
            else -> stripDiscord(e.detail).ifBlank { "查询失败，请稍后重试" }
        }
        is java.net.SocketTimeoutException -> "网络超时：面板服务响应慢，请检查网络后重试"
        is java.net.UnknownHostException -> "DNS 解析失败，请检查网络"
        else -> e.message?.let { "查询失败：${stripDiscord(it)}" } ?: "查询失败，请稍后重试"
    }
}

/**
 * Mihomo sr_info_parsed → Enka detailInfo 格式适配层
 * 把 Mihomo 返回的 JSON 转成现有 ShowcaseActivity 能解析的 Enka 结构
 */
private fun adaptMihomoToEnka(mihomo: JSONObject): JSONObject {
    val out = JSONObject()
    val player = mihomo.optJSONObject("player")
    val chars = mihomo.optJSONArray("characters")

    // UID
    val uid = player?.optString("uid", "") ?: ""
    out.put("uid", uid)

    // detailInfo 包装
    val detail = JSONObject()
    detail.put("nickname", player?.optString("nickname", "未知") ?: "未知")
    detail.put("level", player?.optInt("level", 0) ?: 0)

    // 角色列表
    val avatarList = org.json.JSONArray()
    if (chars != null) {
        for (i in 0 until chars.length()) {
            val c = chars.optJSONObject(i) ?: continue
            val enkaChar = JSONObject()
            enkaChar.put("avatarId", c.optInt("id", 0))
            enkaChar.put("name", c.optString("name", ""))
            enkaChar.put("level", c.optInt("level", 0))
            enkaChar.put("rank", c.optInt("rank", 0))
            enkaChar.put("portrait", c.optString("portrait", ""))
            enkaChar.put("icon", c.optString("icon", ""))
            enkaChar.put("rarity", c.optInt("rarity", 0))

            // 元素 + 命途
            val elem = c.optJSONObject("element")
            if (elem != null) {
                enkaChar.put("elementName", elem.optString("name", ""))
                enkaChar.put("elementIcon", elem.optString("icon", ""))
            }
            val path = c.optJSONObject("path")
            if (path != null) {
                enkaChar.put("pathName", path.optString("name", ""))
                enkaChar.put("pathIcon", path.optString("icon", ""))
            }

            // 技能详情（图标 + 等级）：只保留 5 个主技能（普攻/战技/终结技/天赋/秘技），
            // 奖励关、行迹大节点、欢愉技等不在技能行展示。
            val skillsArr = c.optJSONArray("skills")
            if (skillsArr != null) {
                val skillsOut = org.json.JSONArray()
                val mainSuffix = setOf(1, 2, 3, 4, 7)
                for (k in 0 until skillsArr.length()) {
                    val sk = skillsArr.optJSONObject(k) ?: continue
                    val skId = sk.optInt("id", 0)
                    if (skId % 100 !in mainSuffix) continue
                    val skOut = JSONObject()
                    skOut.put("id", skId)
                    skOut.put("name", sk.optString("name", ""))
                    skOut.put("level", sk.optInt("level", 1))
                    skOut.put("maxLevel", sk.optInt("max_level", 10))
                    skOut.put("type", sk.optString("type", ""))
                    skOut.put("typeText", sk.optString("type_text", ""))
                    skOut.put("icon", sk.optString("icon", ""))
                    skillsOut.put(skOut)
                }
                enkaChar.put("skillsDetail", skillsOut)
            }

            // 基础白值（用于绿色加成显示）
            val attrs = c.optJSONArray("attributes")
            if (attrs != null) enkaChar.put("_baseAttrs", attrs)

            // 遗器/光锥带来的绿色增量（上游直接给出，含百分比词条；优先于最终值−白值推算）
            val adds = c.optJSONArray("additions")
            if (adds != null) enkaChar.put("_additions", adds)

            // 光锥
            val lc = c.optJSONObject("light_cone")
            if (lc != null) {
                val eq = JSONObject()
                eq.put("tid", lc.optInt("id", 0))
                eq.put("level", lc.optInt("level", 0))
                eq.put("rank", lc.optInt("rank", 0))
                enkaChar.put("equipment", eq)
            }

            // 遗器
            val relics = c.optJSONArray("relics")
            val relicList = org.json.JSONArray()
            if (relics != null) {
                for (j in 0 until relics.length()) {
                    val r = relics.optJSONObject(j) ?: continue
                    val relic = JSONObject()
                    relic.put("tid", r.optInt("id", 0))
                    relic.put("type", r.optInt("type", 0))
                    relic.put("level", r.optInt("level", 0))
                    relic.put("rarity", r.optInt("rarity", 5))
                    relic.put("setName", r.optString("set_name", ""))
                    relic.put("icon", r.optString("icon", ""))

                    // _flat.props：主词条 + 副词条（Mihomo 解析版字段为 type / value / display）
                    val flat = JSONObject()
                    val props = org.json.JSONArray()

                    // 主词条
                    val main = r.optJSONObject("main_affix")
                    if (main != null) {
                        props.put(mirProp(main))
                    }

                    // 副词条
                    val subs = r.optJSONArray("sub_affix")
                    val subList = org.json.JSONArray()
                    if (subs != null) {
                        for (k in 0 until subs.length()) {
                            val s = subs.optJSONObject(k) ?: continue
                            props.put(mirProp(s))

                            val subEntry = JSONObject()
                            subEntry.put("cnt", s.optInt("count", 0))
                            subList.put(subEntry)
                        }
                    }

                    flat.put("props", props)
                    relic.put("_flat", flat)
                    relic.put("subAffixList", subList)

                    // 套装ID：Mihomo 直接给 set_id（如 "129"），不能从遗器 id 推算
                    flat.put("setID", r.optString("set_id", "0").toIntOrNull() ?: 0)

                    relicList.put(relic)
                }
            }
            enkaChar.put("relicList", relicList)

            // 最终面板：Mihomo 直接给 statistics，无需本地重算
            val stats = c.optJSONArray("statistics")
            if (stats != null) enkaChar.put("_stats", stats)
            enkaChar.put("elementName", c.optJSONObject("element")?.optString("name", "") ?: "")
            enkaChar.put("pathName", c.optJSONObject("path")?.optString("name", "") ?: "")

            // 套装效果列表
            val relicSets = c.optJSONArray("relic_sets")
            if (relicSets != null) enkaChar.put("_relicSets", relicSets)

            // 行迹
            val skills = c.optJSONArray("skills")
            val skillTree = org.json.JSONArray()
            if (skills != null) {
                for (k in 0 until skills.length()) {
                    val s = skills.optJSONObject(k) ?: continue
                    val sid = s.optInt("id", 0)
                    // 只取主要技能（101-104 范围）
                    if (sid % 100 in 1..4) {
                        val node = JSONObject()
                        node.put("pointId", sid % 100)
                        node.put("level", s.optInt("level", 1))
                        node.put("type_text", s.optString("type_text", ""))
                        node.put("icon", s.optString("icon", ""))
                        skillTree.put(node)
                    }
                }
            }
            enkaChar.put("skillTreeList", skillTree)

            avatarList.put(enkaChar)
        }
    }
    detail.put("avatarDetailList", avatarList)
    out.put("detailInfo", detail)

    if (avatarList.length() == 0) {
        throw Exception("该玩家未开启角色详情对外展示")
    }
    return out
}

/**
 * Mihomo 词条对象 → 内部统一词条对象
 * Mihomo: {type:"CriticalDamageBase", value:0.11, display:"11.0%", percent:true}
 */
private fun mirProp(src: JSONObject): JSONObject {
    val out = JSONObject()
    out.put("type", src.optString("type", ""))
    out.put("value", src.optDouble("value", 0.0))
    out.put("display", src.optString("display", ""))
    out.put("name", src.optString("name", ""))
    out.put("percent", src.optBoolean("percent", false))
    return out
}
