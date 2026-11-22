package com.mh.starrailcheckin.ui.profile

import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text

/**
 * 角色详情（立绘 / 属性面板 / 技能 / 光锥 / 遗器评分）。
 * 评分口径与 EnkaOverviewActivity 一致（RelicScorer，miao 规则）。
 *
 * D-2：由 View/XML 迁移到 Compose；解析/聚合/评分保留为纯 Kotlin 派生模型，UI 只渲染。
 */
class EnkaShowcaseActivity : LightBaseActivity() {

    private lateinit var rootJson: JSONObject
    private var charList = mutableListOf<JSONObject>()

    // 深色星空底适配：卡片透明，文字用浅色；bgDeep 仅作头像/图标衬底
    private val bgDeep = Color(0xFFF0EDF7)
    private val textWhite = Color(0xFFEDEBFA)
    private val textGray = Color(0xFFA39DBA)
    private val textLight = Color(0xFFA39DBA)
    private val accentGold = Color(0xFFF5B041)
    private val accentGreen = Color(0xFF2ECC71)
    private val accentRed = Color(0xFFE74C3C)
    private val accentBlue = Color(0xFF5DADE2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val raw = intent.getStringExtra("raw_json").orEmpty()
        if (raw.isBlank()) { finish(); return }
        rootJson = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "角色面板数据已损坏，无法查看", Toast.LENGTH_SHORT).show(); finish(); return
        }

        EnkaNameMap.load(applicationContext)

        val detail = rootJson.optJSONObject("detailInfo")
        val chars = detail?.optJSONArray("avatarDetailList")
        if (chars != null && chars.length() > 0) {
            for (i in 0 until chars.length()) chars.optJSONObject(i)?.let { charList.add(it) }
        }

        val playerInfo = run {
            val nick = detail?.optString("nickname", "未知") ?: "未知"
            val lvl = detail?.optInt("level", 0) ?: 0
            val uid = rootJson.optString("uid", "")
            "$nick  Lv.$lvl  UID:$uid"
        }
        val uid = rootJson.optString("uid", "")

        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme(darkTheme = false, forceDark = true) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        ShowcaseScreen(
                            playerInfo = playerInfo,
                            uid = uid,
                            charList = charList,
                            bgDeep = bgDeep,
                            textWhite = textWhite,
                            textGray = textGray,
                            textLight = textLight,
                            accentGold = accentGold,
                            accentGreen = accentGreen,
                            accentRed = accentRed,
                            accentBlue = accentBlue,
                            openOverview = {
                                val intent = android.content.Intent(this@EnkaShowcaseActivity, EnkaOverviewActivity::class.java)
                                intent.putExtra("raw_json", rootJson.toString())
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 派生模型（纯 Kotlin，不触碰 View） ----------------

private data class LcInfo(val name: String, val level: Int, val rank: Int, val tid: Int)

private data class SubProp(val label: String, val arrow: String, val value: String, val valid: Boolean)

private data class RelicCell(
    val type: Int, val level: Int, val rarity: Int, val setID: Int,
    val mainLabel: String, val mainValue: String,
    val score: Double, val grade: String, val fail: Int, val subs: List<SubProp>
)

private data class ShowcaseStatRow(val label: String, val display: String, val baseText: String, val addText: String)

private data class SkillInfo(val icon: String, val level: Int, val typeText: String)

private data class CharDetail(
    val aid: Int, val name: String, val level: Int,
    val element: String, val path: String,
    val lc: LcInfo?, val relics: List<RelicCell>,
    val stats: List<ShowcaseStatRow>, val skills: List<SkillInfo>,
    val totalScore: Double, val grade: String
)

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

private fun buildDetail(c: JSONObject): CharDetail {
    val aid = c.optInt("avatarId", 0)
    val name = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
    val level = c.optInt("level", 0)
    val element = c.optString("elementName", "")
    val path = c.optString("pathName", "")

    // 光锥
    val eq = c.optJSONObject("equipment")
    val lc = if (eq != null && eq.optInt("tid", 0) != 0) {
        LcInfo(
            name = EnkaNameMap.lcName(eq.optInt("tid", 0)),
            level = eq.optInt("level", 0),
            rank = eq.optInt("rank", 0),
            tid = eq.optInt("tid", 0)
        )
    } else null

    // 遗器
    val base = baseAttrsOf(c)
    val charName = name
    val cells = mutableListOf<JSONObject>()
    c.optJSONArray("relicList")?.let { arr ->
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cells.add(it) }
    }

    var totalScore = 0.0
    val relics = cells.map { r ->
        val cell = buildRelicCell(r, aid, charName, base)
        totalScore += cell.score
        cell
    }
    val avgScore = if (relics.isNotEmpty()) totalScore / relics.size else 0.0
    val grade = RelicScorer.scoreToGrade(avgScore)

    // 属性面板
    val stats = buildStats(c)

    // 技能（按 type 排序：普攻/战技/终结/天赋/秘技）
    val skills = buildSkills(c)

    return CharDetail(aid, name, level, element, path, lc, relics, stats, skills, totalScore, grade)
}

private fun buildRelicCell(
    r: JSONObject, avatarId: Int, charName: String, base: Triple<Double, Double, Double>
): RelicCell {
    val type = r.optInt("type", 0)
    val rlvl = r.optInt("level", 0)
    val rarity = r.optInt("rarity", 5)
    val flat = r.optJSONObject("_flat")
    val setID = flat?.optInt("setID", 0) ?: 0
    val props = flat?.optJSONArray("props")
    val subsArr = r.optJSONArray("subAffixList")

    val mainProp = props?.optJSONObject(0)
    val mainType = mainProp?.optString("type", "") ?: ""
    val mainValue = mainProp?.optDouble("value", 0.0) ?: 0.0

    val subList = mutableListOf<Pair<String, Double>>()
    val subFailList = mutableListOf<Triple<String, Double, Int>>()
    val subs = mutableListOf<SubProp>()
    if (props != null && subsArr != null) {
        for (j in 1 until props.length()) {
            val p = props.optJSONObject(j) ?: continue
            val ptype = p.optString("type", "")
            val pval = p.optDouble("value", 0.0)
            val cnt = subsArr.optJSONObject(j - 1)?.optInt("cnt", 0) ?: 0
            subList.add(Pair(ptype, pval))
            subFailList.add(Triple(ptype, pval, cnt))
            val valid = RelicScorer.isValidType(ptype, avatarId)
            subs.add(SubProp(
                label = propLabel(ptype),
                arrow = if (cnt >= 3) ">>" else ">",
                value = "+${propValueText(p)}",
                valid = valid
            ))
        }
    }

    val relicScore = RelicScorer.scoreRelic(
        charName, type, mainType, mainValue, subList, base.first, base.second, base.third
    )
    val failCount = RelicScorer.countFail(charName, subFailList)

    return RelicCell(
        type = type, level = rlvl, rarity = rarity, setID = setID,
        mainLabel = propLabel(mainType),
        mainValue = mainProp?.let { propValueText(it) } ?: "",
        score = relicScore, grade = RelicScorer.scoreToGrade(relicScore),
        fail = failCount, subs = subs
    )
}

private fun buildStats(c: JSONObject): List<ShowcaseStatRow> {
    val statsArr = c.optJSONArray("_stats") ?: return emptyList()
    val baseMap = mutableMapOf<String, Double>()
    c.optJSONArray("_baseAttrs")?.let { baseArr ->
        for (i in 0 until baseArr.length()) {
            val a = baseArr.optJSONObject(i) ?: continue
            baseMap[a.optString("field", "")] = a.optDouble("value", 0.0)
        }
    }
    val addMap = mutableMapOf<String, JSONObject>()
    c.optJSONArray("_additions")?.let { adds ->
        for (i in 0 until adds.length()) {
            val a = adds.optJSONObject(i) ?: continue
            addMap[a.optString("field", "")] = a
        }
    }

    val flatFields = listOf("hp", "atk", "def", "spd")
    val rows = mutableListOf<ShowcaseStatRow>()
    for (i in 0 until statsArr.length()) {
        val s = statsArr.optJSONObject(i) ?: continue
        val field = s.optString("field", "")
        val label = statLabel(field, s.optString("name", field))
        val disp = s.optString("display", "")
        val baseVal = baseMap[field] ?: 0.0
        val percent = s.optBoolean("percent", false)

        var baseText = ""
        if (field in flatFields && baseVal > 0.0001) {
            baseText = if (percent) "${fmt1(baseVal * 100)}%" else baseVal.toInt().toString()
        }
        var addText = ""
        val addEntry = addMap[field]
        val addDisplay = addEntry?.optString("display", "")?.trim().orEmpty()
        if (field in flatFields && addDisplay.isNotBlank()) addText = "+$addDisplay"

        rows.add(ShowcaseStatRow(label, disp, baseText, addText))
    }
    return rows
}

private fun buildSkills(c: JSONObject): List<SkillInfo> {
    val skillsDetail = c.optJSONArray("skillsDetail") ?: return emptyList()
    val ordered = mutableListOf<SkillInfo>()
    val typeOrder = listOf(
        "Normal", "BPSkill", "Ultra", "Talent", "Maze",
        "ElationDamage", "MemospriteSkill", "MemospriteTalent"
    )
    for (t in typeOrder) {
        for (i in 0 until skillsDetail.length()) {
            val s = skillsDetail.optJSONObject(i) ?: continue
            if (s.optString("type", "") == t) {
                ordered.add(SkillInfo(
                    icon = s.optString("icon", ""),
                    level = s.optInt("level", 1),
                    typeText = s.optString("typeText", "")
                ))
                break
            }
        }
    }
    return ordered
}

// ---------------- Compose 渲染 ----------------

@Composable
private fun ShowcaseScreen(
    playerInfo: String,
    uid: String,
    charList: List<JSONObject>,
    bgDeep: Color,
    textWhite: Color,
    textGray: Color,
    textLight: Color,
    accentGold: Color,
    accentGreen: Color,
    accentRed: Color,
    accentBlue: Color,
    openOverview: () -> Unit
) {
    var selectedIndex by remember { mutableStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, (charList.size - 1).coerceAtLeast(0))
    val detail = remember(safeIndex, charList) {
        charList.getOrNull(safeIndex)?.let { buildDetail(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.enka_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxSize().background(Color(0x590A0D18)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp)
        ) {
            // 玩家信息
            Text(
                text = playerInfo,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = textWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
            )

            // 全队总览入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x22FFFFFF))
                    .clickable(onClick = openOverview)
                    .padding(vertical = 10.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("📊 全队练度总览 / 遗器仓库", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textWhite)
            }

            Spacer(Modifier.height(10.dp))

            if (detail == null) {
                Text(
                    "该玩家未展示角色", fontSize = 13.sp, color = textGray,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                )
                return@Column
            }

            // 角色头像横向列表
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                charList.forEachIndexed { idx, c ->
                    val aid = c.optInt("avatarId", 0)
                    AvatarThumb(
                        aid = aid,
                        selected = idx == safeIndex,
                        bgDeep = bgDeep,
                        accentGold = accentGold,
                        onClick = { selectedIndex = idx }
                    )
                }
            }

            // 角色主卡：左右分栏——左半立绘、右半属性面板，并排不叠加（对齐旧 XML 布局）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                // 左半：立绘
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    PortraitImage(aid = detail.aid, modifier = Modifier.fillMaxSize())
                    Text(
                        text = "UID:$uid",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)
                    )
                }

                // 右半：属性面板（分栏并排，不压立绘）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "LV.${detail.level}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(accentRed)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            detail.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textWhite,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) {
                        if (detail.element.isNotBlank()) {
                            Text(detail.element, fontSize = 12.sp, color = Color(0xFFF4D03F), modifier = Modifier.padding(end = 10.dp))
                        }
                        if (detail.path.isNotBlank()) {
                            Text(detail.path, fontSize = 12.sp, color = Color(0xFFAED6F1))
                        }
                    }

                    detail.stats.forEach { row ->
                        StatRowView(row = row, textWhite = textWhite, textGray = textGray, textLight = textLight, accentGreen = accentGreen)
                    }
                }
            }

            // 技能栏
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                detail.skills.forEach { skill ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        SkillIcon(icon = skill.icon, bgDeep = bgDeep)
                        Text("${skill.level}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textWhite, modifier = Modifier.padding(top = 6.dp))
                        Text(skill.typeText, fontSize = 11.sp, color = textGray, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // 光锥 + 遗器评分横栏
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                // 光锥卡
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LcImage(tid = detail.lc?.tid ?: 0, modifier = Modifier.size(52.dp).padding(end = 10.dp), bgDeep = bgDeep)
                    Column {
                        Text(detail.lc?.name ?: "未装备光锥", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accentGold)
                        Text(
                            if (detail.lc != null) "Lv.${detail.lc.level}  叠影${detail.lc.rank}阶" else "",
                            fontSize = 11.sp, color = textGray, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // 遗器评分卡
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Transparent)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmt1(detail.totalScore), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = textWhite)
                        Text("遗器评分", fontSize = 11.sp, color = textGray, modifier = Modifier.padding(top = 2.dp))
                    }
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(detail.grade, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accentRed)
                        Text("遗器评级", fontSize = 11.sp, color = textGray, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // 遗器 2x3 网格
            if (detail.relics.isEmpty()) {
                Text(
                    "  该角色未穿戴遗器", fontSize = 12.sp, color = textGray,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            } else {
                detail.relics.chunked(3).forEach { rowCells ->
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        rowCells.forEach { cell ->
                            RelicCard(
                                cell = cell,
                                textWhite = textWhite,
                                textGray = textGray,
                                textLight = textLight,
                                accentGold = accentGold,
                                accentGreen = accentGreen,
                                accentRed = accentRed,
                                accentBlue = accentBlue,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Text(
                "数据来自 Mihomo（鳄梨）第三方服务 · 评分仅供参考",
                fontSize = 11.sp, color = textGray, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun AvatarThumb(
    aid: Int, selected: Boolean, bgDeep: Color, accentGold: Color, onClick: () -> Unit
) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv -> EnkaImageLoader.loadAvatar(iv.context, aid, iv) },
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bgDeep)
            .then(
                if (selected) Modifier.border(3.dp, accentGold, CircleShape) else Modifier
            )
            .graphicsLayer { scaleX = if (selected) 1.1f else 1f; scaleY = if (selected) 1.1f else 1f }
            .clickable(onClick = onClick)
    )
}

@Composable
private fun StatRowView(row: ShowcaseStatRow, textWhite: Color, textGray: Color, textLight: Color, accentGreen: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.label, fontSize = 11.sp, color = textLight, modifier = Modifier.weight(1.5f))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(row.display, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textWhite, textAlign = TextAlign.End)
            Row(horizontalArrangement = Arrangement.End) {
                if (row.baseText.isNotBlank()) {
                    Text(row.baseText, fontSize = 10.sp, color = textGray, textAlign = TextAlign.End, modifier = Modifier.padding(end = 6.dp))
                }
                if (row.addText.isNotBlank()) {
                    Text(row.addText, fontSize = 10.sp, color = accentGreen, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun SkillIcon(icon: String, bgDeep: Color) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(4, 4, 4, 4)
            }
        },
        update = { iv -> EnkaImageLoader.loadMihomoIcon(iv.context, icon, iv) },
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgDeep)
    )
}

@Composable
private fun PortraitImage(aid: Int, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv -> EnkaImageLoader.loadPortrait(iv.context, aid, iv) },
        modifier = modifier
    )
}

@Composable
private fun LcImage(tid: Int, modifier: Modifier = Modifier, bgDeep: Color) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv -> if (tid != 0) EnkaImageLoader.loadLightCone(iv.context, tid, iv) else iv.setImageBitmap(null) },
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(bgDeep)
    )
}

@Composable
private fun RelicCard(
    cell: RelicCell,
    textWhite: Color,
    textGray: Color,
    textLight: Color,
    accentGold: Color,
    accentGreen: Color,
    accentRed: Color,
    accentBlue: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 上部：+level + 主词条名 + 数值
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "+${cell.level}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentRed)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
                Text(
                    cell.mainLabel, fontSize = 11.sp, color = textLight,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
                Text(cell.mainValue, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textWhite, textAlign = TextAlign.End)
            }

            // 中部：图标 + 星级/歪次
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RelicImage(setID = cell.setID, type = cell.type, modifier = Modifier.size(40.dp))
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("★".repeat(cell.rarity), fontSize = 10.sp, color = accentGold)
                    if (cell.fail > 0) {
                        Text("歪${cell.fail}次", fontSize = 10.sp, color = accentRed, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // 评分行
            Text(
                "${fmt1(cell.score)} - ${cell.grade}",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = gradeColor(cell.grade, accentGold, accentRed, accentBlue, accentGreen, textGray),
                modifier = Modifier.padding(top = 6.dp)
            )

            // 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(Color(0x1FFFFFFF))
                    .height(1.dp)
            )

            // 副词条
            cell.subs.forEach { sub ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sub.label, fontSize = 10.sp, color = textGray, modifier = Modifier.weight(1f))
                    Text(sub.arrow, fontSize = 9.sp, color = if (sub.arrow == ">>") accentGold else textGray)
                    Text(
                        sub.value, fontSize = 10.sp,
                        color = if (sub.valid) accentGreen else textLight,
                        textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
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

private fun gradeColor(
    grade: String,
    accentGold: Color, accentRed: Color, accentBlue: Color, accentGreen: Color, textGray: Color
): Color = when (grade) {
    "ACE", "MAX" -> Color(0xFFF1C40F)
    "SSS", "SS" -> accentRed
    "S" -> accentGold
    "A" -> accentBlue
    "B" -> accentGreen
    else -> textGray
}

private fun statLabel(field: String, fallback: String): String = when (field) {
    "hp" -> "生命值"
    "atk" -> "攻击力"
    "def" -> "防御力"
    "spd" -> "速度"
    "crit_rate" -> "暴击率"
    "crit_dmg" -> "暴击伤害"
    "break_dmg" -> "击破特攻"
    "effect_hit" -> "效果命中"
    "effect_res" -> "效果抵抗"
    "elation_dmg" -> "欢愉度"
    "heal_rate" -> "治疗加成"
    "energy_regen" -> "能量恢复效率"
    "sp_rate" -> "施法充能效率"
    "physical_dmg" -> "物理伤害加成"
    "fire_dmg" -> "火属性伤害加成"
    "ice_dmg" -> "冰属性伤害加成"
    "thunder_dmg" -> "雷属性伤害加成"
    "wind_dmg" -> "风属性伤害加成"
    "quantum_dmg" -> "量子属性伤害加成"
    "imaginary_dmg" -> "虚数加伤"
    else -> fallback
}

private fun propLabel(type: String): String = when (type) {
    "BaseHP", "HPDelta", "HPAddedRatio" -> "生命值"
    "BaseAttack", "AttackDelta", "AttackAddedRatio" -> "攻击力"
    "BaseDefence", "DefenceDelta", "DefenceAddedRatio" -> "防御力"
    "Speed", "SpeedDelta" -> "速度"
    "CriticalChance", "CriticalChanceBase" -> "暴击率"
    "CriticalDamage", "CriticalDamageBase" -> "暴击伤害"
    "StatusProbability", "StatusProbabilityBase" -> "效果命中"
    "StatusResist", "StatusResistance" -> "效果抵抗"
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
    return formatPropValue(p.optString("type", ""), p.optDouble("value", 0.0))
}

private fun formatPropValue(type: String, value: Double): String {
    val isPct = type.endsWith("Ratio") || type == "CriticalChance" || type == "CriticalDamage" ||
        type == "StatusProbability" || type == "StatusResist" || type == "BreakDamageAddedRatio"
    return when {
        isPct -> {
            val pct = java.math.BigDecimal.valueOf(value * 100)
                .setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
            "${pct}%"
        }
        type.endsWith("Delta") || type.startsWith("Base") || type == "Speed" -> value.toInt().toString()
        value < 1.0 -> {
            val pct = java.math.BigDecimal.valueOf(value * 100)
                .setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
            "${pct}%"
        }
        else -> value.toInt().toString()
    }
}

private fun fmt1(v: Double): String =
    java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
