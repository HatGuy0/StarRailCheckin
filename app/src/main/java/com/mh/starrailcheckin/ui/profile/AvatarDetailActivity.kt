package com.mh.starrailcheckin.ui.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 全角色面板 —— 单角色详情页（数据源：米游社官方接口）。
 * 视觉参照社区面板长图：无底色立绘头部 + 右侧属性面板 + 5 个主行迹/副行迹 +
 * 光锥 + 遗器评分横幅 + 3 列遗器明细。
 */
class AvatarDetailActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AVATAR_ID = "avatar_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val avatarId = intent.getIntExtra(EXTRA_AVATAR_ID, 0)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        AvatarDetailScreen(avatarId = avatarId, onBack = { finish() })
                    }
                }
            }
        }
    }
}

// ==================== 数据模型 ====================

internal data class Prop(
    val type: String,
    val field: String,
    val name: String,
    val percent: Boolean,
    val value: Double,
    val base: Double = 0.0
) {
    val text: String get() = if (percent) String.format(Locale.US, "%.1f%%", value * 100)
    else fmtFlat(value)
}

internal data class SubUi(
    val prop: Prop,
    val times: Int,
    val label: String,
    val countText: String,
    val countValue: Double
)

private fun statTextValue(text: String): Double {
    val normalized = text.replace(",", "").replace("+", "").trim()
    val value = normalized.removeSuffix("%").toDoubleOrNull() ?: 0.0
    return if (normalized.endsWith("%")) value / 100.0 else value
}

internal data class StatRow(
    val field: String,
    val name: String,
    val total: String,
    val base: String,
    val bonus: String,
    val positive: Boolean,
    val totalValue: Double = statTextValue(total),
    val baseValue: Double = statTextValue(base),
    val bonusValue: Double = statTextValue(bonus)
)

internal data class SkillUi(
    val name: String,
    val typeText: String,
    val level: Int,
    val icon: String
)

internal data class LcUi(
    val id: Int,
    val name: String,
    val level: Int,
    val superimpose: Int,
    val rarity: Int,
    val hp: String,
    val atk: String,
    val def: String,
    val desc: String
)

internal data class RelicUi(
    val tid: Int,
    val setId: Int,
    val setName: String,
    val type: Int,
    val level: Int,
    val main: Prop?,
    val subs: List<SubUi>,
    val score: Double,
    val grade: String,
    val failCount: Int
)

internal data class AffixUi(
    val name: String,
    val countText: String,
    val countValue: Double,
    val valueText: String
)

internal data class SetUi(val name: String, val count: Int, val desc: String)

internal data class DetailUi(
    val aid: Int,
    val name: String,
    val uid: String,
    val level: Int,
    val rank: Int,
    val rarity: Int,
    val element: String,
    val elementField: String,
    val path: String,
    val stats: List<StatRow>,
    val skills: List<SkillUi>,
    val traces: List<String>,
    val lc: LcUi?,
    val relics: List<RelicUi>,
    val totalScore: Double,
    val totalGrade: String,
    val affixSums: List<AffixUi>,
    val sets: List<SetUi>,
    val sourceTime: String,
    val skillLevels: Map<Int, Int> = emptyMap(),
    val treeIds: Set<String> = emptySet(),
    val originalTalentLevels: Map<String, Int> = emptyMap(),
    val talentLevels: Map<String, Int> = emptyMap(),
    val enemyLv: Int = 80,
    val panel: OfficialPanelParser.Result? = null
)

// ==================== 米游社数值词条映射 ====================
// 官方 avatar/info 的 property_type 是数字；映射到 Enka/Mihomo 属性名后，
// 面板计算、评分、图标、中文名全部复用现有字典。
// 映射对齐 miao-plugin MysPanelHSRMappings.js 与 StarRail-plugin affixData.js。
private val PROP_BY_ID = mapOf(
    // 面板基础属性 / 遗器固定值副词条
    1 to "HPDelta", 2 to "AttackDelta", 3 to "DefenceDelta",
    4 to "SpeedDelta", 5 to "CriticalChanceBase", 6 to "CriticalDamageBase",
    7 to "HealRatioBase", 9 to "SPRatioBase",
    10 to "StatusProbabilityBase", 11 to "StatusResistanceBase",
    // 属性伤害加成
    12 to "PhysicalAddedRatio", 14 to "FireAddedRatio", 16 to "IceAddedRatio",
    18 to "ThunderAddedRatio", 20 to "WindAddedRatio",
    22 to "QuantumAddedRatio", 24 to "ImaginaryAddedRatio",
    // 主词条第二套编号（同名属性的另一组 id）
    27 to "HPDelta", 29 to "AttackDelta", 31 to "DefenceDelta",
    32 to "HPAddedRatio", 33 to "AttackAddedRatio", 34 to "DefenceAddedRatio",
    51 to "SpeedDelta", 52 to "CriticalChanceBase", 53 to "CriticalDamageBase",
    54 to "SPRatioBase", 55 to "HealRatioBase", 56 to "StatusProbabilityBase",
    57 to "StatusResistanceBase",
    58 to "BreakDamageAddedRatioBase", 59 to "BreakDamageAddedRatioBase"
)

private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
    var empty: JSONArray? = null
    for (key in keys) {
        val arr = o.optJSONArray(key) ?: continue
        if (arr.length() > 0) return arr
        if (empty == null) empty = arr
    }
    return empty
}

private fun readDouble(o: JSONObject, key: String): Double {
    val raw = o.opt(key) ?: return 0.0
    if (raw is Number) return raw.toDouble().takeIf { it.isFinite() } ?: 0.0
    val text = raw.toString().trim().replace(",", "")
    if (text.isBlank()) return 0.0
    val percent = text.endsWith("%")
    val value = text.removeSuffix("%").trim().toDoubleOrNull() ?: return 0.0
    return if (percent) value / 100.0 else value
}

private fun propKeyOf(o: JSONObject): String {
    val raw = o.opt("property_type") ?: o.opt("propertyType") ?: o.opt("type") ?: o.opt("property")
    val mapped = when (raw) {
        is Number -> PROP_BY_ID[raw.toInt()] ?: ""
        is String -> if (raw.toIntOrNull() != null) PROP_BY_ID[raw.toInt()] ?: "" else raw
        else -> ""
    }
    if (mapped.isNotBlank()) return mapped

    // 个别面板属性的数字 id 与遗器词条不同，官方中文名可作为最后兜底。
    val name = o.optString("property_name", "").ifBlank { o.optString("name", "") }
    val percent = o.opt("value")?.toString()?.contains("%") == true
    return when {
        name.contains("暴击率") -> "CriticalChanceBase"
        name.contains("暴击伤害") -> "CriticalDamageBase"
        name.contains("效果命中") -> "StatusProbabilityBase"
        name.contains("效果抵抗") -> "StatusResistanceBase"
        name.contains("击破特攻") -> "BreakDamageAddedRatioBase"
        name.contains("治疗") -> "HealRatioBase"
        name.contains("能量恢复") -> "SPRatioBase"
        name.contains("欢愉") -> "ElationDamageAddedRatio"
        name.contains("物理") -> "PhysicalAddedRatio"
        name.contains("火属性") -> "FireAddedRatio"
        name.contains("冰属性") -> "IceAddedRatio"
        name.contains("雷属性") -> "ThunderAddedRatio"
        name.contains("风属性") -> "WindAddedRatio"
        name.contains("量子") -> "QuantumAddedRatio"
        name.contains("虚数") -> "ImaginaryAddedRatio"
        name == "速度" -> "SpeedDelta"
        name == "生命值" -> if (percent) "HPAddedRatio" else "HPDelta"
        name == "攻击力" -> if (percent) "AttackAddedRatio" else "AttackDelta"
        name == "防御力" -> if (percent) "DefenceAddedRatio" else "DefenceDelta"
        else -> ""
    }
}

private fun fmtFlat(v: Double): String =
    if (abs(v - v.toLong()) < 0.05) v.toLong().toString()
    else String.format(Locale.US, "%.1f", v)

private fun fmtInt(v: Double): String = String.format(Locale.US, "%,d", v.toLong())

private fun elementInfo(e: String): Pair<String, String> = when {
    e.contains("Fire", true) -> "火" to "fire_dmg"
    e.contains("Ice", true) -> "冰" to "ice_dmg"
    e.contains("Thunder", true) || e.contains("Lightning", true) -> "雷" to "thunder_dmg"
    e.contains("Wind", true) -> "风" to "wind_dmg"
    e.contains("Quantum", true) -> "量子" to "quantum_dmg"
    e.contains("Imaginary", true) -> "虚数" to "imaginary_dmg"
    e.contains("Physical", true) -> "物理" to "physical_dmg"
    else -> "" to "physical_dmg"
}

private fun elementColor(field: String): Color = when (field) {
    "fire_dmg" -> Color(0xFFF0745C)
    "ice_dmg" -> Color(0xFF5FB8F0)
    "thunder_dmg" -> Color(0xFFB07CF2)
    "wind_dmg" -> Color(0xFF4FC79A)
    "quantum_dmg" -> Color(0xFF7E74F0)
    "imaginary_dmg" -> Color(0xFFE6C351)
    else -> Color(0xFFA9B6C4)
}

private fun pathInfo(p: String): String = when {
    p.contains("Hunt", true) -> "巡猎"
    p.contains("Erudition", true) -> "智识"
    p.contains("Harmony", true) -> "同谐"
    p.contains("Nihility", true) -> "虚无"
    p.contains("Preservation", true) -> "存护"
    p.contains("Abundance", true) -> "丰饶"
    p.contains("Destruction", true) -> "毁灭"
    p.contains("Remembrance", true) -> "记忆"
    p.contains("Elation", true) -> "欢愉"
    else -> p
}

/** 副词条基础值（用于换算「词条数」，对齐 miao 口径）。 */
private fun subBaseValue(p: Prop): Double = when (p.field) {
    "crit_rate" -> 0.0324
    "crit_dmg" -> 0.0648
    "spd" -> 2.6
    "break_dmg" -> 0.0648
    "effect_hit" -> 0.0432
    "effect_res" -> 0.0432
    "heal_rate" -> 0.0553
    "energy_regen" -> 0.0311
    "hp" -> if (p.percent) 0.0432 else 254.0 / 6
    "atk" -> if (p.percent) 0.0432 else 127.0 / 6
    "def" -> if (p.percent) 0.054 else 127.0 / 6
    else -> 0.0
}

private fun relicPropLabel(p: Prop): String = when (p.field) {
    "hp" -> if (p.percent) "大生命" else "小生命"
    "atk" -> if (p.percent) "大攻击" else "小攻击"
    "def" -> if (p.percent) "大防御" else "小防御"
    else -> p.name
}

private fun shortAffixName(p: Prop): String = when (p.field) {
    "crit_rate" -> "暴击"
    "crit_dmg" -> "爆伤"
    "spd" -> "速度"
    "break_dmg" -> "击破"
    "effect_hit" -> "命中"
    "effect_res" -> "抵抗"
    "heal_rate" -> "治疗"
    "energy_regen" -> "充能"
    "hp" -> if (p.percent) "生命" else "小生命"
    "atk" -> if (p.percent) "攻击" else "小攻击"
    "def" -> if (p.percent) "防御" else "小防御"
    else -> p.name
}

// ==================== 数据构建 ====================

private suspend fun loadDetail(context: android.content.Context, avatarId: Int): DetailUi {
    EnkaNameMap.load(context)
    val raw = MicaApi.fetchAvatarDetail(context, avatarId)
    val aid = raw.optInt("id", 0).takeIf { it > 0 } ?: avatarId
    val level = raw.optInt("level", 0)
    val promotion = raw.optInt("promotion", 6)
    val rank = raw.optInt("rank", 0)
    val rarity = raw.optInt("rarity", 0).takeIf { it > 0 } ?: EnkaNameMap.charRarity(aid)
    val element = EnkaNameMap.charElement(aid).ifBlank { raw.optString("element", "") }
    val path = EnkaNameMap.charPath(aid)
    val uid = Prefs.uid(context)

    val eq = raw.optJSONObject("equipment") ?: raw.optJSONObject("equip")
    val lcId = eq?.let { it.optInt("tid", 0).takeIf { v -> v > 0 } ?: it.optInt("id", 0) } ?: 0
    val lcRank = eq?.optInt("rank", 1) ?: 1
    val lcLevel = eq?.optInt("level", level) ?: level
    val lcPromotion = eq?.optInt("promotion", 6) ?: 6

    // 白值（角色 + 光锥）
    val baseArr = EnkaNameMap.combinedBaseAttrs(aid, promotion, level, eq)
    val baseMap = HashMap<String, Double>()
    if (baseArr != null) {
        for (i in 0 until baseArr.length()) {
            val o = baseArr.optJSONObject(i) ?: continue
            baseMap[o.optString("field", "")] = o.optDouble("value", 0.0)
        }
    }

    // 增量汇总：遗器 + 行迹 + 光锥被动 + 套装
    val additions = mutableListOf<Prop>()

    // 官方 properties 仅在原版重建数据不可用时兜底，伤害链路不信任 final 汇总值。
    val officialStats = HashMap<String, Prop>()
    val officialPropArr = firstArray(raw, "properties", "property_list", "propertyList")
    if (officialPropArr != null) {
        for (i in 0 until officialPropArr.length()) {
            val o = officialPropArr.optJSONObject(i) ?: continue
            val p = propObject(o)
            if (p.field.isNotBlank()) officialStats[p.field] = p
        }
    }

    val name = EnkaNameMap.charName(aid).let { n ->
        if (n.isBlank() || n.contains("{NICKNAME}", true) || n.startsWith("角色")) {
            raw.optString("name", "").ifBlank { "开拓者" }
        } else n
    }
    MiaoDataClient.ensureLoaded(context, name)
    val characterData = MiaoDataClient.data(name)
    val rulesLoaded = MiaoRulesEngine.ensureLoaded(context)
    val equipmentData = MiaoRulesEngine.equipmentData()
    val artifactData = MiaoRulesEngine.artifactData()
    val panel = if (rulesLoaded && characterData != null && equipmentData != null && artifactData != null) {
        runCatching {
            OfficialPanelParser.parse(raw, characterData, equipmentData, artifactData)
        }.onFailure { LogHelper.e("Mica", "official panel rebuild failed aid=$aid", it) }.getOrNull()
    } else null
    val panelSkills = panel?.skills ?: OfficialSkillParser.parse(
        raw = raw,
        rank = rank,
        talentCons = characterData?.optJSONObject("talentCons")
    )
    if (panel != null) {
        baseMap["hp"] = panel.attr["hpBase"] ?: 0.0
        baseMap["atk"] = panel.attr["atkBase"] ?: 0.0
        baseMap["def"] = panel.attr["defBase"] ?: 0.0
        baseMap["spd"] = panel.attr["speedBase"] ?: 0.0
    }

    // 遗器：官方分 relics（4 件隧洞）+ ornaments（2 件位面饰品），需合并。
    val relicSrc = firstArray(raw, "relicList", "relic_list", "relics")
    val ornamentSrc = firstArray(raw, "ornamentList", "ornament_list", "ornaments")
    val relicUis = mutableListOf<RelicUi>()
    val setCounts = LinkedHashMap<Int, Int>()
    val relicInputs = mutableListOf<JSONObject>()
    listOfNotNull(relicSrc, ornamentSrc).forEach { arr ->
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(relicInputs::add)
    }
    for (o in relicInputs) {
        val r = parseRelic(o, name, baseMap) ?: continue
        relicUis.add(r)
        if (r.setId > 0) setCounts[r.setId] = (setCounts[r.setId] ?: 0) + 1
        r.main?.let { additions.add(it) }
        r.subs.forEach { additions.add(it.prop) }
    }

    // 行迹常驻属性只统计角色 skills 中已激活且 point_type != 2 的节点。
    val treeArr = panelSkills.allNodes
    val characterTreeArr = panelSkills.characterNodes
    for (i in 0 until characterTreeArr.length()) {
        val node = characterTreeArr.optJSONObject(i) ?: continue
        if (!OfficialSkillParser.isActivatedStatNode(node)) continue
        val pid = OfficialSkillParser.pointId(node)
        val lv = node.optInt("level", 0)
        if (pid <= 0 || lv <= 0) continue
        val props = EnkaNameMap.skillTreeProps(pid, lv) ?: continue
        for (j in 0 until props.length()) {
            props.optJSONObject(j)?.let { additions.add(propObject(it)) }
        }
    }

    // 光锥被动
    if (lcId > 0) {
        EnkaNameMap.lightConeProps(lcId, lcRank)?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { additions.add(propObject(it)) }
            }
        }
    }

    // 套装
    val localSets = mutableListOf<SetUi>()
    for ((setId, count) in setCounts) {
        EnkaNameMap.relicSetProps(setId, count)?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { additions.add(propObject(it)) }
            }
        }
        localSets.add(
            SetUi(
                name = EnkaNameMap.relicSetName(setId).ifBlank {
                    relicUis.firstOrNull { it.setId == setId }?.setName.orEmpty().ifBlank { "套装$setId" }
                },
                count = count,
                desc = EnkaNameMap.relicSetDesc(setId, count)
            )
        )
    }
    val sets = panel?.sets?.map { rebuilt ->
        localSets.firstOrNull { it.name == rebuilt.name }?.let { rebuilt.copy(desc = it.desc) } ?: rebuilt
    } ?: localSets

    val addArr = JSONArray()
    additions.filter { it.field.isNotBlank() && abs(it.value) > 1e-9 }
        .forEach { addArr.put(EnkaNameMap.propInfo(it.type, it.value)) }
    val statArr = EnkaNameMap.buildSkeletonStats(baseArr, addArr)

    fun statOf(field: String): JSONObject? {
        for (i in 0 until statArr.length()) {
            val o = statArr.optJSONObject(i) ?: continue
            if (o.optString("field", "") == field) return o
        }
        return null
    }

    val (elementName, elementField) = elementInfo(element)
    val flatFields = setOf("hp", "atk", "def", "spd")
    val rowsSpec = listOf(
        "hp" to "生命值",
        "atk" to "攻击力",
        "def" to "防御力",
        "spd" to "速度",
        "crit_rate" to "暴击率",
        "crit_dmg" to "暴击伤害",
        elementField to "伤害加成",
        "break_dmg" to "击破特攻",
        "effect_hit" to "效果命中",
        "effect_res" to "效果抵抗",
        "heal_rate" to "治疗加成",
        "energy_regen" to "能量恢复效率"
    )
    val stats = mutableListOf<StatRow>()
    for ((field, label) in rowsSpec) {
        val o = statOf(field)
        val isFlat = field in flatFields
        val white = when {
            isFlat -> baseMap[field] ?: 0.0
            field == "crit_rate" -> 0.05
            field == "crit_dmg" -> 0.50
            field == "energy_regen" -> 1.0
            else -> 0.0
        }
        val official = officialStats[field]
        val panelKey = when (field) {
            "spd" -> "speed"
            "crit_rate" -> "cpct"
            "crit_dmg" -> "cdmg"
            "break_dmg" -> "stance"
            "effect_hit" -> "effPct"
            "effect_res" -> "effDef"
            "heal_rate" -> "heal"
            "energy_regen" -> "recharge"
            elementField -> "dmg"
            else -> field
        }
        val rebuilt = panel?.attr?.get(panelKey)
        val total = if (rebuilt != null) {
            if (isFlat) rebuilt else rebuilt / 100.0
        } else {
            official?.value ?: o?.optDouble("value", 0.0) ?: white
        }
        val shownWhite = if (rebuilt != null) {
            val rawBase = if (isFlat) panel?.attr?.get("${panelKey}Base") else panel?.base?.get(panelKey)
            if (rawBase != null) {
                if (isFlat) rawBase else rawBase / 100.0
            } else white
        } else if (isFlat) {
            official?.base?.takeIf { it > 0.0 } ?: baseMap[field] ?: 0.0
        } else {
            official?.base?.takeIf { it > 0.0 } ?: white
        }
        val bonus = total - shownWhite
        fun fmt(v: Double) = if (isFlat) fmtInt(v) else String.format(Locale.US, "%.1f%%", v * 100)
        stats.add(
            StatRow(
                field = field,
                name = label,
                total = fmt(total),
                base = fmt(shownWhite),
                bonus = (if (bonus >= 0) "+" else "") + fmt(bonus),
                positive = bonus >= 0,
                totalValue = total,
                baseValue = shownWhite,
                bonusValue = bonus
            )
        )
    }
    // 欢愉度同样取原版重建属性，旧汇总仅作资源缺失时的兜底。
    val elationValue = panel?.attr?.get("joy")?.div(100.0)
        ?: officialStats["elation"]?.value
        ?: (0 until statArr.length()).mapNotNull { statArr.optJSONObject(it) }
            .firstOrNull { it.optString("field", "").contains("elation", true) }
            ?.optDouble("value", 0.0)
    if (elationValue != null && elationValue > 1e-9) {
        val v = elationValue
        stats.add(
            StatRow(
                field = "elation",
                name = "欢愉度",
                total = String.format(Locale.US, "%.1f%%", v * 100),
                base = "0.0%",
                bonus = String.format(Locale.US, "+%.1f%%", v * 100),
                positive = true,
                totalValue = v,
                baseValue = 0.0,
                bonusValue = v
            )
        )
    }

    // 本体技能、欢愉技与忆灵技能统一使用官方 remake 和显示等级。
    val skillArr = EnkaNameMap.buildSkillsDetail(treeArr, aid)
    val order = listOf("普攻", "战技", "天赋", "终结技", "秘技", "欢愉技", "忆灵技", "忆灵天赋")
    val talentKeys = mapOf(
        "普攻" to "a", "战技" to "e", "天赋" to "t", "终结技" to "q", "秘技" to "z",
        "欢愉技" to "xe", "忆灵技" to "me", "忆灵天赋" to "mt"
    )
    val skills = mutableListOf<SkillUi>()
    for (type in order) {
        var dictionary: JSONObject? = null
        for (i in 0 until skillArr.length()) {
            val candidate = skillArr.optJSONObject(i) ?: continue
            if (candidate.optString("typeText", "") == type) {
                dictionary = candidate
                break
            }
        }
        var official: JSONObject? = null
        for (i in 0 until treeArr.length()) {
            val candidate = treeArr.optJSONObject(i) ?: continue
            if (candidate.optString("remake", "") == type) {
                official = candidate
                break
            }
        }
        if (dictionary == null && official == null) continue
        val dictName = dictionary?.optString("name", "").orEmpty()
        val officialName = official?.optString("name", "").orEmpty()
        val dataName = talentKeys[type]?.let { MiaoDataClient.talentName(name, it) }.orEmpty()
        val skillName = dictName.takeIf { it.isNotBlank() && it != "技能" && it != type }
            ?: officialName.ifBlank { dataName.ifBlank { type } }
        val dictIcon = dictionary?.optString("icon", "").orEmpty()
        skills.add(
            SkillUi(
                name = skillName,
                typeText = type,
                level = official?.optInt("level", 1) ?: dictionary?.optInt("level", 1) ?: 1,
                icon = dictIcon.ifBlank { official?.optString("icon", "").orEmpty() }
            )
        )
    }

    // 副行迹与静态属性使用同一份官方激活节点，不再按显示等级猜测激活状态。
    val traces = mutableListOf<String>()
    val mainSuffix = setOf(1, 2, 3, 4, 7)
    for (i in 0 until characterTreeArr.length()) {
        val node = characterTreeArr.optJSONObject(i) ?: continue
        if (!OfficialSkillParser.isActivatedStatNode(node)) continue
        val pid = OfficialSkillParser.pointId(node)
        val lv = node.optInt("level", 0)
        if (pid <= 0 || lv <= 0 || pid % 100 in mainSuffix) continue
        val props = EnkaNameMap.skillTreeProps(pid, lv) ?: continue
        for (j in 0 until props.length()) {
            val o = props.optJSONObject(j) ?: continue
            val key = o.optString("type", "")
            if (key.isBlank()) continue
            val f = EnkaNameMap.propInfo(key, o.optDouble("value", 0.0)).optString("field", "")
            if (f.isNotBlank()) traces.add(f)
        }
    }

    // 光锥白值与突破边界来自原版重建，展示描述仍复用现有字典。
    val rebuiltWeapon = panel?.weapon
    val lc = if (rebuiltWeapon != null) {
        val officialDesc = eq?.optString("desc", "").orEmpty()
        LcUi(
            id = rebuiltWeapon.id,
            name = rebuiltWeapon.name,
            level = rebuiltWeapon.level,
            superimpose = rebuiltWeapon.affix,
            rarity = eq?.optInt("rarity", 0)?.takeIf { it > 0 } ?: EnkaNameMap.lcRarity(rebuiltWeapon.id),
            hp = fmtFlat(rebuiltWeapon.attr["hp"] ?: 0.0),
            atk = fmtFlat(rebuiltWeapon.attr["atk"] ?: 0.0),
            def = fmtFlat(rebuiltWeapon.attr["def"] ?: 0.0),
            desc = EnkaNameMap.lcDesc(rebuiltWeapon.id, rebuiltWeapon.affix).ifBlank { officialDesc }
        )
    } else if (lcId > 0) {
        val base = EnkaNameMap.lcBase(lcId, lcPromotion, lcLevel) ?: DoubleArray(4)
        val dictName = EnkaNameMap.lcName(lcId)
        val officialName = eq?.optString("name", "").orEmpty()
        val officialDesc = eq?.optString("desc", "").orEmpty()
        LcUi(
            id = lcId,
            name = if (dictName.isNotBlank() && dictName != "光锥$lcId") dictName
            else officialName.ifBlank { dictName },
            level = lcLevel,
            superimpose = lcRank,
            rarity = eq?.optInt("rarity", 0)?.takeIf { it > 0 } ?: EnkaNameMap.lcRarity(lcId),
            hp = fmtFlat(base[0]),
            atk = fmtFlat(base[1]),
            def = fmtFlat(base[2]),
            desc = EnkaNameMap.lcDesc(lcId, lcRank).ifBlank { officialDesc }
        )
    } else null

    // 遗器副词条合计（按 属性+百分比类型 聚合，避免大小生命混在一起）
    val sumGroups = LinkedHashMap<String, MutableList<Prop>>()
    relicUis.forEach { r ->
        r.subs.forEach { s ->
            val key = s.prop.field + if (s.prop.percent) "%" else ""
            sumGroups.getOrPut(key) { mutableListOf() }.add(s.prop)
        }
    }
    val affixSums = sumGroups.values.mapNotNull { list ->
        val p0 = list.firstOrNull() ?: return@mapNotNull null
        val total = list.sumOf { it.value }
        val base = subBaseValue(p0)
        val cnt = if (base > 0) total / base else 0.0
        if (cnt <= 0.01) return@mapNotNull null
        AffixUi(
            name = shortAffixName(p0),
            countText = String.format(Locale.US, "%.1f", cnt),
            countValue = cnt,
            valueText = if (p0.percent) String.format(Locale.US, "+%.1f%%", total * 100)
            else "+" + fmtFlat(total)
        )
    }.sortedByDescending { it.countValue }.take(12)

    val totalScore = relicUis.sumOf { it.score }
    val totalGrade = if (relicUis.isEmpty()) "-" else RelicScorer.scoreToGrade(totalScore / relicUis.size)
    LogHelper.i(
        "Mica",
        "avatar_detail parsed aid=$aid properties=${officialStats.size} relics=${relicUis.size} " +
            "skills=${skills.size} lightcone=$lcId"
    )

    return DetailUi(
        aid = aid,
        name = name,
        uid = uid,
        level = level,
        rank = rank,
        rarity = rarity,
        element = elementName,
        elementField = elementField,
        path = pathInfo(path),
        stats = stats,
        skills = skills,
        traces = traces.take(10),
        lc = lc,
        relics = relicUis.sortedBy { it.type },
        totalScore = totalScore,
        totalGrade = totalGrade,
        affixSums = affixSums,
        sets = sets,
        sourceTime = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date()),
        skillLevels = panelSkills.skillLevels,
        treeIds = panelSkills.treeIds,
        originalTalentLevels = panelSkills.originalTalentLevels,
        talentLevels = panelSkills.talentLevels,
        panel = panel
    )
}

private fun propObject(o: JSONObject): Prop {
    val key = propKeyOf(o)
    // 官方 avatar.properties 用 base/final；遗器主/副词条用 value。
    // 同一条函数复用，按是否带 final 字段区分口径。
    val hasFinal = o.has("final")
    val value = if (hasFinal) readDouble(o, "final") else readDouble(o, "value")
    val base = if (hasFinal) readDouble(o, "base") else 0.0
    val info = if (key.isNotBlank()) EnkaNameMap.propInfo(key, value) else JSONObject()
    return Prop(
        type = key,
        field = info.optString("field", ""),
        name = info.optString("name", key),
        percent = info.optBoolean("percent", false),
        value = value,
        base = base
    )
}

private fun parseRelic(o: JSONObject, charName: String, baseMap: Map<String, Double>): RelicUi? {
    val tid = o.optInt("id", 0).takeIf { v -> v > 0 } ?: o.optInt("tid", 0)
    var type = o.optInt("type", 0)
    if (type !in 1..6) type = o.optInt("pos", o.optInt("position", 0))
    if (type !in 1..6) type = (tid % 10).takeIf { v -> v in 1..6 } ?: 0
    if (type == 0) return null
    val level = o.optInt("level", 0)

    // 套装与单件信息优先用单件遗器 id 查静态元数据（官方节点通常不返回 set 字段）。
    val meta = EnkaNameMap.relicMeta(tid)
    val setObj = o.optJSONObject("set")
    val setId = meta?.optString("set_id", "")?.toIntOrNull()?.takeIf { it > 0 }
        ?: setObj?.optInt("id", 0)?.takeIf { it > 0 }
        ?: o.optInt("set_id", 0).takeIf { it > 0 }
        ?: o.optInt("setId", 0)
    val setName = meta?.optString("name", "")?.ifBlank { null }
        ?: setObj?.optString("name", "")?.ifBlank { null }
        ?: if (setId > 0) EnkaNameMap.relicSetName(setId) else ""

    val mainObj = o.optJSONObject("main_property") ?: o.optJSONObject("mainProperty")
        ?: o.optJSONObject("main_affix") ?: o.optJSONObject("main")
    var main = mainObj?.let { propObject(it).takeIf { p -> p.field.isNotBlank() } }
    // 官方主词条常只有 property_type 不带 value，按稀有度/等级查表重建。
    if (main != null && abs(main.value) < 1e-9) {
        val rebuilt = EnkaNameMap.mainAffixValue(tid, main.field, level)
        if (rebuilt != 0.0) main = main.copy(value = rebuilt)
    }

    // 米游社官方遗器副词条字段为 properties；其余别名兼容 Enka/Mihomo 数据。
    val subArr = firstArray(o, "sub_property_list", "subPropertyList", "sub_affix_list", "properties")
    val subs = mutableListOf<SubUi>()
    val scorePairs = mutableListOf<Pair<String, Double>>()
    val countTriples = mutableListOf<Triple<String, Double, Int>>()
    if (subArr != null) {
        for (i in 0 until subArr.length()) {
            val s = subArr.optJSONObject(i) ?: continue
            if (s.optBoolean("is_preview", false)) continue
            val p = propObject(s)
            if (p.field.isBlank()) continue
            val v = p.value
            val times = s.optInt("times", 0).takeIf { t -> t > 0 }
                ?: s.optInt("count", 0).takeIf { t -> t > 0 }
                ?: s.optInt("cnt", 1)
            val base = subBaseValue(p)
            val cnt = if (base > 0) v / base else 0.0
            subs.add(
                SubUi(
                    prop = p,
                    times = times,
                    label = relicPropLabel(p),
                    countText = if (cnt > 0) String.format(Locale.US, "%.1f", cnt) else "",
                    countValue = cnt
                )
            )
            scorePairs.add(p.type to v)
            countTriples.add(Triple(p.type, v, times))
        }
    }

    val baseHp = baseMap["hp"] ?: 0.0
    val baseAtk = baseMap["atk"] ?: 0.0
    val baseDef = baseMap["def"] ?: 0.0
    val score = runCatching {
        RelicScorer.scoreRelic(
            charName = charName,
            position = type,
            mainType = main?.type ?: "",
            mainValue = main?.value ?: 0.0,
            subList = scorePairs,
            baseHp = baseHp,
            baseAtk = baseAtk,
            baseDef = baseDef
        )
    }.getOrDefault(0.0)
    val fail = runCatching { RelicScorer.countFail(charName, countTriples) }.getOrDefault(0)

    return RelicUi(
        tid = tid,
        setId = if (setId > 0) setId else 0,
        setName = setName,
        type = type,
        level = level,
        main = main,
        subs = subs.sortedByDescending { it.countValue },
        score = score,
        grade = RelicScorer.scoreToGrade(score),
        failCount = fail
    )
}

// ==================== 页面 ====================

@Composable
private fun AvatarDetailScreen(avatarId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !dark
    }

    var data by remember { mutableStateOf<DetailUi?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { loadDetail(context, avatarId) }
        }.onSuccess { data = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient))
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = data?.name ?: "角色详情",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(text = "刷新", onClick = { reload++ })
            }

            when {
                loading && data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中…", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }
                error != null && data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error ?: "加载失败",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "点右上角刷新重试",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
                data != null -> DetailContent(data!!)
            }
        }
    }
}

@Composable
private fun DetailContent(d: DetailUi) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Header(d) }
        if (d.skills.isNotEmpty() || d.traces.isNotEmpty()) item { SkillPanel(d) }
        if (d.lc != null) item { LightConeCard(d.lc) }
        item { GlobalRankCard(d) }
        if (d.relics.isNotEmpty()) {
            item { ScoreBanner(d) }
            item { RelicDetailPanel(d.relics) }
        }
        if (d.sets.isNotEmpty()) item { SetPanel(d.sets) }
        item { DamageCalcCard(d) }
        item {
            Text(
                text = "数据源：米游社官方接口 · 评分口径：miao-plugin",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==================== 头部 ====================

@Composable
private fun Header(d: DetailUi) {
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val accent = elementColor(d.elementField)
    Box(
        Modifier
            .fillMaxWidth()
            .height(352.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface)
            .clipToBounds()
    ) {
        // 立绘：全身预览图，Fit 居中，保证整个角色完整露出
        PortraitImage(
            aid = d.aid,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.70f)
                .align(Alignment.CenterStart)
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Transparent,
                        0.30f to surface.copy(alpha = 0.12f),
                        0.50f to surface,
                        1.0f to surface
                    )
                )
        )

        // 标题（右上）
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = d.name,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "Lv.${d.level}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "UID ${d.uid}",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "·",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(Modifier.width(5.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${d.rank}唤",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (d.element.isNotBlank()) Chip(d.element, accent)
                if (d.path.isNotBlank()) Chip(d.path, MiuixTheme.colorScheme.onSurfaceSecondary)
            }
        }

        // 属性面板（右侧，覆盖在立绘之上）
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.48f)
                .padding(top = 82.dp, end = 10.dp)
        ) {
            d.stats.forEach { StatLine(it) }
        }

        // 命座图标（左下）：已解锁高亮，未解锁半透明
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(6) { i ->
                EidolonIcon(aid = d.aid, index = i + 1, unlocked = i < d.rank)
            }
        }

        // 数据源（右下）
        Text(
            text = "数据源：米游社 · ${d.sourceTime}",
            fontSize = 7.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 8.dp)
        )
    }
}

@Composable
private fun StatLine(r: StatRow) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatGlyph(r.field, MiuixTheme.colorScheme.onSurfaceSecondary, 10.dp)
            Spacer(Modifier.width(3.dp))
            Text(
                text = r.name,
                fontSize = 8.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = r.total,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        Column(
            Modifier.width(46.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = r.base,
                fontSize = 7.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1
            )
            Text(
                text = r.bonus,
                fontSize = 7.sp,
                color = if (r.positive) Color(0xFF4CC38A) else Color(0xFFE05B5B),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Chip(text: String, tint: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text = text, fontSize = 8.sp, color = tint, maxLines = 1)
    }
}

@Composable
private fun PortraitImage(aid: Int, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(aid) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(aid) {
        bmp = withContext(Dispatchers.IO) { EnkaImageLoader.portraitBitmap(context, aid) }
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            // 方图素材主体基本居中，用 Center 水平居中裁剪，避免角色被推到右侧或单边切半
            alignment = Alignment.Center
        )
    }
}

/** 命座图标：已解锁 → 灰色圆形底 + 图标染白；未解锁 → 半透明原图标。 */
@Composable
private fun EidolonIcon(aid: Int, index: Int, unlocked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (unlocked) Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF6B6B6B))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { alpha = if (unlocked) 1f else 0.35f },
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    if (unlocked) {
                        // 把所有不透明像素染白：RGB 置 0 后平移到 1.0（即 255），Alpha 保持原值。
                        // 4x5 矩阵：R'=G'=B'=1，A'=A
                        val cm = android.graphics.ColorMatrix(
                            floatArrayOf(
                                0f, 0f, 0f, 0f, 1f,
                                0f, 0f, 0f, 0f, 1f,
                                0f, 0f, 0f, 0f, 1f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                    }
                }
            },
            update = { iv ->
                val icon = when (index) {
                    3 -> "icon/skill/${aid}_skill.png"
                    5 -> "icon/skill/${aid}_ultimate.png"
                    else -> "icon/skill/${aid}_rank$index.png"
                }
                if (iv.tag != icon) {
                    iv.tag = icon
                    EnkaImageLoader.loadMihomoIcon(iv.context, icon, iv)
                }
            }
        )
    }
}

// ==================== 行迹 ====================

@Composable
private fun SkillPanel(d: DetailUi) {
    val ctx = LocalContext.current
    Panel {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            d.skills.forEach { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = s.typeText,
                        fontSize = 8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(38.dp)) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (s.icon.isNotBlank()) {
                                NetworkImage(
                                    key = "skill-${s.icon}",
                                    load = { iv -> EnkaImageLoader.loadMihomoIcon(ctx, s.icon, iv) },
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(14.dp)
                                .align(Alignment.BottomCenter)
                                .clip(CircleShape)
                                .background(MiuixTheme.colorScheme.surfaceContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${s.level}",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 光锥 ====================

@Composable
private fun LightConeCard(lc: LcUi) {
    val ctx = LocalContext.current
    Panel {
        Row {
            Box(
                Modifier
                    .size(width = 78.dp, height = 100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
            ) {
                NetworkImage(
                    key = "lc-${lc.id}",
                    load = { iv -> EnkaImageLoader.loadLightCone(ctx, lc.id, iv) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = lc.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Lv.${lc.level}",
                        fontSize = 9.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "叠影${lc.superimpose}阶",
                            fontSize = 8.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LcStat("hp", lc.hp)
                    LcStat("atk", lc.atk)
                    LcStat("def", lc.def)
                }
            }
        }
        if (lc.desc.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = lc.desc,
                fontSize = 9.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun LcStat(field: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatGlyph(field, MiuixTheme.colorScheme.onSurfaceSecondary, 11.dp)
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

// ==================== 全球遗器排名（scoremyrelic） ====================

private sealed class RankState {
    object Loading : RankState()
    data class Ready(val r: RelicLeaderboard.Result) : RankState()
    data class Empty(val msg: String) : RankState()
}

@Composable
private fun GlobalRankCard(d: DetailUi) {
    val ctx = LocalContext.current
    var state by remember(d.aid, d.totalScore) {
        mutableStateOf<RankState>(RankState.Loading)
    }

    LaunchedEffect(d.aid, d.totalScore) {
        if (d.totalScore <= 0.0) {
            state = RankState.Empty("暂无可用于排名的遗器评分")
            return@LaunchedEffect
        }
        val r = RelicLeaderboard.query(d.aid, d.uid, d.totalScore)
        state = when {
            r == null -> RankState.Empty("该角色暂无榜单数据（仅热门角色开放）")
            r.error != null -> RankState.Empty("榜单加载失败，请检查网络后重试")
            r.myRank <= 0 -> RankState.Empty("榜单尚未刷新，请稍后再试")
            else -> RankState.Ready(r)
        }
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "全球遗器排名",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "scoremyrelic · 每小时",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            RankState.Loading -> Text(
                text = "正在获取全球排名…",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            is RankState.Empty -> Text(
                text = s.msg,
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )

            is RankState.Ready -> {
                val rank = s.r.myRank
                val percent = ((1.0 - rank.toDouble() / (s.r.total + 1)) * 100)
                    .coerceIn(0.0, 100.0)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "第 ${fmtInt(rank.toDouble())} 名",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Text(
                        text = "  / 共 ${fmtInt(s.r.total.toDouble())} 人",
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "你的评分 ${String.format(Locale.US, "%.1f", d.totalScore)} · 超过 " +
                        "${String.format(Locale.US, "%.1f", percent)}% 玩家",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                if (s.r.topName.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "榜首 ${s.r.topName} · ${String.format(Locale.US, "%.1f", s.r.topScore)} 分",
                        fontSize = 8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "榜单更新 ${s.r.updatedText}" +
                        if (s.r.estimated) " · 参考名次（本地评分插入榜单估算）" else "",
                    fontSize = 8.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Fribbels 伤害评分 ↗",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openUrl(ctx, "https://fribbels.github.io/hsr-optimizer/")
                }
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = "stardb 排名 ↗",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openUrl(ctx, "https://stardb.gg/")
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "数据来源：api.scoremyrelic.com · 仅收录在该站查询过的账号",
            fontSize = 8.sp,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

// ==================== 评级分色 ====================

/**
 * 评级字体颜色：
 * ACE+（含 MAX）→ 红色；S / SS / SSS → 黄色；A / B → 蓝色；C 及以下 → 绿色。
 */
private fun gradeColor(grade: String): Color = when (grade.uppercase()) {
    "MAX", "ACE" -> Color(0xFFE74C3C)            // 红
    "SSS", "SS", "S" -> Color(0xFFE6A23C)        // 黄
    "A", "B" -> Color(0xFF4D8CF7)                 // 蓝
    else -> Color(0xFF4CC38A)                     // 绿（C/D/-）
}

// ==================== 评分横幅 ====================

@Composable
private fun ScoreBanner(d: DetailUi) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(86.dp)) {
                Text(
                    text = "圣遗物总分",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(Modifier.height(2.dp))
                Column {
                    Text(
                        text = d.totalGrade,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor(d.totalGrade)
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f", d.totalScore),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "圣遗物评级",
                    fontSize = 8.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(58.dp)
                    .background(MiuixTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                d.affixSums.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { a ->
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = a.countText,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.width(2.dp))
                                    Text(
                                        text = a.name,
                                        fontSize = 8.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                        maxLines = 1
                                    )
                                }
                                Text(
                                    text = a.valueText,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (d.affixSums.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "评分规则：通用",
                    fontSize = 8.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "评分为喵喵版规则，仅供参考与娱乐",
                    fontSize = 8.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }
    }
}

// ==================== 遗器明细 ====================

@Composable
private fun RelicDetailPanel(relics: List<RelicUi>) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "遗器强化明细",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· 共${relics.size}件",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "词条数按词条基础值折算",
                fontSize = 8.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        relics.chunked(3).forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { r -> RelicCard(r, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RelicCard(r: RelicUi, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(7.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = r.setName.ifBlank { EnkaNameMap.relicTypeName(r.type) },
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                lineHeight = 11.sp,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (r.setId > 0) {
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    NetworkImage(
                        key = "relic-${r.setId}-${r.type}",
                        load = { iv -> EnkaImageLoader.loadRelic(ctx, r.setId, r.type, iv) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = String.format(Locale.US, "%.1f分", r.score),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "- ${r.grade}",
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                color = gradeColor(r.grade)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "+${r.level}",
                fontSize = 7.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        Spacer(Modifier.height(4.dp))
        val main = r.main
        if (main != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatGlyph(main.field, MiuixTheme.colorScheme.onSurface, 9.dp)
                Spacer(Modifier.width(2.dp))
                Text(
                    text = relicPropLabel(main),
                    fontSize = 7.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "+${main.text}",
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
        }
        r.subs.take(4).forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = s.countText,
                    fontSize = 6.5.sp,
                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.width(15.dp),
                    maxLines = 1
                )
                StatGlyph(s.prop.field, MiuixTheme.colorScheme.onSurfaceSecondary, 8.dp)
                Spacer(Modifier.width(2.dp))
                Text(
                    text = s.label,
                    fontSize = 7.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "+${s.prop.text}",
                    fontSize = 7.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SetPanel(sets: List<SetUi>) {
    Panel {
        sets.forEachIndexed { idx, s ->
            if (idx > 0) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = s.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "×${s.count}",
                    fontSize = 9.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            if (s.desc.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = s.desc,
                    fontSize = 8.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DamagePlaceholder() {
    Column {
        Text(
            text = "伤害计算",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(12.dp)
        ) {
            Text(
                text = "暂未接入：需要完整技能倍率与公式库，后续版本补充。",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                lineHeight = 14.sp
            )
        }
    }
}

// ==================== 通用容器 ====================

@Composable
fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun NetworkImage(key: String, load: (ImageView) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { iv ->
            if (iv.tag != key) {
                iv.tag = key
                load(iv)
            }
        }
    )
}

// ==================== 属性小图标 ====================

@Composable
private fun StatGlyph(field: String, tint: Color, size: Dp = 14.dp) {
    val res = statIconRes(field)
    if (res != 0) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(size),
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit
        )
        return
    }
    Canvas(Modifier.size(size)) {
        drawCircle(tint, this.size.minDimension * 0.14f, center)
    }
}

/** 官方属性图标（StarRailRes icon/property，128×128 白色透明底，按主题色 tint）。 */
private fun statIconRes(field: String): Int = when (field) {
    "hp" -> R.drawable.stat_ic_hp
    "atk" -> R.drawable.stat_ic_atk
    "def" -> R.drawable.stat_ic_def
    "spd" -> R.drawable.stat_ic_spd
    "crit_rate" -> R.drawable.stat_ic_crit_rate
    "crit_dmg" -> R.drawable.stat_ic_crit_dmg
    "break_dmg" -> R.drawable.stat_ic_break
    "effect_hit" -> R.drawable.stat_ic_effect_hit
    "effect_res" -> R.drawable.stat_ic_effect_res
    "heal_rate" -> R.drawable.stat_ic_heal
    "energy_regen", "sp_rate" -> R.drawable.stat_ic_energy
    "elation" -> R.drawable.stat_ic_joy
    "physical_dmg", "dmg" -> R.drawable.stat_ic_physical
    "fire_dmg" -> R.drawable.stat_ic_fire
    "ice_dmg" -> R.drawable.stat_ic_ice
    "thunder_dmg" -> R.drawable.stat_ic_thunder
    "wind_dmg" -> R.drawable.stat_ic_wind
    "quantum_dmg" -> R.drawable.stat_ic_quantum
    "imaginary_dmg" -> R.drawable.stat_ic_imaginary
    else -> 0
}
