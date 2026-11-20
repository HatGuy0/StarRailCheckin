package com.mh.starrailcheckin.ui.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远端拉取 StarRailRes 技能数据（character_skills.json），磁盘缓存兜底。
 *
 * 数据源：Mar-7th/StarRailRes@master/index_min/cn/character_skills.json
 * 格式：{ "150601": { name, type, type_text, params: [Lv1倍率, Lv2倍率, ...], element, ... } }
 *
 * 出新角色时 Mar-7th 更完 JSON → App 下次启动自动拿到新角色技能倍率。
 */
object GameDataClient {

    private const val CACHE_FILE = "sr_character_skills.json"

    /** 多镜像依次尝试：jsDelivr 优先（国内 CDN），raw 兜底。 */
    private val SKILLS_URLS = listOf(
        "https://cdn.jsdelivr.net/gh/Mar-7th/StarRailRes@master/index_min/cn/character_skills.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/Mar-7th/StarRailRes/master/index_min/cn/character_skills.json",
        "https://ghfast.top/https://raw.githubusercontent.com/Mar-7th/StarRailRes/master/index_min/cn/character_skills.json",
        "https://raw.githubusercontent.com/Mar-7th/StarRailRes/master/index_min/cn/character_skills.json"
    )

    @Volatile
    private var cachedSkills: JSONObject? = null

    /** 拉取远端 JSON，失败返回 null；成功时同时落盘缓存。 */
    suspend fun ensureLoaded(ctx: Context): Boolean {
        if (cachedSkills != null) return true
        return withContext(Dispatchers.IO) {
            // 1) 先尝试磁盘缓存
            val disk = File(ctx.cacheDir, CACHE_FILE)
            if (disk.exists() && disk.length() > 20000) {
                val cached = runCatching {
                    JSONObject(disk.readText())
                }.getOrNull()
                if (cached != null && cached.length() > 100) {
                    cachedSkills = cached
                }
            }

            // 2) 尝试远端刷新（磁盘缓存基础上叠加新数据）
            for (url in SKILLS_URLS) {
                val body = httpGet(url) ?: continue
                val parsed = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (parsed.length() > 100) {
                    cachedSkills = parsed
                    runCatching { disk.writeText(parsed.toString()) }
                    return@withContext true
                }
            }

            // 3) 最终看磁盘缓存是否可用
            cachedSkills != null
        }
    }

    /**
     * 查技能倍率：传入角色 aid（如 1506）+ 技能类型 + 技能等级，返回该等级的主倍率。
     * 技能类型匹配 priority: typeText > effect > type。
     *
     * 返回 null = 没找到技能；返回 Double.NaN = 技能存在但没配置 params。
     */
    fun findMultiplier(aid: Int, skillType: String, level: Int): Double? {
        val all = cachedSkills ?: return null
        val typeNorm = skillType.trim()
        val priorityOrder = listOf(typeNorm, normalizeType(skillType))

        // 前缀匹配：1506 → 所有 "1506xx" 开头的 skill
        val prefix = aid.toString()
        val bestKey = run {
            var best: String? = null
            var bestPriority = Int.MAX_VALUE
            val keys = all.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!key.startsWith(prefix)) continue
                val skillObj = all.optJSONObject(key) ?: continue
                val tt = skillObj.optString("type_text", "")
                val effect = skillObj.optString("effect", "")
                val type = skillObj.optString("type", "")

                val pIndex = priorityOrder.indexOfFirst {
                    it.isNotBlank() && (tt == it || effect == it || type == it ||
                        type.startsWith(it) || effect.startsWith(it))
                }
                if (pIndex in 0 until bestPriority) {
                    bestPriority = pIndex
                    best = key
                }
            }
            best
        }

        val skillObj = bestKey?.let { all.optJSONObject(it) } ?: return null
        val paramsArr = skillObj.optJSONArray("params") ?: return null
        if (paramsArr.length() == 0) return null
        val slot = (level - 1).coerceIn(0, paramsArr.length() - 1)
        val slotObj = paramsArr.opt(slot)

        return when (slotObj) {
            is Number -> slotObj.toDouble()
            is org.json.JSONArray -> {
                // params 数组通常是 [baseMult, 额外参数...]，主倍率取第一个
                val first = slotObj.opt(0)
                when (first) {
                    is Number -> first.toDouble()
                    else -> null
                }
            }
            else -> null
        }
    }

    /** 给调试 / 日志用：当前已加载多少个技能。 */
    fun skillCount(): Int = cachedSkills?.length() ?: 0

    // ============ 辅助 ============

    /** 把 SkillUi.typeText / SkillUi.type 映射到 StarRailRes 的 type 字段。 */
    private fun normalizeType(typeText: String): String = when {
        typeText.contains("普攻") -> "Normal"
        typeText.contains("战技") -> "BPSkill"
        typeText.contains("终结技") || typeText.contains("大招") -> "Ultra"
        typeText.contains("天赋") -> "Talent"
        typeText.contains("秘技") -> "Maze"
        typeText.contains("欢愉") -> "ElationDamage"
        else -> typeText
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                setRequestProperty("Accept", "application/json")
            }
            conn = c
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }
        finally { runCatching { conn?.disconnect() } }
    }
}

// ============ 缩放属性字典 ============

/**
 * 绝大多数角色的技能乘 ATK。少数例外：
 *   寒鸦 (1513) / 三月七·真妃 (1515) — 乘 DEF
 *   花火 (1509) / 藿藿 (1507) / 阿哈 (1511) / 苍角 (1510) / 流萤 (1514) / 砂金 (1508) — 部分技能乘 HP
 *
 * 返回缩放属性："atk" / "hp" / "def"。默认 "atk"。
 */
fun scalingStatOf(aid: Int, skillType: String): String {
    // 先按角色 + 技能类型精确匹配（某些角色普攻乘 ATK 但终结技乘 HP）
    val specialBySkill = mapOf(
        // 寒鸦：战技/终结技乘 DEF，普攻还是 ATK
        (1513 to "普攻") to "atk",
        (1513 to "战技") to "def",
        (1513 to "终结技") to "def",
        (1513 to "天赋") to "def",
        // 苍角：终结技乘 HP
        (1510 to "终结技") to "hp",
        // 花火：终结技乘 HP
        (1509 to "终结技") to "hp"
    )
    specialBySkill[(aid to skillType)]?.let { return it }

    // 角色级兜底：整角色所有技能都乘 HP/DEF
    val byChar = mapOf(
        1507 to "hp",    // 藿藿
        1508 to "hp",    // 砂金（终结技乘 HP）
        1511 to "hp",    // 阿哈（欢愉系多技能乘 HP）
        1514 to "hp",    // 流萤（毁灭·物理 HP 系）
        1515 to "def"    // 三月七·真妃 DEF 系
    )
    return byChar[aid] ?: "atk"
}

// ============ 元素 → 目标抗性 ============

/**
 * 目标对某元素的默认抗性（同元素 20% / 量子 40% / 虚数 50% / 物理 50% / 弱点 0%）。
 * 返回值是小数（0.2 = 20%）。弱点击破后 RES → 0，调用方把 isBroken 设 true 会自动应用 resMult 为 1.0。
 */
fun defaultTargetRes(targetElement: String): Double = when {
    targetElement.equals("Physical", true) -> 0.50
    targetElement.equals("Imaginary", true) -> 0.50
    targetElement.equals("Quantum", true) -> 0.40
    // Fire / Ice / Thunder / Wind / 其他同元素弱点 → 默认 20%
    else -> 0.20
}

/** 从 DetailUi.elementField（"quantum_dmg"/"fire_dmg"）转成 StarRailRes 元素名（"Quantum"/"Fire"）。 */
fun detailFieldToElement(field: String): String = when {
    field.contains("Physical", true) -> "Physical"
    field.contains("fire", true) -> "Fire"
    field.contains("ice", true) -> "Ice"
    field.contains("thunder", true) -> "Thunder"
    field.contains("wind", true) -> "Wind"
    field.contains("quantum", true) -> "Quantum"
    field.contains("imaginary", true) -> "Imaginary"
    else -> ""
}
