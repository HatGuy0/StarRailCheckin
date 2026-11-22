package com.mh.starrailcheckin.ui.profile

import android.content.Context
import com.mh.starrailcheckin.network.ApiHosts
import com.mh.starrailcheckin.util.LogHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enka 数据名称映射 + 面板数据表（内置 assets 离线兜底 + 磁盘缓存 + 远端字典刷新）。
 *
 * 名字映射优先级：磁盘缓存/远端刷新结果 > 内置 assets 快照；
 * 远端只补本地缺失的角色/光锥，不会用旧名覆盖本地已有的新名。
 */
object EnkaNameMap {
    private var characters: Map<String, String> = emptyMap()
    private var lightCones: Map<String, String> = emptyMap()
    private var relicSets: Map<String, String> = emptyMap()
    private var relics: Map<String, String> = emptyMap()
    private var relicMeta: Map<String, JSONObject> = emptyMap()   // 遗器全字段（set_id/main_affix_id/sub_affix_id/icon/rarity/type）
    private var charMeta: Map<String, JSONObject> = emptyMap()  // 角色全字段（element/path 等兜底）
    private var charIdByName: Map<String, Int> = emptyMap()   // 抽卡记录按中文名反查角色ID
    private var lcIdByName: Map<String, Int> = emptyMap()     // 抽卡记录按中文名反查光锥ID
    private var lcRarityMap: Map<String, Int> = emptyMap()    // 光锥稀有度（全角色面板光锥星级用）

    // 面板计算数据
    private var promotions: JSONObject? = null        // 角色突破白值（角色裸白值）
    private var skillTrees: JSONObject? = null      // 行迹节点
    private var lightConePromotions: JSONObject? = null  // 光锥突破白值
    private var lightConeRanks: JSONObject? = null  // 光锥被动属性
    private var relicSetsData: JSONObject? = null  // 遗器套装效果
    private var relicWeights: JSONObject? = null    // 遗器评分角色权重表（miao 口径）
    private var loaded = false

    // 远端词条字典（raw 兜底角色遗器翻译用），磁盘缓存
    private var mainAffixes: JSONObject? = null
    private var subAffixes: JSONObject? = null
    private var properties: JSONObject? = null

    /**
     * 上游字典尚未收录的新角色本地兜底：id → [中文名, element 英文, path 英文]。
     * 上游静态表收录后，本地字典/缓存会自动带出真实数据，这里仅保证骨架角色不显示成「角色1511」。
     */
    private val NEW_CHAR_FALLBACK = mapOf(
        1511 to Triple("星神★阿哈", "Quantum", "Elation")
    )

    /**
     * 新角色角色裸白值兜底（不含光锥，Lv.80 / 突破6 的 hp/atk/def/spd）。
     * 仅在 promotions 表也缺该角色时使用；光锥基础值由 lightConePromotions 单独叠加。
     * 1511 裸白值取自 StarRailRes 突破曲线：HP1047.82 / ATK679.14 / DEF509.36 / SPD94。
     */
    private val NEW_CHAR_BASE = mapOf(
        1511 to doubleArrayOf(1047.82, 679.14, 509.36, 94.0)
    )

    private const val CACHE_FILE = "enka_name_map.json"
    private const val AFFIX_CACHE_FILE = "enka_relic_affixes.json"
    private const val STRUCT_CACHE_FILE = "enka_struct.json"

    // 远端角色/光锥字典（id → 名称），多源依次尝试；失败不阻塞，回退内置与缓存
    private val REMOTE_CHARACTERS = listOf(
        "${ApiHosts.VIZUAL_BASE}/db/cn/characters.json"
    )
    private val REMOTE_LIGHT_CONES = listOf(
        "${ApiHosts.VIZUAL_BASE}/db/cn/light_cones.json"
    )
    // 远端遗器词条 / 属性字典
    private val REMOTE_MAIN_AFFIX = "${ApiHosts.VIZUAL_BASE}/db/cn/relic_main_affixes.json"
    private val REMOTE_SUB_AFFIX = "${ApiHosts.VIZUAL_BASE}/db/cn/relic_sub_affixes.json"
    private val REMOTE_PROPERTIES = "${ApiHosts.VIZUAL_BASE}/db/cn/properties.json"
    // 远端结构化数据表（StarRailRes 上游，多镜像依次尝试；拉取成功即覆盖本地缓存，跟随上游更新）
    private val SR_RES_BASES = listOf(
        "${ApiHosts.JSDELIVERT_MAR7TH}/index_min/cn/"
    ) + ApiHosts.GH_PROXIES.map { "${it}${ApiHosts.GITHUB_RAW_MAR7TH}/index_min/cn/" } +
        listOf("${ApiHosts.GITHUB_RAW_MAR7TH}/index_min/cn/")
    private fun srResUrls(file: String) = SR_RES_BASES.map { it + file }
    private val REMOTE_PROMOTIONS = srResUrls("character_promotions.json")
    private val REMOTE_SKILL_TREES = srResUrls("character_skill_trees.json")
    private val REMOTE_LC_PROMOTIONS = srResUrls("light_cone_promotions.json")
    // 远端遗器评分角色权重表（miao-plugin 上游 JS，按角色名补缺合并）
    private val REMOTE_WEIGHTS = "${ApiHosts.GITHUB_RAW_MIAO}/resources/meta-sr/artifact/artis-mark.js"

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        val builtinChars = loadNameMap(ctx, "characters")
        val builtinLcs = loadNameMap(ctx, "light_cones")
        relicSets = loadNameMap(ctx, "relic_sets")
        relics = loadNameMap(ctx, "relics")
        relicMeta = loadRelicMeta(ctx)
        charMeta = loadCharMeta(ctx)
        lcRarityMap = loadLcRarity(ctx)

        // 磁盘缓存覆盖内置快照
        val cache = readNameCache(ctx)
        val cachedChars = cache?.optJSONObject("characters")
        val cachedLcs = cache?.optJSONObject("light_cones")
        characters = mergeNameMap(builtinChars, cachedChars)
        lightCones = mergeNameMap(builtinLcs, cachedLcs)

        // 词条字典磁盘缓存（远端刷新后落盘，离线兜底）
        val affixCache = readAffixCache(ctx)
        mainAffixes = affixCache?.optJSONObject("main")
        subAffixes = affixCache?.optJSONObject("sub")
        properties = affixCache?.optJSONObject("props")

        // 突破白值 / 行迹节点 / 光锥白值：本地内置为底，磁盘缓存（上次远端成功快照）覆盖叠加
        val structCache = readStructCache(ctx)
        promotions = mergeJsonObjectOverride(loadJson(ctx, "character_promotions"), structCache?.optJSONObject("promotions"))
        skillTrees = mergeJsonObjectOverride(loadJson(ctx, "character_skill_trees"), structCache?.optJSONObject("skill_trees"))
        lightConePromotions = mergeJsonObjectOverride(loadJson(ctx, "light_cone_promotions"), structCache?.optJSONObject("lc_promotions"))
        relicWeights = mergeJsonObject(loadWeightAssets(ctx), structCache?.optJSONObject("weights"))
        lightConeRanks = loadJson(ctx, "light_cone_ranks")
        relicSetsData = loadJson(ctx, "relic_sets")
        rebuildNameMaps()
        loaded = true
    }

    /** 异步刷新远端字典：合并缺失项后更新内存并落盘；任何失败静默回退。 */
    suspend fun refreshRemote(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            load(ctx)
            val remoteChars = fetchNameMap(REMOTE_CHARACTERS)
            val remoteLcs = fetchNameMap(REMOTE_LIGHT_CONES)
            synchronized(this@EnkaNameMap) {
                // 只补本地缺失项，远端旧名不覆盖本地已有新名
                if (remoteChars.isNotEmpty()) {
                    characters = characters + remoteChars.filterKeys { it !in characters }
                }
                if (remoteLcs.isNotEmpty()) {
                    lightCones = lightCones + remoteLcs.filterKeys { it !in lightCones }
                }
                rebuildNameMaps()
                persistNameMap(ctx)
            }
            // 词条/属性字典（与角色无关的通用表），拉取成功即覆盖缓存
            val main = fetchJson(REMOTE_MAIN_AFFIX)
            val sub = fetchJson(REMOTE_SUB_AFFIX)
            val props = fetchJson(REMOTE_PROPERTIES)
            if (main != null || sub != null || props != null) {
                synchronized(this@EnkaNameMap) {
                    main?.let { mainAffixes = it }
                    sub?.let { subAffixes = it }
                    props?.let { properties = it }
                    persistAffixCache(ctx)
                }
            }
            // 突破白值 / 行迹节点 / 光锥白值：远端成功即覆盖缓存，跟随 StarRailRes 上游更新
            val remotePromo = fetchJsonList(REMOTE_PROMOTIONS)
            val remoteTree = fetchJsonList(REMOTE_SKILL_TREES)
            val remoteLcPromo = fetchJsonList(REMOTE_LC_PROMOTIONS)
            if (remotePromo != null || remoteTree != null || remoteLcPromo != null) {
                synchronized(this@EnkaNameMap) {
                    promotions = mergeJsonObjectOverride(promotions, remotePromo)
                    skillTrees = mergeJsonObjectOverride(skillTrees, remoteTree)
                    lightConePromotions = mergeJsonObjectOverride(lightConePromotions, remoteLcPromo)
                    persistStructCache(ctx)
                }
            }
            // 遗器评分权重表：解析上游 JS，按角色名补缺合并（本地已有优先）
            val remoteWeights = fetchText(REMOTE_WEIGHTS)?.let { parseMiaoWeightJs(it) }
            if (remoteWeights != null) {
                synchronized(this@EnkaNameMap) {
                    relicWeights = mergeJsonObject(relicWeights, remoteWeights)
                    persistStructCache(ctx)
                }
            }
        }
    }

    /** 沉淀服务端返回的 id→名称（面板/抽卡联网拿到新角色名时调用），写入内存与磁盘缓存。 */
    fun rememberCharName(ctx: Context, id: Int, name: String) =
        rememberName(ctx, id, name, isChar = true)

    /** 沉淀服务端返回的光锥 id→名称。 */
    fun rememberLcName(ctx: Context, id: Int, name: String) =
        rememberName(ctx, id, name, isChar = false)

    private fun rememberName(ctx: Context, id: Int, name: String, isChar: Boolean) {
        if (id <= 0 || name.isBlank()) return
        val key = id.toString()
        val trimmed = name.trim()
        synchronized(this) {
            val exists = if (isChar) characters[key] else lightCones[key]
            if (exists == trimmed) return
            if (isChar) characters = characters + (key to trimmed)
            else lightCones = lightCones + (key to trimmed)
            rebuildNameMaps()
            persistNameMap(ctx)
        }
    }

    private fun rebuildNameMaps() {
        charIdByName = characters.entries.associate { it.value.trim() to (it.key.toIntOrNull() ?: 0) }
        lcIdByName = lightCones.entries.associate { it.value.trim() to (it.key.toIntOrNull() ?: 0) }
    }

    private fun mergeNameMap(
        builtin: Map<String, String>,
        cached: JSONObject?
    ): Map<String, String> {
        val out = mutableMapOf<String, String>()
        out.putAll(builtin)
        cached?.let {
            val keys = it.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = it.optString(k, "")
            }
        }
        return out
    }

    private fun loadNameMap(ctx: Context, name: String): Map<String, String> {
        return try {
            val text = ctx.assets.open("enka/$name.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val m = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = json.optJSONObject(k)?.optString("name", k) ?: k
            }
            m
        } catch (_: Exception) { emptyMap() }
    }

    private fun loadJson(ctx: Context, name: String): JSONObject? {
        return try {
            val text = ctx.assets.open("enka/$name.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) { null }
    }

    /** 内置遗器评分权重表（assets 根目录 miao_weights.json）。 */
    private fun loadWeightAssets(ctx: Context): JSONObject? {
        return try {
            val text = ctx.assets.open("miao_weights.json").bufferedReader().use { it.readText() }
            JSONObject(text)
        } catch (_: Exception) { null }
    }

    /** 本地遗器全字段（tid → 条目），用于 raw 遗器翻译时取套装/词条分组/图标。 */
    private fun loadRelicMeta(ctx: Context): Map<String, JSONObject> {
        return try {
            val text = ctx.assets.open("enka/relics.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val m = mutableMapOf<String, JSONObject>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                json.optJSONObject(k)?.let { m[k] = it }
            }
            m
        } catch (_: Exception) { emptyMap() }
    }

    /** 本地角色全字段（id → 条目），用于骨架角色取元素/命途。 */
    private fun loadCharMeta(ctx: Context): Map<String, JSONObject> {
        return try {
            val text = ctx.assets.open("enka/characters.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val m = mutableMapOf<String, JSONObject>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                json.optJSONObject(k)?.let { m[k] = it }
            }
            m
        } catch (_: Exception) { emptyMap() }
    }

    fun charElement(id: Int): String =
        charMeta[id.toString()]?.optString("element", "") ?: NEW_CHAR_FALLBACK[id]?.second ?: ""
    fun charPath(id: Int): String =
        charMeta[id.toString()]?.optString("path", "") ?: NEW_CHAR_FALLBACK[id]?.third ?: ""

    /** 角色稀有度（4/5星），接口未返回时用本地字典兜底。 */
    fun charRarity(id: Int): Int = charMeta[id.toString()]?.optInt("rarity", 0) ?: 0

    /** 大招能量上限（max_sp，如 100/120/130/140），缺省返回 0。 */
    fun charMaxSp(id: Int): Int = charMeta[id.toString()]?.optInt("max_sp", 0) ?: 0

    /** 光锥稀有度（3/4/5星），读内置字典，缺省按 4 星。 */
    fun lcRarity(id: Int): Int = lcRarityMap[id.toString()] ?: 4

    private fun loadLcRarity(ctx: Context): Map<String, Int> {
        return try {
            val text = ctx.assets.open("enka/light_cones.json").bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val m = mutableMapOf<String, Int>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = json.optJSONObject(k)?.optInt("rarity", 4) ?: 4
            }
            m
        } catch (_: Exception) { emptyMap() }
    }

    private fun readAffixCache(ctx: Context): JSONObject? {
        return try {
            val f = java.io.File(ctx.filesDir, AFFIX_CACHE_FILE)
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (_: Exception) { null }
    }

    private fun persistAffixCache(ctx: Context) {
        runCatching {
            val out = JSONObject()
            mainAffixes?.let { out.put("main", it) }
            subAffixes?.let { out.put("sub", it) }
            properties?.let { out.put("props", it) }
            java.io.File(ctx.filesDir, AFFIX_CACHE_FILE).writeText(out.toString())
        }.onFailure { LogHelper.e("EnkaNameMap", "持久化副词条缓存失败", it) }
    }

    private fun readStructCache(ctx: Context): JSONObject? {
        return try {
            val f = java.io.File(ctx.filesDir, STRUCT_CACHE_FILE)
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (_: Exception) { null }
    }

    private fun persistStructCache(ctx: Context) {
        runCatching {
            val out = JSONObject()
            promotions?.let { out.put("promotions", it) }
            skillTrees?.let { out.put("skill_trees", it) }
            lightConePromotions?.let { out.put("lc_promotions", it) }
            relicWeights?.let { out.put("weights", it) }
            java.io.File(ctx.filesDir, STRUCT_CACHE_FILE).writeText(out.toString())
        }.onFailure { LogHelper.e("EnkaNameMap", "持久化面板结构缓存失败", it) }
    }

    /** 把 extra 中 base 缺失的键并入 base；本地已有条目优先，不被远端覆盖（权重表用）。 */
    private fun mergeJsonObject(base: JSONObject?, extra: JSONObject?): JSONObject? {
        if (extra == null) return base
        val out = base ?: JSONObject()
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (!out.has(k)) out.put(k, extra.get(k))
        }
        return out
    }

    /** 远端快照覆盖合并：extra 中同 id 条目直接替换 base，保证跟随上游数值更新。 */
    private fun mergeJsonObjectOverride(base: JSONObject?, extra: JSONObject?): JSONObject? {
        if (extra == null) return base
        val out = base ?: JSONObject()
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out.put(k, extra.get(k))
        }
        return out
    }

    private fun readNameCache(ctx: Context): JSONObject? {
        return try {
            val f = java.io.File(ctx.filesDir, CACHE_FILE)
            if (!f.exists()) null else JSONObject(f.readText())
        } catch (_: Exception) { null }
    }

    private fun persistNameMap(ctx: Context) {
        runCatching {
            val out = JSONObject()
            out.put("characters", JSONObject(characters))
            out.put("light_cones", JSONObject(lightCones))
            java.io.File(ctx.filesDir, CACHE_FILE).writeText(out.toString())
        }.onFailure { LogHelper.e("EnkaNameMap", "持久化名称映射缓存失败", it) }
    }

    private fun fetchNameMap(urls: List<String>): Map<String, String> {
        for (u in urls) {
            val text = runCatching {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
            }.getOrNull() ?: continue
            val m = mutableMapOf<String, String>()
            try {
                val json = JSONObject(text)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val name = json.optJSONObject(k)?.optString("name", "") ?: ""
                    if (name.isNotBlank()) m[k] = name
                }
                if (m.isNotEmpty()) return m
            } catch (_: Exception) {
                LogHelper.w("EnkaNameMap", "远端名称字典解析失败，切换下一镜像")
            }
        }
        return emptyMap()
    }

    private fun fetchJson(url: String): JSONObject? {
        val text = fetchText(url) ?: return null
        return try { JSONObject(text) } catch (_: Exception) { null }
    }

    /** 多镜像依次拉取结构化 JSON，返回第一个解析成功且非空的结果。 */
    private fun fetchJsonList(urls: List<String>): JSONObject? {
        for (u in urls) {
            val parsed = fetchJson(u)
            if (parsed != null && parsed.length() > 0) return parsed
        }
        return null
    }

    private fun fetchText(url: String): String? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrNull()
    }

    /**
     * 解析 miao-plugin 上游 artis-mark.js 权重表为 JSONObject：
     * 角色名 → { hp, atk, def, speed, cpct, cdmg, stance, heal, recharge, effPct, effDef, dmg }。
     * 上游为 JS 模块字面量，端上去除注释后按正则抽取，不执行脚本。
     */
    private fun parseMiaoWeightJs(text: String): JSONObject? {
        return runCatching {
            var t = text.replace(Regex("/\\*.*?\\*/"), "")
            t = t.replace(Regex("//[^\n]*"), "")
            val out = JSONObject()
            val blockRe = Regex("""['"]?([^:'"{}\n,]+?)['"]?\s*:\s*\{([^}]*)\}""")
            val attrRe = Regex("""(\w+)\s*:\s*([0-9.]+)""")
            blockRe.findAll(t).forEach { m ->
                val name = m.groupValues[1].trim()
                if (name.isBlank() || name == "usefulAttr") return@forEach
                val attrs = JSONObject()
                attrRe.findAll(m.groupValues[2]).forEach { a ->
                    attrs.put(a.groupValues[1], a.groupValues[2].toDouble())
                }
                if (attrs.length() > 0) out.put(name, attrs)
            }
            if (out.length() > 0) out else null
        }.getOrNull()
    }

    fun charName(id: Int): String =
        characters[id.toString()] ?: NEW_CHAR_FALLBACK[id]?.first ?: "角色$id"
    fun lcName(id: Int): String = lightCones[id.toString()] ?: "光锥$id"

    /** 全部角色中文名（用于培养目标等本地选择器）。 */
    fun allCharNames(): List<String> =
        characters.values.filter { it.isNotBlank() }.distinct().sorted()

    /** 取指定角色的遗器评分权重表（miao 口径），无记录返回 null。 */
    fun relicWeightFor(name: String): JSONObject? = relicWeights?.optJSONObject(name)

    /** 按中文名反查角色ID，找不到返回0（用于抽卡记录加载头像）。名字保留原样，不去除星号等符号。 */
    fun charIdForName(name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val t = name.trim()
        var id = charIdByName[t] ?: 0
        // 兜底：去掉后缀/括号再试（如"旅情（星穹）"类带后缀的）
        if (id == 0) {
            val key2 = t.substringBefore("（").substringBefore("(").trim()
            id = charIdByName[key2] ?: 0
        }
        return id
    }

    /** 按中文名反查光锥ID，找不到返回0 */
    fun lcIdForName(name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val t = name.trim()
        var id = lcIdByName[t] ?: 0
        if (id == 0) {
            val key2 = t.substringBefore("（").substringBefore("(").trim()
            id = lcIdByName[key2] ?: 0
        }
        return id
    }

    fun relicSetName(id: Int): String = relicSets[id.toString()] ?: ""
    fun relicName(id: Int): String = relics[id.toString()] ?: "遗器$id"

    /** 按单件遗器 id 取静态元数据（set_id / name / icon / rarity / type 等）。 */
    fun relicMeta(id: Int): JSONObject? = relicMeta[id.toString()]

    /**
     * 官方主词条只有 property_type、缺 value 时，按单件遗器主词条分组查表重建：
     * value = base + step × level；百分比词条再除以 100。
     */
    fun mainAffixValue(tid: Int, field: String, level: Int): Double {
        val meta = relicMeta[tid.toString()] ?: return 0.0
        val group = meta.optString("main_affix_id", "").ifBlank { return 0.0 }
        val affixes = mainAffixes?.optJSONObject(group)?.optJSONObject("affixes") ?: return 0.0
        val keys = affixes.keys()
        while (keys.hasNext()) {
            val entry = affixes.optJSONObject(keys.next()) ?: continue
            val prop = entry.optString("property", "")
            val info = buildProp(prop, 0.0)
            if (info.optString("field", "") != field) continue
            val v = entry.optDouble("base", 0.0) + entry.optDouble("step", 0.0) * level
            return if (info.optBoolean("percent", false)) v / 100.0 else v
        }
        return 0.0
    }

    fun relicTypeName(type: Int): String = when (type) {
        1 -> "头部"; 2 -> "手部"; 3 -> "躯干"; 4 -> "脚部"; 5 -> "位面球"; 6 -> "连结绳"; else -> "部位$type"
    }

    /**
     * 角色裸白值（不含光锥）：values[promotion].base + step × (level - 1)
     * values[promotion].base 是该突破阶数下1级时的属性，step是每级增量
     */
    fun charBase(id: Int, promotion: Int, level: Int): DoubleArray? {
        val char = promotions?.optJSONObject(id.toString())
        if (char == null) return NEW_CHAR_BASE[id]
        val values = char.optJSONArray("values") ?: return NEW_CHAR_BASE[id]
        val idx = promotion.coerceIn(0, values.length() - 1)
        val v = values.optJSONObject(idx) ?: return NEW_CHAR_BASE[id]
        val lvDiff = (level - 1).coerceAtLeast(0)
        fun calc(key: String): Double {
            val obj = v.optJSONObject(key) ?: return 0.0
            val base = obj.optDouble("base", 0.0)
            val step = obj.optDouble("step", 0.0)
            return base + step * lvDiff
        }
        return doubleArrayOf(calc("hp"), calc("atk"), calc("def"), calc("spd"))
    }

    /** 行迹节点常驻属性：返回 [{type, value}]，已点亮的节点才返回 */
    fun skillTreeProps(pointId: Int, level: Int): JSONArray? {
        val node = skillTrees?.optJSONObject(pointId.toString()) ?: return null
        val levels = node.optJSONArray("levels") ?: return null
        val result = JSONArray()
        for (i in 0..level.coerceIn(0, levels.length() - 1)) {
            val lv = levels.optJSONObject(i) ?: continue
            val props = lv.optJSONArray("properties") ?: continue
            for (j in 0 until props.length()) {
                result.put(props.optJSONObject(j))
            }
        }
        return if (result.length() > 0) result else null
    }

    /** 公开包装：把 (Mihomo 属性名, 数值) 转成面板属性对象。 */
    fun propInfo(property: String, value: Double): JSONObject = buildProp(property, value)

    /**
     * 光锥技能描述：解析 #N[i] / #N[f1] / #N[f2] 完整占位符。
     * 带尾随 % 时按百分比格式化（小数 ×100），否则按整数/小数格式输出。
     * 对齐 miao-plugin Weapon.getAffixDesc 的正则处理，避免残留 [i] 与 0.18 被显示成 0.2。
     */
    fun lcDesc(lcId: Int, superimpose: Int): String {
        val lc = lightConeRanks?.optJSONObject(lcId.toString()) ?: return ""
        val desc = lc.optString("desc", "")
        if (desc.isBlank()) return ""
        val params = lc.optJSONArray("params") ?: return desc
        val idx = (superimpose - 1).coerceIn(0, params.length() - 1)
        val row = params.optJSONArray(idx) ?: return desc
        var out = desc
        val regex = Regex("#(\\d+)\\[(i|f1|f2)\\](%?)")
        out = regex.replace(out) { mr ->
            val i = mr.groupValues[1].toIntOrNull() ?: return@replace mr.value
            val v = row.optDouble(i - 1, 0.0)
            val fmt = mr.groupValues[2]
            val pct = mr.groupValues[3] == "%"
            when {
                pct -> {
                    val decimals = if (fmt == "f2") 2 else 1
                    String.format(Locale.US, "%.${decimals}f%%", v * 100)
                }
                fmt == "i" -> fmtParam(v)
                fmt == "f1" -> String.format(Locale.US, "%.1f", v)
                fmt == "f2" -> String.format(Locale.US, "%.2f", v)
                else -> v.toString()
            }
        }
        return out
    }

    /** 遗器套装 2/4 件效果描述。 */
    fun relicSetDesc(setId: Int, count: Int): String {
        val set = relicSetsData?.optJSONObject(setId.toString()) ?: return ""
        val desc = set.optJSONArray("desc") ?: return ""
        val idx = if (count >= 4) 1 else 0
        return desc.optString(idx, "")
    }

    private fun fmtParam(v: Double): String =
        if (kotlin.math.abs(v - v.toLong()) < 1e-9) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", v)

    /** 角色裸白值数组（不含光锥）。 */
    fun charBaseAttrs(id: Int, promotion: Int, level: Int): JSONArray? {
        val base = charBase(id, promotion, level) ?: return null
        return baseAttrsArray(base, null)
    }

    /** 光锥基础白值：values[promotion].base + step×(level-1)；无记录返回 null。 */
    fun lcBase(lcId: Int, promotion: Int, level: Int): DoubleArray? {
        val lc = lightConePromotions?.optJSONObject(lcId.toString()) ?: return null
        val values = lc.optJSONArray("values") ?: return null
        val idx = promotion.coerceIn(0, values.length() - 1)
        val v = values.optJSONObject(idx) ?: return null
        val lvDiff = (level - 1).coerceAtLeast(0)
        fun calc(key: String): Double {
            val obj = v.optJSONObject(key) ?: return 0.0
            return obj.optDouble("base", 0.0) + obj.optDouble("step", 0.0) * lvDiff
        }
        return doubleArrayOf(calc("hp"), calc("atk"), calc("def"), 0.0)
    }

    /**
     * 面板展示/评分用白值数组 = 角色裸白值 + 光锥基础白值（与成品面板白值列口径一致）。
     * eq 为空时只出角色裸白值。
     */
    fun combinedBaseAttrs(id: Int, promotion: Int, level: Int, eq: JSONObject?): JSONArray? {
        val char = charBase(id, promotion, level) ?: return null
        val lcId = eq?.optInt("tid", 0)?.takeIf { it > 0 } ?: eq?.optInt("id", 0) ?: 0
        val lc = if (eq != null && lcId > 0) {
            lcBase(lcId, eq.optInt("promotion", 6), eq.optInt("level", level))
        } else null
        return baseAttrsArray(char, lc)
    }

    private fun baseAttrsArray(char: DoubleArray, lc: DoubleArray?): JSONArray {
        val out = JSONArray()
        val fields = listOf("hp", "atk", "def", "spd")
        val names = listOf("生命值", "攻击力", "防御力", "速度")
        for (i in fields.indices) {
            val v = char[i] + (lc?.getOrNull(i) ?: 0.0)
            out.put(JSONObject()
                .put("field", fields[i])
                .put("name", names[i])
                .put("value", v))
        }
        return out
    }

    /**
     * 由 raw 行迹节点列表构造技能详情（skillsDetail）。
     * 主技能节点 pointId 尾号 1..4；名称/图标/类型取自行迹表，缺表时按类型兜底。
     */
    /** 主技能后缀：01 普攻 / 02 战技 / 03 终结技 / 04 天赋 / 07 秘技；其余为奖励关/行迹/欢愉技，不进技能行。 */
    private val MAIN_SKILL_SUFFIX = setOf(1, 2, 3, 4, 7)

    fun buildSkillsDetail(skillTree: JSONArray?, aid: Int): JSONArray {
        val out = JSONArray()
        if (skillTree == null) return out
        for (i in 0 until skillTree.length()) {
            val node = skillTree.optJSONObject(i) ?: continue
            val pid = listOf("pointId", "point_id", "id", "skill_id")
                .firstNotNullOfOrNull { key -> node.optInt(key, 0).takeIf { it > 0 } }
                ?: continue
            val lv = node.optInt("level", 1)
            val suffix = pid % 100
            if (suffix !in MAIN_SKILL_SUFFIX) continue
            val tree = skillTrees?.optJSONObject(pid.toString())
            // 字典未收录的新角色：anchor / 类型按行迹后缀兜底推断
            val rawAnchor = tree?.optString("anchor", "") ?: ""
            val anchor = rawAnchor.ifBlank {
                when (suffix) {
                    1 -> "Point01"; 2 -> "Point02"; 3 -> "Point03"; 4 -> "Point04"; 7 -> "Point05"; else -> ""
                }
            }
            val typeText = when (anchor) {
                "Point01" -> "普攻"; "Point02" -> "战技"; "Point03" -> "终结技"; "Point04" -> "天赋"; "Point05" -> "秘技"; else -> ""
            }
            val englishType = when (suffix) {
                1 -> "Normal"; 2 -> "BPSkill"; 3 -> "Ultra"; 4 -> "Talent"; 7 -> "Maze"; else -> ""
            }
            // 图标同样按 StarRailRes 命名规律兜底：icon/skill/{aid}_{basic_atk|skill|ultimate|talent|technique}.png
            val slug = when (suffix) {
                1 -> "basic_atk"; 2 -> "skill"; 3 -> "ultimate"; 4 -> "talent"; 7 -> "technique"; else -> ""
            }
            val officialName = node.optString("name", "")
            val officialIcon = node.optString("icon", "")
            val icon = (tree?.optString("icon", "") ?: "").ifBlank {
                officialIcon.ifBlank { if (slug.isNotBlank()) "icon/skill/${aid}_$slug.png" else "" }
            }
            val sk = JSONObject()
            sk.put("id", pid)
            sk.put("name", tree?.optString("name", "")?.ifBlank {
                officialName.ifBlank { typeText.ifBlank { "技能" } }
            } ?: officialName.ifBlank { "技能" })
            sk.put("level", lv)
            sk.put("maxLevel", tree?.optInt("max_level", 10) ?: node.optInt("max_level", 10))
            sk.put("type", englishType)
            sk.put("typeText", typeText)
            sk.put("icon", icon)
            out.put(sk)
        }
        return out
    }

    /** 光锥常驻被动属性：返回 [{type, value}]，按叠影阶数读取 */
    fun lightConeProps(lcId: Int, superimpose: Int): JSONArray? {
        val lc = lightConeRanks?.optJSONObject(lcId.toString()) ?: return null
        val props = lc.optJSONArray("properties") ?: return null
        val idx = (superimpose - 1).coerceIn(0, props.length() - 1)
        return props.optJSONArray(idx)
    }

    /** 遗器套装常驻属性：按套装件数返回对应效果属性 */
    fun relicSetProps(setId: Int, count: Int): JSONArray? {
        val set = relicSetsData?.optJSONObject(setId.toString()) ?: return null
        val props = set.optJSONArray("properties") ?: return null
        val result = JSONArray()
        if (count >= 2) {
            val p2: JSONArray? = props.optJSONArray(0)
            if (p2 != null) for (i in 0 until p2.length()) result.put(p2.optJSONObject(i))
        }
        if (count >= 4) {
            val p4: JSONArray? = props.optJSONArray(1)
            if (p4 != null) for (i in 0 until p4.length()) result.put(p4.optJSONObject(i))
        }
        return if (result.length() > 0) result else null
    }

    // ---------------- raw 兜底角色：遗器词条翻译 ----------------

    /** 按遗器部位兜底主词条分组编号：1头 2手 3躯干 4脚 5球 6绳。 */
    private fun mainGroupByType(type: Int): String = when (type) {
        1 -> "51"; 2 -> "52"; 3 -> "53"; 4 -> "54"; 5 -> "55"; 6 -> "56"; else -> "51"
    }

    /**
     * 把 raw 接口的原始遗器条目翻译成面板内部结构。
     * raw: {tid,type,level,mainAffixId,subAffixList:[{cnt,step,affixId}]}
     * 主词条 value = base + step×level；副词条 value = base×cnt + step×step字段。
     */
    fun translateRawRelic(raw: JSONObject): JSONObject? {
        val tid = raw.optInt("tid", 0)
        if (tid == 0) return null
        val type = raw.optInt("type", 0)
        val level = raw.optInt("level", 0)
        val meta = relicMeta[tid.toString()]
        var setId = meta?.optString("set_id", "0") ?: "0"
        val mainGroup = meta?.optString("main_affix_id", "")?.ifBlank { mainGroupByType(type) } ?: mainGroupByType(type)
        val subGroup = meta?.optString("sub_affix_id", "")?.ifBlank { "5" } ?: "5"
        var icon = meta?.optString("icon", "") ?: ""
        val rarity = meta?.optInt("rarity", 5) ?: 5

        // 4.7 之后新增饰品未进本地/在线字典：按 tid 规律反推套装编号（如 63295/63296 → 套装 329），
        // 图标走 StarRailRes 网络兜底，避免两件新饰品只剩空白占位方块。
        if (setId == "0" && type in 5..6 && tid >= 63000) {
            val inferredSet = tid / 10 - 6000
            if (inferredSet in 301..399) {
                setId = inferredSet.toString()
                val suffix = if (type == 5) "_0" else "_1"
                icon = "icon/relic/$setId$suffix.png"
            }
        }

        val props = JSONArray()
        // 主词条
        val mainEntry = mainAffixes
            ?.optJSONObject(mainGroup)?.optJSONObject("affixes")
            ?.optJSONObject(raw.optInt("mainAffixId", 0).toString())
        if (mainEntry != null) {
            props.put(buildProp(mainEntry.optString("property", ""),
                mainEntry.optDouble("base", 0.0) + mainEntry.optDouble("step", 0.0) * level))
        }
        // 副词条
        val subOut = JSONArray()
        val subList = raw.optJSONArray("subAffixList")
        if (subList != null) {
            for (j in 0 until subList.length()) {
                val s = subList.optJSONObject(j) ?: continue
                val cnt = s.optInt("cnt", 0)
                val stepLv = s.optInt("step", 0)
                val affixId = s.optInt("affixId", 0)
                subOut.put(JSONObject().put("cnt", cnt))
                val entry = subAffixes
                    ?.optJSONObject(subGroup)?.optJSONObject("affixes")
                    ?.optJSONObject(affixId.toString())
                if (entry != null) {
                    val base = entry.optDouble("base", 0.0)
                    val step = entry.optDouble("step", 0.0)
                    props.put(buildProp(entry.optString("property", ""), base * cnt + step * stepLv))
                }
            }
        }

        val relic = JSONObject()
        relic.put("tid", tid)
        relic.put("type", type)
        relic.put("level", level)
        relic.put("rarity", rarity)
        relic.put("setName", relicSets[setId] ?: "")
        relic.put("icon", icon)
        val flat = JSONObject()
        flat.put("props", props)
        flat.put("setID", setId.toIntOrNull() ?: 0)
        relic.put("_flat", flat)
        relic.put("subAffixList", subOut)
        return relic
    }

    /**
     * 汇总遗器主/副词条作为「绿色增量」。固定值与百分比按 field#percent 分开累计，
     * 避免 HP 固定值与 HP 百分比、DEF 固定值与 DEF 百分比混到同一字段里。
     */
    fun buildStatsFromRelics(relicList: JSONArray?): JSONArray {
        val acc = linkedMapOf<String, JSONObject>()
        if (relicList != null) {
            for (i in 0 until relicList.length()) {
                val r = relicList.optJSONObject(i) ?: continue
                val props = r.optJSONObject("_flat")?.optJSONArray("props") ?: continue
                for (j in 0 until props.length()) {
                    val p = props.optJSONObject(j) ?: continue
                    val field = p.optString("field", "")
                    if (field.isBlank()) continue
                    val percent = p.optBoolean("percent", false)
                    val key = "$field#$percent"
                    val cur = acc[key]
                    if (cur == null) {
                        acc[key] = JSONObject().put("field", field)
                            .put("name", p.optString("name", ""))
                            .put("percent", percent)
                            .put("value", p.optDouble("value", 0.0))
                    } else {
                        cur.put("value", cur.optDouble("value", 0.0) + p.optDouble("value", 0.0))
                    }
                }
            }
        }
        val out = JSONArray()
        for (entry in acc.values) {
            val percent = entry.optBoolean("percent", false)
            val value = entry.optDouble("value", 0.0)
            entry.put("display", formatPropDisplay(entry.optString("field", ""), value, percent))
            out.put(entry)
        }
        return out
    }

    /** 骨架角色绿色增量：固定值词条取固定增量，百分比战斗词条取百分比增量，每字段只出一条。 */
    fun buildSkeletonAdditions(relicBonus: JSONArray?): JSONArray {
        val flat = mutableMapOf<String, Double>()
        val ratio = mutableMapOf<String, Double>()
        val meta = mutableMapOf<String, JSONObject>()
        if (relicBonus != null) {
            for (i in 0 until relicBonus.length()) {
                val b = relicBonus.optJSONObject(i) ?: continue
                val field = b.optString("field", "")
                if (field.isBlank()) continue
                val v = b.optDouble("value", 0.0)
                meta[field] = b
                if (b.optBoolean("percent", false)) ratio[field] = (ratio[field] ?: 0.0) + v
                else flat[field] = (flat[field] ?: 0.0) + v
            }
        }
        val flatFields = listOf("hp", "atk", "def", "spd")
        val out = JSONArray()
        for ((field, m) in meta) {
            val isFlat = field in flatFields
            val v = if (isFlat) flat[field] ?: 0.0 else ratio[field] ?: 0.0
            if (v <= 0.0001) continue
            val percent = !isFlat
            out.put(JSONObject()
                .put("field", field)
                .put("name", m.optString("name", ""))
                .put("percent", percent)
                .put("value", v)
                .put("display", formatPropDisplay(field, v, percent)))
        }
        return out
    }

    /**
     * 骨架角色（raw 兜底）最终面板：基础白值 + 遗器增量。
     * 固定值词条：最终 = 白值×(1+百分比增量) + 固定增量；百分比战斗词条：最终 = 默认基础 + 百分比增量。
     */
    fun buildSkeletonStats(baseAttrs: JSONArray?, relicBonus: JSONArray?): JSONArray {
        val baseMap = mutableMapOf<String, Double>()
        if (baseAttrs != null) {
            for (i in 0 until baseAttrs.length()) {
                val a = baseAttrs.optJSONObject(i) ?: continue
                baseMap[a.optString("field", "")] = a.optDouble("value", 0.0)
            }
        }
        val flat = mutableMapOf<String, Double>()
        val ratio = mutableMapOf<String, Double>()
        val meta = mutableMapOf<String, JSONObject>()
        if (relicBonus != null) {
            for (i in 0 until relicBonus.length()) {
                val b = relicBonus.optJSONObject(i) ?: continue
                val field = b.optString("field", "")
                if (field.isBlank()) continue
                val v = b.optDouble("value", 0.0)
                meta[field] = b
                if (b.optBoolean("percent", false)) ratio[field] = (ratio[field] ?: 0.0) + v
                else flat[field] = (flat[field] ?: 0.0) + v
            }
        }
        val flatFields = listOf("hp", "atk", "def", "spd")
        val defaultBase = mapOf("crit_rate" to 0.05, "crit_dmg" to 0.5, "energy_regen" to 1.0)
        val order = listOf("hp", "atk", "def", "spd", "crit_rate", "crit_dmg",
            "break_dmg", "effect_hit", "effect_res", "heal_rate", "energy_regen", "sp_rate",
            "physical_dmg", "fire_dmg", "ice_dmg", "thunder_dmg", "wind_dmg", "quantum_dmg", "imaginary_dmg")
        val seen = linkedSetOf<String>()
        val out = JSONArray()
        fun emit(field: String) {
            if (field in seen) return
            seen.add(field)
            val isFlat = field in flatFields
            val base = baseMap[field] ?: (defaultBase[field] ?: 0.0)
            val finalVal = if (isFlat) {
                base * (1.0 + (ratio[field] ?: 0.0)) + (flat[field] ?: 0.0)
            } else {
                base + (ratio[field] ?: 0.0)
            }
            val m = meta[field]
            val percent = if (isFlat) false else (m?.optBoolean("percent", true) ?: true)
            val entry = JSONObject()
                .put("field", field)
                .put("name", m?.optString("name", "") ?: "")
                .put("percent", percent)
                .put("value", finalVal)
                .put("display", formatPropDisplay(field, finalVal, percent))
            out.put(entry)
        }
        for (f in order) if (baseMap.containsKey(f) || flat.containsKey(f) || ratio.containsKey(f)) emit(f)
        for (f in meta.keys) emit(f)
        return out
    }

    private fun buildProp(property: String, value: Double): JSONObject {
        val metaP = properties?.optJSONObject(property)
        var field = metaP?.optString("field", "")?.ifBlank { propField(property) } ?: propField(property)
        // 远端字典把欢愉度记作 elation_dmg，统一成内部字段 elation
        if (field == "elation_dmg") field = "elation"
        val percent = metaP?.optBoolean("percent", propIsPercent(property)) ?: propIsPercent(property)
        val name = metaP?.optString("name", "")?.ifBlank { propName(property) } ?: propName(property)
        val icon = metaP?.optString("icon", "") ?: ""
        val o = JSONObject()
        o.put("type", property)
        o.put("field", field)
        o.put("name", name)
        o.put("icon", icon)
        o.put("percent", percent)
        o.put("value", value)
        o.put("display", formatPropDisplay(field, value, percent))
        return o
    }

    private fun propField(property: String): String = when (property) {
        "HPDelta", "HPAddedRatio" -> "hp"
        "AttackDelta", "AttackAddedRatio" -> "atk"
        "DefenceDelta", "DefenceAddedRatio" -> "def"
        "SpeedDelta" -> "spd"
        "CriticalChanceBase", "CriticalChance" -> "crit_rate"
        "CriticalDamageBase", "CriticalDamage" -> "crit_dmg"
        "StatusProbabilityBase", "StatusProbability" -> "effect_hit"
        "StatusResistanceBase", "StatusResistance" -> "effect_res"
        "BreakDamageAddedRatioBase", "BreakDamageAddedRatio" -> "break_dmg"
        "HealRatioBase" -> "heal_rate"
        "SPRatioBase" -> "energy_regen"
        "PhysicalAddedRatio" -> "physical_dmg"
        "FireAddedRatio" -> "fire_dmg"
        "IceAddedRatio" -> "ice_dmg"
        "ThunderAddedRatio" -> "thunder_dmg"
        "WindAddedRatio" -> "wind_dmg"
        "QuantumAddedRatio" -> "quantum_dmg"
        "ImaginaryAddedRatio" -> "imaginary_dmg"
        "ElationDamageAddedRatio", "ElationDamageAddedRatioBase" -> "elation"
        else -> ""
    }

    private fun propIsPercent(property: String): Boolean = when {
        property.endsWith("Delta") -> false
        property == "Speed" || property == "SpeedDelta" -> false
        else -> property.endsWith("Ratio") || property.endsWith("Base")
    }

    private fun propName(property: String): String = when (property) {
        "HPDelta", "HPAddedRatio" -> "生命值"
        "AttackDelta", "AttackAddedRatio" -> "攻击力"
        "DefenceDelta", "DefenceAddedRatio" -> "防御力"
        "SpeedDelta" -> "速度"
        "CriticalChanceBase" -> "暴击率"
        "CriticalDamageBase" -> "暴击伤害"
        "StatusProbabilityBase" -> "效果命中"
        "StatusResistanceBase" -> "效果抵抗"
        "BreakDamageAddedRatioBase" -> "击破特攻"
        "HealRatioBase" -> "治疗加成"
        "SPRatioBase" -> "能量恢复效率"
        "PhysicalAddedRatio" -> "物理伤害加成"
        "FireAddedRatio" -> "火属性伤害加成"
        "IceAddedRatio" -> "冰属性伤害加成"
        "ThunderAddedRatio" -> "雷属性伤害加成"
        "WindAddedRatio" -> "风属性伤害加成"
        "QuantumAddedRatio" -> "量子属性伤害加成"
        "ImaginaryAddedRatio" -> "虚数属性伤害加成"
        "ElationDamageAddedRatio", "ElationDamageAddedRatioBase" -> "欢愉度"
        else -> property
    }

    private fun formatPropDisplay(field: String, value: Double, percent: Boolean): String {
        if (percent) {
            val pct = java.math.BigDecimal.valueOf(value * 100)
                .setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
            return "$pct%"
        }
        return value.toInt().toString()
    }

    /** 命途/元素英文 → 中文（骨架角色标签兜底）。 */
    fun pathNameOf(path: String): String = when (path) {
        "Knight" -> "存护"; "Rogue" -> "巡猎"; "Mage" -> "智识"; "Shaman" -> "同谐"
        "Warrior" -> "毁灭"; "Priest" -> "丰饶"; "Elation" -> "欢愉"; else -> path
    }

    fun elementNameOf(element: String): String = when (element) {
        "Physical" -> "物理"; "Fire" -> "火"; "Ice" -> "冰"; "Thunder" -> "雷"
        "Wind" -> "风"; "Quantum" -> "量子"; "Imaginary" -> "虚数"; else -> element
    }
}
