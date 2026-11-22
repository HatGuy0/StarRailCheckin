package com.mh.starrailcheckin.ui.profile

import android.os.Bundle
import android.widget.ImageView
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/**
 * 练度总览 + 遗器仓库（方向一 / 方向二）。
 * 复用橱窗 raw_json 全量数据，跨角色聚合练度梯队，并平铺全部遗器供筛选排序。
 * 评分口径与 EnkaShowcaseActivity 一致（RelicScorer，miao 规则）。
 *
 * D-1：由 View/XML 迁移到 Compose；解析/聚合/评分保留为纯 Kotlin 逻辑，UI 只渲染。
 */
class EnkaOverviewActivity : LightBaseActivity() {

    private lateinit var rootJson: JSONObject
    private val charList = mutableListOf<JSONObject>()

    private val summaries = mutableListOf<CharSummary>()
    private val relicEntries = mutableListOf<RelicEntry>()
    private var playerTitle: String = ""

    // 配色（与单角色面板一致，深色星空底 + 浅色文字）
    private val textWhite = Color(0xFFEDEBFA)
    private val textGray = Color(0xFFA39DBA)
    private val accentGold = Color(0xFFF5B041)
    private val accentGreen = Color(0xFF2ECC71)
    private val accentRed = Color(0xFFE74C3C)
    private val accentBlue = Color(0xFF5DADE2)
    private val primaryColor = Color(0xFF7B6CF6)
    private val cardBg = Color(0x14FFFFFF)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val raw = intent.getStringExtra("raw_json").orEmpty()
        if (raw.isBlank()) { finish(); return }
        rootJson = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "面板数据已损坏，无法查看", Toast.LENGTH_SHORT).show(); finish(); return
        }

        EnkaNameMap.load(applicationContext)

        val chars = rootJson.optJSONObject("detailInfo")?.optJSONArray("avatarDetailList")
        if (chars != null && chars.length() > 0) {
            for (i in 0 until chars.length()) chars.optJSONObject(i)?.let { charList.add(it) }
            buildSummaries()
            buildRelicEntries()
        }
        renderPlayerTitle()

        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme(darkTheme = false, forceDark = true) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        OverviewScreen(
                            playerTitle = playerTitle,
                            summaries = summaries,
                            relicEntries = relicEntries,
                            textWhite = textWhite,
                            textGray = textGray,
                            accentGold = accentGold,
                            accentGreen = accentGreen,
                            accentRed = accentRed,
                            accentBlue = accentBlue,
                            primaryColor = primaryColor,
                            cardBg = cardBg,
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    // ---------------- 数据聚合（纯 Kotlin，不触碰 View） ----------------

    private fun baseAttrsOf(c: JSONObject): Triple<Double, Double, Double> {
        var hp = 1200.0; var atk = 600.0; var def = 500.0
        c.optJSONArray("_baseAttrs")?.let {
            for (i in 0 until it.length()) {
                val a = it.optJSONObject(i) ?: continue
                when (a.optString("field")) {
                    "hp" -> hp = a.optDouble("value", hp)
                    "atk" -> atk = a.optDouble("value", atk)
                    "def" -> def = a.optDouble("value", def)
                }
            }
        }
        return Triple(hp, atk, def)
    }

    private fun relicCells(c: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        c.optJSONArray("relicList")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun scoreOne(
        c: JSONObject, r: JSONObject, base: Triple<Double, Double, Double>
    ): Pair<Double, List<Pair<String, Double>>> {
        val charName = c.optString("name", "").ifBlank { EnkaNameMap.charName(c.optInt("avatarId", 0)) }
        val position = r.optInt("type", 1)
        val props = r.optJSONObject("_flat")?.optJSONArray("props")
        val mainType = props?.optJSONObject(0)?.optString("type", "") ?: ""
        val mainValue = props?.optJSONObject(0)?.optDouble("value", 0.0) ?: 0.0
        val subs = mutableListOf<Pair<String, Double>>()
        if (props != null) for (j in 1 until props.length()) {
            val p = props.optJSONObject(j) ?: continue
            subs.add(Pair(p.optString("type", ""), p.optDouble("value", 0.0)))
        }
        val score = RelicScorer.scoreRelic(
            charName, position, mainType, mainValue, subs,
            base.first, base.second, base.third
        )
        return Pair(score, subs)
    }

    private fun buildSummaries() {
        summaries.clear()
        for (c in charList) {
            val aid = c.optInt("avatarId", 0)
            val name = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
            val level = c.optInt("level", 0)
            val rank = c.optInt("rank", 0)
            val element = c.optString("elementName", "")
            val path = c.optString("pathName", "")

            val eq = c.optJSONObject("equipment")
            val hasLc = eq != null && eq.optInt("tid", 0) != 0
            val lcName = if (hasLc) EnkaNameMap.lcName(eq.optInt("tid", 0)) else "未装备光锥"
            val lcLevel = eq?.optInt("level", 0) ?: 0
            val lcRank = eq?.optInt("rank", 0) ?: 0

            val base = baseAttrsOf(c)
            val cells = relicCells(c)
            var total = 0.0
            for (r in cells) total += scoreOne(c, r, base).first
            val avg = if (cells.isNotEmpty()) total / cells.size else 0.0
            val grade = RelicScorer.scoreToGrade(avg)

            var skillMax = 0; var skillCount = 0
            c.optJSONArray("skillsDetail")?.let { sk ->
                skillCount = sk.length()
                for (i in 0 until sk.length()) {
                    val lv = sk.optJSONObject(i)?.optInt("level", 1) ?: 1
                    if (lv > skillMax) skillMax = lv
                }
            }

            val tier = when {
                level < 60 || !hasLc || cells.isEmpty() -> "待养成"
                level >= 70 && hasLc && avg >= 28.0 -> "成型"
                else -> "培养中"
            }
            summaries.add(CharSummary(
                aid, name, level, rank, element, path,
                hasLc, lcName, lcLevel, lcRank,
                cells.size, avg, grade, skillMax, skillCount, tier, c
            ))
        }
        val tierOrder = mapOf("成型" to 0, "培养中" to 1, "待养成" to 2)
        summaries.sortWith(compareBy({ tierOrder[it.tier] ?: 3 }, { -it.avgScore }, { -it.level }))
    }

    private fun buildRelicEntries() {
        relicEntries.clear()
        for (c in charList) {
            val aid = c.optInt("avatarId", 0)
            val name = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
            val base = baseAttrsOf(c)
            for (r in relicCells(c)) {
                val (score, subs) = scoreOne(c, r, base)
                val failList = mutableListOf<Triple<String, Double, Int>>()
                val subsArr = r.optJSONArray("subAffixList")
                subs.forEachIndexed { idx, (t, v) ->
                    val cnt = subsArr?.optJSONObject(idx)?.optInt("cnt", 0) ?: 0
                    failList.add(Triple(t, v, cnt))
                }
                val fail = RelicScorer.countFail(name, failList)
                val setID = r.optJSONObject("_flat")?.optInt("setID", 0) ?: 0
                val setName = r.optString("setName", "").ifBlank { EnkaNameMap.relicSetName(setID) }
                var crit = 0.0
                for ((t, v) in subs) when (t) {
                    "CriticalChanceBase", "CriticalChance" -> crit += v * 100
                    "CriticalDamageBase", "CriticalDamage" -> crit += v * 100
                }
                relicEntries.add(RelicEntry(
                    name, aid, r, score, RelicScorer.scoreToGrade(score), fail,
                    setID, r.optInt("type", 0), setName, crit, r.optInt("level", 0)
                ))
            }
        }
    }

    private fun renderPlayerTitle() {
        val detail = rootJson.optJSONObject("detailInfo")
        val nick = detail?.optString("nickname", "未知") ?: "未知"
        val lvl = detail?.optInt("level", 0) ?: 0
        val uid = detail?.optString("uid", "") ?: rootJson.optString("uid", "")
        playerTitle = "$nick  Lv.$lvl  UID:$uid  · 共 ${charList.size} 名角色"
    }
}

internal data class CharSummary(
    val aid: Int, val name: String, val level: Int, val rank: Int,
    val element: String, val path: String,
    val hasLc: Boolean, val lcName: String, val lcLevel: Int, val lcRank: Int,
    val relicCount: Int, val avgScore: Double, val grade: String,
    val skillMax: Int, val skillCount: Int, val tier: String,
    val raw: JSONObject
)

internal data class RelicEntry(
    val charName: String, val charAid: Int, val relic: JSONObject, val score: Double,
    val grade: String, val fail: Int, val setID: Int, val type: Int,
    val setName: String, val critSum: Double, val level: Int
)

@Composable
private fun OverviewScreen(
    playerTitle: String,
    summaries: List<CharSummary>,
    relicEntries: List<RelicEntry>,
    textWhite: Color,
    textGray: Color,
    accentGold: Color,
    accentGreen: Color,
    accentRed: Color,
    accentBlue: Color,
    primaryColor: Color,
    cardBg: Color,
    onBack: () -> Unit
) {
    var currentTab by remember { mutableStateOf(0) }
    var sortMode by remember { mutableStateOf(0) }
    var relicFilterType by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.enka_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xE60A0D18), Color(0x990A0D18), Color(0xB30A0D18))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // 顶栏：返回 + 标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(painter = painterResource(R.drawable.ic_back), contentDescription = "返回", tint = textWhite)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "练度总览",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = textWhite,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.width(48.dp))
            }

            // 玩家信息
            Text(
                text = playerTitle,
                fontSize = 13.sp,
                color = textGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            )

            // 页签
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("练度总览", "遗器仓库(${relicEntries.size})").forEachIndexed { idx, title ->
                    TabCapsule(
                        title = title,
                        selected = currentTab == idx,
                        textWhite = textWhite,
                        textGray = textGray,
                        primaryColor = primaryColor
                    ) { currentTab = idx }
                }
            }

            // 遗器仓库筛选行（总览页签隐藏）
            if (currentTab == 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("全部", "头", "手", "衣", "鞋", "球", "绳").forEachIndexed { idx, label ->
                            FilterChip(
                                label = label,
                                selected = relicFilterType == idx,
                                textWhite = textWhite,
                                textGray = textGray,
                                primaryColor = primaryColor,
                                cardBg = cardBg
                            ) { relicFilterType = idx }
                        }
                    }
                    Text(
                        text = when (sortMode) { 1 -> "排序:双暴"; 2 -> "排序:等级"; else -> "排序:评分" },
                        fontSize = 11.sp,
                        color = accentBlue,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                    Text(
                        text = "⟳",
                        fontSize = 13.sp,
                        color = accentBlue,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { sortMode = (sortMode + 1) % 3 }
                    )
                }
            }

            if (currentTab == 0) {
                OverviewTab(
                    summaries = summaries,
                    textWhite = textWhite,
                    textGray = textGray,
                    accentGold = accentGold,
                    accentGreen = accentGreen,
                    accentBlue = accentBlue,
                    cardBg = cardBg,
                    gradeColor = { g -> gradeColor(g, accentGold, accentRed, accentBlue, accentGreen, textGray) }
                )
            } else {
                RelicRepoTab(
                    relicEntries = relicEntries,
                    filterType = relicFilterType,
                    sortMode = sortMode,
                    textWhite = textWhite,
                    textGray = textGray,
                    accentGold = accentGold,
                    accentGreen = accentGreen,
                    accentBlue = accentBlue,
                    cardBg = cardBg,
                    gradeColor = { g -> gradeColor(g, accentGold, accentRed, accentBlue, accentGreen, textGray) }
                )
            }

            Text(
                text = "数据来自 Mihomo（鳄梨）第三方服务 · 评分仅供参考",
                fontSize = 11.sp,
                color = textGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun TabCapsule(
    title: String,
    selected: Boolean,
    textWhite: Color,
    textGray: Color,
    primaryColor: Color,
    onClick: () -> Unit
) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = if (selected) textWhite else textGray,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) primaryColor else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    textWhite: Color,
    textGray: Color,
    primaryColor: Color,
    cardBg: Color,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = if (selected) textWhite else textGray,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) primaryColor else cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun OverviewTab(
    summaries: List<CharSummary>,
    textWhite: Color,
    textGray: Color,
    accentGold: Color,
    accentGreen: Color,
    accentBlue: Color,
    cardBg: Color,
    gradeColor: (String) -> Color
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        if (summaries.isEmpty()) {
            EmptyTip("暂无角色数据", textGray)
            return@Column
        }
        val done = summaries.count { it.tier == "成型" }
        val growing = summaries.count { it.tier == "培养中" }
        val wait = summaries.count { it.tier == "待养成" }

        // 汇总卡
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cardBg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricCol("${summaries.size}", "角色总数", textWhite, textGray, Modifier.weight(1f))
            MetricCol("$done", "成型", accentGreen, textGray, Modifier.weight(1f))
            MetricCol("$growing", "培养中", accentBlue, textGray, Modifier.weight(1f))
            MetricCol("$wait", "待养成", accentGold, textGray, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        summaries.forEach { s ->
            CharCard(
                s = s,
                textWhite = textWhite,
                textGray = textGray,
                accentGold = accentGold,
                accentGreen = accentGreen,
                accentBlue = accentBlue,
                cardBg = cardBg,
                gradeColor = gradeColor
            )
        }
    }
}

@Composable
private fun MetricCol(value: String, label: String, color: Color, labelColor: Color, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 11.sp, color = labelColor)
    }
}

@Composable
private fun CharCard(
    s: CharSummary,
    textWhite: Color,
    textGray: Color,
    accentGold: Color,
    accentGreen: Color,
    accentBlue: Color,
    cardBg: Color,
    gradeColor: (String) -> Color
) {
    val tierColor = when (s.tier) {
        "成型" -> accentGreen; "培养中" -> accentBlue; else -> accentGold
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(aid = s.aid, modifier = Modifier.size(54.dp).padding(end = 12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = textWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  LV.${s.level}", fontSize = 11.sp, color = accentGold)
            }
            val elemPath = listOf(s.element, s.path).filter { it.isNotBlank() }.joinToString(" · ")
            if (elemPath.isNotBlank()) Text(elemPath, fontSize = 11.sp, color = textGray, maxLines = 1)
            val lcText = if (s.hasLc) "${s.lcName} Lv.${s.lcLevel} 叠影${s.lcRank}" else "未装备光锥"
            Text(lcText, fontSize = 11.sp, color = textGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val relicText = if (s.relicCount > 0)
                "遗器 ${fmt1(s.avgScore)} · ${s.grade}（${s.relicCount}件）"
            else "未穿戴遗器"
            Text(relicText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = gradeColor(s.grade))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                s.tier,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tierColor)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
            if (s.skillCount > 0) {
                Text("技能最高 ${s.skillMax} 级", fontSize = 10.sp, color = textGray, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun RelicRepoTab(
    relicEntries: List<RelicEntry>,
    filterType: Int,
    sortMode: Int,
    textWhite: Color,
    textGray: Color,
    accentGold: Color,
    accentGreen: Color,
    accentBlue: Color,
    cardBg: Color,
    gradeColor: (String) -> Color
) {
    val filtered = remember(relicEntries, filterType) {
        relicEntries.filter { filterType == 0 || it.type == filterType }
    }
    val sorted = remember(filtered, sortMode) {
        when (sortMode) {
            1 -> filtered.sortedByDescending { it.critSum }
            2 -> filtered.sortedByDescending { it.level }
            else -> filtered.sortedByDescending { it.score }
        }
    }
    val dupKeys = remember(filtered) {
        filtered.groupingBy { "${it.setID}_${it.type}" }.eachCount().filter { it.value > 1 }.keys
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        if (sorted.isEmpty()) {
            EmptyTip("该部位暂无遗器", textGray)
            return@Column
        }
        sorted.forEach { e ->
            RelicCard(
                e = e,
                duplicated = dupKeys.contains("${e.setID}_${e.type}"),
                textWhite = textWhite,
                textGray = textGray,
                accentGold = accentGold,
                accentGreen = accentGreen,
                accentBlue = accentBlue,
                cardBg = cardBg,
                gradeColor = gradeColor
            )
        }
    }
}

@Composable
private fun RelicCard(
    e: RelicEntry,
    duplicated: Boolean,
    textWhite: Color,
    textGray: Color,
    accentGold: Color,
    accentGreen: Color,
    accentBlue: Color,
    cardBg: Color,
    gradeColor: (String) -> Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RelicImage(setID = e.setID, type = e.type, modifier = Modifier.size(42.dp).padding(end = 10.dp))

        Column(modifier = Modifier.weight(1f)) {
            val topLine = listOf(partName(e.type), e.setName.ifBlank { "遗器${e.setID}" })
                .filter { it.isNotBlank() }.joinToString(" · ")
            Text(topLine, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${e.charName}  ·  +${e.level}", fontSize = 11.sp, color = textGray)

            val flat = e.relic.optJSONObject("_flat")
            val props = flat?.optJSONArray("props")
            val main = props?.optJSONObject(0)
            if (main != null) {
                Text(
                    "主词条 ${propLabel(main.optString("type", ""))}  ${propValueText(main)}",
                    fontSize = 11.sp, color = accentBlue
                )
            }

            val subsArr = e.relic.optJSONArray("subAffixList")
            if (props != null) {
                for (j in 1 until props.length()) {
                    val p = props.optJSONObject(j) ?: continue
                    val ptype = p.optString("type", "")
                    if (ptype.isBlank()) continue
                    val cnt = subsArr?.optJSONObject(j - 1)?.optInt("cnt", 0) ?: 0
                    val valid = RelicScorer.isValidType(ptype, e.charAid)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(propLabel(ptype), fontSize = 10.sp, color = textGray, modifier = Modifier.weight(1f))
                        Text(if (cnt >= 3) ">>" else ">", fontSize = 9.sp, color = if (cnt >= 3) accentGold else textGray)
                        Text(
                            "+${propValueText(p)}",
                            fontSize = 10.sp,
                            color = if (valid) accentGreen else textGray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            val badges = mutableListOf("${fmt1(e.score)} ${e.grade}")
            if (e.fail > 0) badges.add("歪${e.fail}次")
            if (duplicated) badges.add("重复")
            Text(
                badges.joinToString("  ·  "),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = gradeColor(e.grade),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ---------------- 图片桥接 ----------------

@Composable
private fun AvatarImage(aid: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv -> EnkaImageLoader.loadAvatar(iv.context, aid, iv) },
        modifier = modifier.clip(CircleShape)
    )
}

@Composable
private fun RelicImage(setID: Int, type: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv -> EnkaImageLoader.loadRelic(iv.context, setID, type, iv) },
        modifier = modifier
    )
}

// ---------------- 工具 ----------------

@Composable
private fun EmptyTip(text: String, textGray: Color) {
    Text(
        text = text, fontSize = 13.sp, color = textGray,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
    )
}

private fun gradeColor(
    grade: String,
    accentGold: Color, accentRed: Color, accentBlue: Color, accentGreen: Color, textGray: Color
): Color = when (grade) {
    "ACE", "MAX" -> accentGold
    "SSS", "SS" -> accentRed
    "S" -> accentGold
    "A" -> accentBlue
    "B" -> accentGreen
    else -> textGray
}

private fun partName(type: Int): String = when (type) {
    1 -> "头部"; 2 -> "手部"; 3 -> "躯干"; 4 -> "脚部"; 5 -> "位面球"; 6 -> "连结绳"; else -> "部位$type"
}

private fun propLabel(type: String): String = when (type) {
    "BaseHP", "HPDelta", "HPAddedRatio" -> "生命值"
    "BaseAttack", "AttackDelta", "AttackAddedRatio" -> "攻击力"
    "BaseDefence", "DefenceDelta", "DefenceAddedRatio" -> "防御力"
    "Speed", "SpeedDelta" -> "速度"
    "CriticalChance", "CriticalChanceBase" -> "暴击率"
    "CriticalDamage", "CriticalDamageBase" -> "暴击伤害"
    "StatusProbability", "StatusProbabilityBase" -> "效果命中"
    "StatusResist", "StatusResistanceBase" -> "效果抵抗"
    "BreakDamageAddedRatio", "BreakDamageAddedRatioBase" -> "击破特攻"
    "PhysicalAddedRatio" -> "物理伤害加成"
    "FireAddedRatio" -> "火属性伤害加成"
    "IceAddedRatio" -> "冰属性伤害加成"
    "ThunderAddedRatio" -> "雷属性伤害加成"
    "WindAddedRatio" -> "风属性伤害加成"
    "QuantumAddedRatio" -> "量子属性伤害加成"
    "ImaginaryAddedRatio" -> "虚数属性伤害加成"
    else -> type
}

private fun propValueText(p: JSONObject): String {
    val disp = p.optString("display", "")
    if (disp.isNotBlank()) return disp
    val t = p.optString("type", "")
    val v = p.optDouble("value", 0.0)
    val isPct = t.endsWith("Ratio") || t == "CriticalChance" || t == "CriticalDamage" ||
        t == "StatusProbability" || t == "StatusResist" || t == "BreakDamageAddedRatio"
    return when {
        isPct -> "${fmt1(v * 100)}%"
        t.endsWith("Delta") || t.startsWith("Base") || t == "Speed" -> v.toInt().toString()
        v < 1.0 -> "${fmt1(v * 100)}%"
        else -> v.toInt().toString()
    }
}

private fun fmt1(v: Double): String =
    java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
