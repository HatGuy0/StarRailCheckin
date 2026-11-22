package com.mh.starrailcheckin.ui.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

private typealias JsMap = MutableMap<String, Any?>

/** 全角色 miao 伤害规则引擎：执行 calc.js AST，并还原 DmgAttr/DmgCalc 的星铁分支。 */
internal object MiaoRulesEngine {

    data class Row(
        val title: String,
        val crit: Double? = null,
        val avg: Double? = null,
        val text: String? = null
    )

    @Volatile
    private var rules: JSONObject? = null

    @Volatile
    private var equipment: JSONObject? = null

    @Volatile
    private var artifact: JSONObject? = null

    suspend fun ensureLoaded(ctx: Context): Boolean {
        if (rules != null && equipment != null && artifact != null) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                val rulesText = ctx.assets.open("miao_rules.json").bufferedReader().use { it.readText() }
                val equipmentText = ctx.assets.open("miao_equipment.json").bufferedReader().use { it.readText() }
                val artifactText = ctx.assets.open("miao_artifact.json").bufferedReader().use { it.readText() }
                val parsedRules = JSONObject(rulesText)
                val parsedEquipment = JSONObject(equipmentText)
                val parsedArtifact = JSONObject(artifactText)
                rules = parsedRules
                equipment = parsedEquipment
                artifact = parsedArtifact
                true
            }.getOrDefault(false)
        }
    }

    fun equipmentData(): JSONObject? = equipment

    fun artifactData(): JSONObject? = artifact

    fun resolveCharName(d: DetailUi): String? {
        val characters = rules?.optJSONObject("characters") ?: return null
        val name = d.name.trim()
        if (characters.has(name) || isMissing(name)) return name
        val withoutSuffix = name.substringBefore("（").substringBefore("(")
        if (characters.has(withoutSuffix) || isMissing(withoutSuffix)) return withoutSuffix
        // 上游名称可能与游戏内名称不完全一致（如「银狼 LV.999」对「银狼LV.999」），
        // 角色 ID 在规则中唯一，按 ID 兜底最可靠。
        val iterator = characters.keys()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            val id = characters.optJSONObject(candidate)?.opt("id")?.toString()
            if (id == d.aid.toString()) return candidate
        }
        return null
    }

    private fun isMissing(charName: String): Boolean {
        val missing = rules?.optJSONArray("missing") ?: return false
        return (0 until missing.length()).any { missing.optString(it) == charName }
    }

    fun hasRules(charName: String): Boolean =
        rules?.optJSONObject("characters")?.has(charName) == true

    fun buildRows(d: DetailUi, charName: String): List<Row>? {
        val program = rules?.optJSONObject("characters")?.optJSONObject(charName)
            ?.optJSONObject("program") ?: return null
        val data = MiaoDataClient.data(charName) ?: return null
        val env = MiaoJsEvaluator.execute(program)
        val details = env.get("details") as? List<*> ?: return null
        val buffs = (env.get("buffs") as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()
        val sortedBuffs = (buffs + equipmentBuffs(d) + presetAutoBuffs(d)).mapIndexed { index, buff -> index to buff }
            .sortedWith(compareBy({ MiaoJsEvaluator.number(it.second["sort"] ?: 1) }, { it.first }))
            .map { it.second }

        val talent = buildTalent(d, data)
        val trees = d.treeIds.associateWith { true }
        val weapon = linkedMapOf<String, Any?>(
            "name" to (d.lc?.name ?: ""),
            "affix" to (d.lc?.superimpose ?: 1),
            "refine" to (d.lc?.superimpose ?: 1)
        )
        val meta = linkedMapOf<String, Any?>(
            "characterName" to charName,
            "charId" to d.aid,
            "uid" to d.uid,
            "level" to d.level,
            "enemyLv" to d.enemyLv,
            "cons" to d.rank,
            "talent" to talent,
            "trees" to trees,
            "weapon" to weapon
        )
        val defaultParamsValue = maybeCall(env.get("defParams"), listOf(meta))
        val defaultParams = toStringMap(defaultParamsValue)
        val rows = mutableListOf<Row>()

        details.forEachIndexed { detailIndex, rawDetail ->
            val baseAttr = initialAttr(d, data)
            val baseDs = makeDs(baseAttr, defaultParams, meta, data, d)
            val detail = if (MiaoJsEvaluator.isCallable(rawDetail)) {
                toStringMap(MiaoJsEvaluator.call(rawDetail, listOf(baseDs)))
            } else {
                toStringMap(rawDetail)
            }
            if (MiaoJsEvaluator.truthy(detail["isStatic"])) return@forEachIndexed
            val requiredCons = MiaoJsEvaluator.number(detail["cons"]).toInt()
            if (requiredCons > 0 && d.rank < requiredCons) return@forEachIndexed

            val ownParamsValue = maybeCall(detail["params"], listOf(meta))
            val params = mergeParams(defaultParams, toStringMap(ownParamsValue))
            val attr = initialAttr(d, data)
            applyBuffs(attr, sortedBuffs, params, meta, data, d, detail["talent"]?.toString().orEmpty())
            val ds = makeDs(attr, params, meta, data, d)
            ds["artis"] = (d.panel?.sets ?: d.sets).map { linkedMapOf<String, Any?>("name" to it.name, "count" to it.count) }
            val check = detail["check"]
            if (MiaoJsEvaluator.isCallable(check) && !MiaoJsEvaluator.truthy(MiaoJsEvaluator.call(check, listOf(ds)))) {
                return@forEachIndexed
            }
            val damage = detail["dmg"] ?: return@forEachIndexed
            val dispatcher = damageDispatcher(attr, d.level, d.enemyLv)
            val result = toStringMap(MiaoJsEvaluator.call(damage, listOf(ds, dispatcher)))
            val titleValue = maybeCall(detail["title"], listOf(ds))
            val title = MiaoJsEvaluator.string(titleValue)
            val text = if (result["type"] == "text") MiaoJsEvaluator.string(result["avg"]) else null
            rows += Row(
                title = title,
                crit = finiteNumber(result["dmg"]),
                avg = finiteNumber(result["avg"]),
                text = text
            )
        }
        return rows
    }

    private fun maybeCall(value: Any?, args: List<Any?>): Any? =
        if (MiaoJsEvaluator.isCallable(value)) MiaoJsEvaluator.call(value, args) else value

    private fun toStringMap(value: Any?): JsMap = when (value) {
        is Map<*, *> -> {
            val result = linkedMapOf<String, Any?>()
            value.forEach { (key, item) -> result[MiaoJsEvaluator.string(key)] = item }
            result
        }
        else -> linkedMapOf()
    }

    private fun mergeParams(base: JsMap, override: JsMap): JsMap {
        val result = deepCopy(base)
        override.forEach { (key, value) ->
            val old = result[key]
            result[key] = if (old is Map<*, *> && value is Map<*, *>) {
                mergeParams(toStringMap(old), toStringMap(value))
            } else value
        }
        return result
    }

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val result = linkedMapOf<String, Any?>()
            value.forEach { (key, item) -> result[MiaoJsEvaluator.string(key)] = deepCopy(item) }
            result
        }
        is List<*> -> value.map(::deepCopy)
        else -> value
    }

    // 泛型擦除下 as? 只校验容器类型，键值类型无法运行时校验，故抑制 unchecked 警告。
    @Suppress("UNCHECKED_CAST")
    private fun deepCopy(map: JsMap): JsMap =
        (deepCopy(map as Any?) as? JsMap) ?: linkedMapOf()

    private fun buildTalent(d: DetailUi, data: JSONObject): JsMap {
        val result = linkedMapOf<String, Any?>()
        val talentData = data.optJSONObject("talent") ?: return result
        val levels = d.talentLevels.toMutableMap()
        val talentIds = data.optJSONObject("talentId")
        if (talentIds != null) {
            val ids = talentIds.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val key = talentIds.optString(id)
                if (key == "z") continue
                val level = id.toIntOrNull()?.let { d.skillLevels[it] }
                if (level != null && !levels.containsKey(key)) levels[key] = level
            }
        }
        // 部分角色的 talentId 是哈希技能 id，官方面板只给 pointId。
        // 对齐 miao Character.getTalentKey：去掉角色 id 前缀后用后缀兜底。
        val charId = data.optInt("id", 0)
        if (charId > 0) {
            val prefix = Regex("^1?$charId")
            val suffixKeys = mapOf(
                "001" to "a", "002" to "e", "003" to "q", "004" to "t",
                "007" to "z", "301" to "me", "302" to "mt", "420" to "xe"
            )
            for ((pid, level) in d.skillLevels) {
                val suffix = pid.toString().replace(prefix, "")
                val key = suffixKeys[suffix] ?: continue
                if (key == "z") continue
                if (!levels.containsKey(key)) levels[key] = level
            }
        }
        val uiLevels = mapOf(
            "a" to "普攻", "e" to "战技", "q" to "终结技", "t" to "天赋",
            "xe" to "欢愉技", "me" to "忆灵技", "mt" to "忆灵天赋"
        )
        uiLevels.forEach { (key, type) ->
            if (!levels.containsKey(key)) {
                d.skills.firstOrNull { it.typeText == type }?.let { levels[key] = it.level }
            }
        }
        val keys = listOf("a", "a2", "e", "e1", "e2", "q", "q2", "q3", "t", "t2",
            "xe", "xe2", "me", "me2", "mt", "mt1", "mt2")
        val variant = Regex("^(a|e|q|t|xe|me|mt)(1|2|3)$")
        for (key in keys) {
            val base = variant.matchEntire(key)?.groupValues?.get(1) ?: key
            val level = levels[base] ?: 1
            val tables = talentData.optJSONObject(key)?.optJSONObject("tables") ?: continue
            val valuesByName = linkedMapOf<String, Any?>()
            val tableKeys = tables.keys()
            while (tableKeys.hasNext()) {
                val row = tables.optJSONObject(tableKeys.next()) ?: continue
                val values = row.optJSONArray("values") ?: continue
                val index = level - 1
                valuesByName[row.optString("name")] = if (index in 0 until values.length()) {
                    values.opt(index)
                } else MiaoJsEvaluator.Undefined
            }
            result[key] = valuesByName
        }
        result["talentLevel"] = levels
        return result
    }

    private fun initialAttr(d: DetailUi, data: JSONObject): JsMap {
        d.panel?.let { panel ->
            val attr = initialAttr(d.copy(panel = null), data)
            for (key in listOf("hp", "atk", "def", "speed")) {
                val base = panel.attr["${key}Base"] ?: 0.0
                attr[key] = attrItem(base = base, plus = (panel.attr[key] ?: 0.0) - base)
            }
            for (key in listOf("recharge", "cpct", "cdmg", "dmg", "heal", "stance", "effPct", "effDef", "joy")) {
                attr[key] = attrItem(base = panel.attr[key] ?: 0.0)
            }
            attr["staticAttr"] = panel.staticAttr.mapValuesTo(linkedMapOf()) { (_, value) ->
                attrItem(base = value.base, plus = value.plus, pct = value.pct, inc = value.inc)
            }
            return attr
        }
        val attr = linkedMapOf<String, Any?>()
        for (key in listOf("hp", "atk", "def")) {
            val row = d.stats.firstOrNull { it.field == key }
            val base = row?.baseValue ?: 0.0
            val total = row?.totalValue ?: 0.0
            attr[key] = attrItem(base = base, plus = total - base)
        }
        val speed = d.stats.firstOrNull { it.field == "spd" }
        val speedBase = speed?.baseValue ?: 0.0
        attr["speed"] = attrItem(base = speedBase, plus = (speed?.totalValue ?: 0.0) - speedBase)

        val percentFields = mapOf(
            "cpct" to "crit_rate", "cdmg" to "crit_dmg", "heal" to "heal_rate",
            "recharge" to "energy_regen", "effPct" to "effect_hit", "effDef" to "effect_res",
            "stance" to "break_dmg", "joy" to "elation"
        )
        percentFields.forEach { (key, field) ->
            attr[key] = attrItem(base = (d.stats.firstOrNull { it.field == field }?.totalValue ?: 0.0) * 100)
        }
        val elementDmg = (d.stats.firstOrNull { it.field == d.elementField }?.totalValue ?: 0.0) * 100
        attr["dmg"] = attrItem(base = elementDmg)
        attr["enemydmg"] = attrItem()
        attr["phy"] = attrItem()
        attr["coloringDmg"] = attrItem()

        for (key in listOf("a", "a2", "a3", "e", "e2", "xe", "xe2", "q", "q2", "q3",
            "t", "t2", "me", "me2", "mt", "mt2", "dot", "break", "elation")) {
            attr[key] = linkedMapOf<String, Any?>(
                "pct" to 0.0, "multi" to 0.0, "plus" to 0.0, "dmg" to 0.0,
                "enemydmg" to 0.0, "cpct" to 0.0, "cdmg" to 0.0, "elevated" to 0.0,
                "merrymakes" to 0.0, "def" to 0.0, "ignore" to 0.0
            )
        }
        attr["enemy"] = linkedMapOf<String, Any?>("def" to 0.0, "ignore" to 0.0, "phy" to 0.0)
        attr["shield"] = attrItem(base = 100.0, inc = 100.0)
        attr["superBreak"] = linkedMapOf<String, Any?>("ignore" to 0.0)
        attr["weapon"] = linkedMapOf<String, Any?>("name" to (d.lc?.name ?: ""), "affix" to (d.lc?.superimpose ?: 1))
        attr["element"] = d.element
        attr["refine"] = ((d.lc?.superimpose ?: 1) - 1).toDouble()
        attr["multi"] = 0.0
        attr["kx"] = 0.0
        attr["merrymakes"] = 0.0
        attr["punchline"] = 0.0
        attr["sp"] = data.optDouble("sp", 0.0)
        attr["staticAttr"] = linkedMapOf<String, Any?>(
            "dmg" to linkedMapOf<String, Any?>("plus" to elementDmg),
            "atk" to linkedMapOf<String, Any?>("pct" to 0.0),
            "hp" to linkedMapOf<String, Any?>("pct" to 0.0),
            "def" to linkedMapOf<String, Any?>("pct" to 0.0),
            "speed" to attrItem(base = speed?.totalValue ?: 0.0)
        )
        return attr
    }

    private fun attrItem(base: Double = 0.0, plus: Double = 0.0, pct: Double = 0.0, inc: Double = 0.0): JsMap =
        linkedMapOf("base" to base, "plus" to plus, "pct" to pct, "inc" to inc)

    private fun makeDs(
        attr: JsMap,
        params: JsMap,
        meta: JsMap,
        data: JSONObject,
        d: DetailUi
    ): JsMap {
        val ds = deepCopy(meta)
        // 函数与面板对象不能深拷贝，恢复引用。
        ds["talent"] = meta["talent"]
        ds["weapon"] = meta["weapon"]
        ds["attr"] = attr
        ds["params"] = params
        ds["refine"] = attr["refine"] ?: 0.0
        ds["element"] = data.optString("elem", d.element)
        ds["weaponTypeName"] = data.optString("weapon", d.path)
        ds["calc"] = MiaoJsEvaluator.NativeFunction { args -> calcValue(args.firstOrNull()) }
        return ds
    }

    private fun applyBuffs(
        attr: JsMap,
        buffs: List<Map<*, *>>,
        params: JsMap,
        meta: JsMap,
        data: JSONObject,
        d: DetailUi,
        currentTalent: String
    ) {
        for (buff in buffs) {
            val ds = makeDs(attr, params, meta, data, d)
            ds["currentTalent"] = currentTalent
            ds["artis"] = (d.panel?.sets ?: d.sets).map { linkedMapOf<String, Any?>("name" to it.name, "count" to it.count) }
            val check = buff["check"]
            if (MiaoJsEvaluator.isCallable(check) && !MiaoJsEvaluator.truthy(MiaoJsEvaluator.call(check, listOf(ds)))) continue
            val cons = MiaoJsEvaluator.number(buff["cons"]).toInt()
            if (cons > 0 && d.rank < cons) continue
            val maxConsValue = buff["maxCons"]
            if (maxConsValue != null && d.rank > MiaoJsEvaluator.number(maxConsValue).toInt()) continue
            val tree = buff["tree"]
            if (tree != null && !d.treeIds.contains("10${MiaoJsEvaluator.number(tree).toInt()}")) continue
            val changes = buff["data"] as? Map<*, *> ?: continue
            for ((rawKey, rawValue) in changes) {
                val key = rawKey.toString()
                val value = maybeCall(rawValue, listOf(ds)) ?: continue
                if (!MiaoJsEvaluator.truthy(value) && !(value is Number && value.toDouble() == 0.0)) continue
                applyBuffValue(attr, key, MiaoJsEvaluator.number(value))
            }
        }
    }

    private val talentBuffRegex = Regex(
        "^(a|a2|a3|e|e2|q|q2|q3|t|t2|me|xe|xe2|mt|dot|break|nightsoul)(Def|Ignore|Dmg|Enemydmg|Plus|Pct|Cpct|Cdmg|Multi|Elevated|Merrymakes)$"
    )
    private val attrBuffRegex = Regex(
        "^(mastery|cpct|cdmg|heal|recharge|dmg|enemydmg|phy|coloringDmg|shield|speed|stance|joy)(Plus|Pct|Inc)?$"
    )
    private val baseBuffRegex = Regex("^(hp|def|atk)(Base|Plus|Pct|Inc)?$")

    @Suppress("UNCHECKED_CAST")
    private fun applyBuffValue(attr: JsMap, key: String, value: Double) {
        // 上游 auto 规则里的拼写别名 ememydmg，按 enemydmg 处理。
        if (key == "ememydmg") {
            applyBuffValue(attr, "enemydmg", value)
            return
        }
        talentBuffRegex.matchEntire(key)?.let { match ->
            val talent = attr[match.groupValues[1]] as? JsMap ?: return
            val field = match.groupValues[2].lowercase()
            talent[field] = MiaoJsEvaluator.number(talent[field]) + value
            return
        }
        attrBuffRegex.matchEntire(key)?.let { match ->
            val item = attr[match.groupValues[1]] as? JsMap ?: return
            val field = match.groupValues[2].ifBlank { "Plus" }.lowercase()
            item[field] = MiaoJsEvaluator.number(item[field]) + value
            return
        }
        baseBuffRegex.matchEntire(key)?.let { match ->
            val item = attr[match.groupValues[1]] as? JsMap ?: return
            val suffix = match.groupValues[2]
            val field = suffix.ifBlank { "Plus" }.lowercase()
            item[field] = MiaoJsEvaluator.number(item[field]) + value
            if (suffix == "Base") {
                val static = (attr["staticAttr"] as? JsMap)?.get(match.groupValues[1]) as? JsMap
                val pct = MiaoJsEvaluator.number(static?.get("pct"))
                item["plus"] = MiaoJsEvaluator.number(item["plus"]) + value * pct / 100
            }
            return
        }
        when (key) {
            "enemyDef" -> addDirect(attr, "enemy", "def", value)
            "ignore", "enemyIgnore" -> addDirect(attr, "enemy", "ignore", value)
            "kx", "multi", "merrymakes", "punchline" -> {
                attr[key] = MiaoJsEvaluator.number(attr[key]) + value
            }
            "superBreakIgnore" -> addDirect(attr, "superBreak", "ignore", value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun addDirect(attr: JsMap, objectKey: String, field: String, value: Double) {
        val target = attr[objectKey] as? JsMap ?: return
        target[field] = MiaoJsEvaluator.number(target[field]) + value
    }

    private fun equipmentBuffs(d: DetailUi): List<Map<*, *>> = weaponBuffs(d) + artifactBuffs(d)

    /** 上游 calc_auto.js 的组队预设 buff：内置快照只收了 calc.js，这里补齐截图涉及角色的队伍增益。 */
    private fun presetAutoBuffs(d: DetailUi): List<Map<String, Any?>> {
        if (d.aid != 1506) return emptyList()
        fun cpctOf(args: List<Any?>): Double {
            val ds = args.firstOrNull() as? Map<*, *> ?: return 0.0
            val attr = ds["attr"] as? Map<*, *> ?: return 0.0
            val it = attr["cpct"] as? Map<*, *> ?: return 0.0
            return MiaoJsEvaluator.number(it["base"]) + MiaoJsEvaluator.number(it["plus"])
        }
        return listOf(
            mapOf("title" to "队伍预设·藿藿1魂：速度+12%", "data" to mapOf("speedPct" to 12.0)),
            mapOf("title" to "队伍预设·爻光大招：全属性抗性穿透+20", "data" to mapOf("kx" to 20.0)),
            mapOf("title" to "队伍预设·爻光天赋：敌方易伤+16", "data" to mapOf("enemydmg" to 16.0)),
            mapOf("title" to "队伍预设·爻光专武：暴击+10 爆伤+30", "data" to mapOf("cpct" to 10.0, "cdmg" to 30.0)),
            mapOf("title" to "队伍预设·爻光1魂：无视防御+20%", "data" to mapOf("ignore" to 20.0)),
            mapOf("title" to "队伍预设·火花行迹：全队爆伤+80%", "data" to mapOf("cdmg" to 80.0)),
            mapOf("title" to "队伍预设·狼尊隐藏分：暴击补足至100、爆伤=140+暴击", "data" to mapOf(
                "cpct" to MiaoJsEvaluator.NativeFunction { args ->
                    val c = cpctOf(args)
                    if (c >= 100) 0.0 else 100.0 - c
                },
                "cdmg" to MiaoJsEvaluator.NativeFunction { args -> 140.0 + cpctOf(args) }
            ))
        )
    }

    private fun weaponBuffs(d: DetailUi): List<Map<*, *>> {
        val lc = d.lc ?: return emptyList()
        return weaponBuffs(lc.name, lc.superimpose, d.path, false)
    }

    fun staticWeaponBuffs(name: String, affix: Int, path: String): List<Map<*, *>> =
        weaponBuffs(name, affix, path, true)

    private fun weaponBuffs(name: String, superimpose: Int, path: String, static: Boolean): List<Map<*, *>> {
        val asset = equipment ?: return emptyList()
        val weaponData = asset.optJSONObject("weaponData")?.optJSONObject(name)
        val type = weaponData?.optString("type")?.takeIf { it.isNotBlank() } ?: path
        val program = asset.optJSONObject("weaponRules")?.optJSONObject(type) ?: return emptyList()
        val env = MiaoJsEvaluator.execute(program)

        val staticIdx = MiaoJsEvaluator.NativeFunction { args ->
            linkedMapOf<String, Any?>(
                "isStatic" to true,
                "idx" to args.getOrNull(0),
                "key" to args.getOrNull(1)
            )
        }
        val keyIdx = MiaoJsEvaluator.NativeFunction { args ->
            val title = args.getOrNull(0)
            val key = args.getOrNull(1)
            val idx = args.getOrNull(2)
            if (key is Map<*, *>) {
                MiaoJsEvaluator.NativeFunction { callArgs ->
                    val tables = toStringMap(callArgs.firstOrNull())
                    val values = linkedMapOf<String, Any?>()
                    key.forEach { (dataKey, tableIdx) ->
                        values[MiaoJsEvaluator.string(dataKey)] = tables[MiaoJsEvaluator.string(tableIdx)]
                    }
                    linkedMapOf<String, Any?>("title" to title, "data" to values)
                }
            } else {
                linkedMapOf<String, Any?>("title" to title, "idx" to idx, "key" to key)
            }
        }
        val default = maybeCall(env.get("\$default"), listOf(staticIdx, keyIdx))
        val root = toStringMap(default)
        val selected = root[name] ?: root[weaponData?.optString("id").orEmpty()] ?: return emptyList()
        val configs = selected as? List<*> ?: listOf(selected)

        val affix = superimpose.coerceAtLeast(1)
        val tables = linkedMapOf<String, Any?>()
        val tableData = weaponData?.optJSONObject("skill")?.optJSONObject("tables")
        if (tableData != null) {
            val keys = tableData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val values = tableData.optJSONArray(key) ?: continue
                tables[key] = if (affix - 1 in 0 until values.length()) {
                    MiaoJsEvaluator.toJs(values.opt(affix - 1))
                } else MiaoJsEvaluator.Undefined
            }
        }

        val result = mutableListOf<Map<*, *>>()
        buffLoop@ for (raw in configs) {
            val value = if (MiaoJsEvaluator.isCallable(raw)) {
                MiaoJsEvaluator.call(raw, listOf(tables))
            } else raw
            val buff = toStringMap(value)
            if (buff.isEmpty() || MiaoJsEvaluator.truthy(buff["isStatic"]) != static) continue
            val data = toStringMap(buff["data"])
            buff["data"] = data

            val idx = buff["idx"]
            val key = buff["key"]
            if (MiaoJsEvaluator.truthy(idx) && MiaoJsEvaluator.truthy(key)) {
                val tableValue = tables[MiaoJsEvaluator.string(idx)]
                if (!MiaoJsEvaluator.truthy(tableValue)) continue@buffLoop
                data[MiaoJsEvaluator.string(key)] = tableValue
            } else {
                val refine = buff["refine"] as? Map<*, *>
                if (refine != null) {
                    val buffCount = MiaoJsEvaluator.number(buff["buffCount"] ?: 1)
                    for ((dataKey, rawValues) in refine) {
                        val values = rawValues as? List<*> ?: continue
                        if (static) {
                            data[MiaoJsEvaluator.string(dataKey)] =
                                MiaoJsEvaluator.number(values.getOrNull(affix - 1)) * buffCount
                        } else {
                            data[MiaoJsEvaluator.string(dataKey)] = MiaoJsEvaluator.NativeFunction { args ->
                                val ds = toStringMap(args.firstOrNull())
                                val refineIdx = MiaoJsEvaluator.number(ds["refine"]).toInt()
                                MiaoJsEvaluator.number(values.getOrNull(refineIdx)) * buffCount
                            }
                        }
                    }
                }
            }
            result += buff
        }
        return result
    }

    private fun artifactBuffs(d: DetailUi): List<Map<*, *>> =
        artifactBuffs(d.panel?.sets ?: d.sets, d.element, false)

    fun staticArtifactBuffs(sets: List<SetUi>, element: String): List<Map<*, *>> =
        artifactBuffs(sets, element, true)

    private fun artifactBuffs(sets: List<SetUi>, element: String, static: Boolean): List<Map<*, *>> {
        val program = equipment?.optJSONObject("artifactProgram") ?: return emptyList()
        val root = toStringMap(MiaoJsEvaluator.execute(program).get("\$default"))
        val result = mutableListOf<Map<*, *>>()
        for (set in sets) {
            val setRules = toStringMap(root[set.name])
            val counts = if (set.count >= 4) listOf(2, 4) else listOf(set.count)
            for (count in counts) {
                val selected = setRules[count.toString()] ?: continue
                val configs = selected as? List<*> ?: listOf(selected)
                for (raw in configs) {
                    val buff = raw as? Map<*, *> ?: continue
                    if (MiaoJsEvaluator.truthy(buff["isStatic"]) != static) continue
                    val requiredElement = buff["elem"]?.let(MiaoJsEvaluator::string).orEmpty()
                    if (requiredElement.isNotBlank() && requiredElement != element) continue
                    result += buff
                }
            }
        }
        return result
    }

    private fun calcValue(value: Any?): Double {
        val item = value as? Map<*, *> ?: return 0.0
        val base = MiaoJsEvaluator.number(item["base"])
        val plus = MiaoJsEvaluator.number(item["plus"])
        val pct = MiaoJsEvaluator.number(item["pct"])
        return base + plus + base * pct / 100
    }

    private fun item(attr: JsMap, key: String): Map<*, *> = attr[key] as? Map<*, *> ?: emptyMap<String, Any?>()

    private fun itemNum(attr: JsMap, key: String, field: String): Double =
        MiaoJsEvaluator.number(item(attr, key)[field])

    private fun dynamicNum(dynamic: Map<*, *>, key: String): Double = MiaoJsEvaluator.number(dynamic[key])

    private fun damageDispatcher(attr: JsMap, level: Int, enemyLv: Int): MiaoJsEvaluator.NativeFunction {
        lateinit var basic: MiaoJsEvaluator.NativeFunction
        lateinit var dynamic: MiaoJsEvaluator.NativeFunction
        lateinit var reaction: MiaoJsEvaluator.NativeFunction
        lateinit var heal: MiaoJsEvaluator.NativeFunction
        lateinit var shield: MiaoJsEvaluator.NativeFunction
        lateinit var dispatcher: MiaoJsEvaluator.NativeFunction

        basic = MiaoJsEvaluator.NativeFunction { args ->
            calcDmg(0.0, args.getOrNull(1), args.getOrNull(2), args.getOrNull(0), "basic",
                args.getOrNull(3), args.getOrNull(4), attr, level, enemyLv)
        }
        dynamic = MiaoJsEvaluator.NativeFunction { args ->
            calcDmg(args.getOrNull(0), args.getOrNull(1), args.getOrNull(3), 0.0, "talent",
                args.getOrNull(2), args.getOrNull(4), attr, level, enemyLv)
        }
        reaction = MiaoJsEvaluator.NativeFunction { args ->
            val element = MiaoJsEvaluator.string(args.getOrNull(0))
            val talent = when (element) {
                "shock", "burn", "windShear", "bleed" -> "dot"
                "superBreak", "lightningBreak", "fireBreak", "windBreak", "physicalBreak",
                "quantumBreak", "imaginaryBreak", "iceBreak" -> "break"
                else -> MiaoJsEvaluator.string(args.getOrNull(2) ?: "fy")
            }
            calcDmg(0.0, talent, element, 0.0, "basic", null, args.getOrNull(1), attr, level, enemyLv)
        }
        heal = MiaoJsEvaluator.NativeFunction { args ->
            linkedMapOf<String, Any?>("avg" to MiaoJsEvaluator.number(args.firstOrNull()) *
                (1 + calcValue(attr["heal"]) / 100 + itemNum(attr, "heal", "inc") / 100))
        }
        shield = MiaoJsEvaluator.NativeFunction { args ->
            linkedMapOf<String, Any?>("avg" to MiaoJsEvaluator.number(args.firstOrNull()) *
                (calcValue(attr["shield"]) / 100) * (itemNum(attr, "shield", "inc") / 100))
        }
        dispatcher = MiaoJsEvaluator.NativeFunction(
            { args ->
                calcDmg(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2), args.getOrNull(3),
                    args.getOrNull(4)?.toString() ?: "talent", args.getOrNull(5), args.getOrNull(6), attr, level, enemyLv)
            },
            mapOf(
                "basic" to basic, "dynamic" to dynamic, "reaction" to reaction,
                "heal" to heal, "shield" to shield,
                "swirl" to MiaoJsEvaluator.NativeFunction { calcDmg(0.0, "fy", "swirl", 0.0, "basic", null, null, attr, level, enemyLv) }
            )
        )
        return dispatcher
    }

    private fun calcDmg(
        pctValue: Any?,
        talentValue: Any?,
        eleValue: Any?,
        basicValue: Any?,
        mode: String,
        dynamicValue: Any?,
        paramsValue: Any?,
        attr: JsMap,
        level: Int,
        enemyLv: Int
    ): JsMap {
        val talent = if (talentValue == null || talentValue == MiaoJsEvaluator.Undefined || talentValue is Boolean) "" else talentValue.toString()
        val ele = if (eleValue == null || eleValue == MiaoJsEvaluator.Undefined || eleValue is Boolean) "" else eleValue.toString()
        val dynamic = dynamicValue as? Map<*, *> ?: emptyMap<String, Any?>()
        var pctNum = MiaoJsEvaluator.number(pctValue)
        val basicNum = MiaoJsEvaluator.number(basicValue)
        var multiNum = MiaoJsEvaluator.number(attr["multi"]) / 100
        var dmgNum = if (ele == "phy") {
            1 + itemNum(attr, "phy", "base") / 100 + itemNum(attr, "phy", "plus") / 100 + dynamicNum(dynamic, "dynamicPhy") / 100
        } else {
            1 + itemNum(attr, "dmg", "base") / 100 + itemNum(attr, "dmg", "plus") / 100 + dynamicNum(dynamic, "dynamicDmg") / 100
        }
        var enemydmgNum = 1 + itemNum(attr, "enemydmg", "base") / 100 + itemNum(attr, "enemydmg", "plus") / 100 +
            dynamicNum(dynamic, "dynamicEnemydmg") / 100
        var cpctNum = itemNum(attr, "cpct", "base") / 100 + itemNum(attr, "cpct", "plus") / 100 +
            dynamicNum(dynamic, "dynamicCpct") / 100
        var cdmgNum = itemNum(attr, "cdmg", "base") / 100 + itemNum(attr, "cdmg", "plus") / 100 +
            dynamicNum(dynamic, "dynamicCdmg") / 100
        var enemyDef = itemNum(attr, "enemy", "def") / 100
        var enemyIgnore = itemNum(attr, "enemy", "ignore") / 100
        var plusNum = 0.0

        if (talent.isNotBlank()) {
            talent.split(",").forEach { key ->
                val item = attr[key] as? Map<*, *> ?: return@forEach
                pctNum += MiaoJsEvaluator.number(item["pct"]) / 100
                dmgNum += MiaoJsEvaluator.number(item["dmg"]) / 100
                enemydmgNum += MiaoJsEvaluator.number(item["enemydmg"]) / 100
                cpctNum += MiaoJsEvaluator.number(item["cpct"]) / 100
                cdmgNum += MiaoJsEvaluator.number(item["cdmg"]) / 100
                enemyDef += MiaoJsEvaluator.number(item["def"]) / 100
                enemyIgnore += MiaoJsEvaluator.number(item["ignore"]) / 100
                multiNum += MiaoJsEvaluator.number(item["multi"]) / 100
                plusNum += MiaoJsEvaluator.number(item["plus"])
            }
        }
        if (ele == "superBreak") enemyIgnore += itemNum(attr, "superBreak", "ignore") / 100

        val defNum = ElationDamage.defNum(level, enemyLv, enemyDef, enemyIgnore)
        val kNum = 1 + MiaoJsEvaluator.number(attr["kx"]) / 100
        val reduceNum = 0.9
        cpctNum = cpctNum.coerceIn(0.0, 1.0)
        if (cpctNum == 0.0) cdmgNum = 0.0
        val atkNum = calcValue(attr["atk"])
        val dmgBase = if (mode == "basic") basicNum * (1 + multiNum) + plusNum
        else atkNum * pctNum * (1 + multiNum) + plusNum

        val breakElements = setOf("shock", "burn", "windShear", "bleed", "entanglement", "lightningBreak",
            "fireBreak", "windBreak", "physicalBreak", "quantumBreak", "imaginaryBreak", "iceBreak", "superBreak")
        if (ele == "skillDot") {
            return linkedMapOf("avg" to dmgBase * dmgNum * enemydmgNum * defNum * kNum * reduceNum)
        }
        if (ele in breakElements) {
            val eleNum = when (ele) {
                "shock" -> 2.0
                "fireBreak", "physicalBreak" -> 2.0
                "windBreak" -> 1.5
                "quantumBreak", "imaginaryBreak" -> 0.5
                "entanglement" -> 0.6
                else -> 1.0
            }
            val stanceNum = 1 + calcValue(attr["stance"]) / 100
            val base = BREAK_BASE[(level - 1).coerceIn(0, BREAK_BASE.lastIndex)]
            return linkedMapOf("avg" to base * eleNum * stanceNum * enemydmgNum * defNum * kNum * reduceNum)
        }
        if (ele == "elation") {
            pctNum += multiNum
            val merryNum = 1 + MiaoJsEvaluator.number(attr["merrymakes"]) / 100
            val joyNum = 1 + itemNum(attr, "joy", "base") / 100 + itemNum(attr, "joy", "plus") / 100
            val punchline = MiaoJsEvaluator.number(attr["punchline"])
            val punchNum = 1 + punchline * 5 / (punchline + 240)
            val common = ElationDamage.baseOf(level) * pctNum * merryNum * joyNum * punchNum * defNum * kNum * reduceNum * enemydmgNum
            return linkedMapOf(
                "dmg" to common * (1 + cdmgNum),
                "avg" to common * (1 + cpctNum * cdmgNum)
            )
        }
        return linkedMapOf(
            "dmg" to dmgBase * dmgNum * enemydmgNum * (1 + cdmgNum) * defNum * kNum * reduceNum,
            "avg" to dmgBase * dmgNum * enemydmgNum * (1 + cpctNum * cdmgNum) * defNum * kNum * reduceNum
        )
    }

    private fun finiteNumber(value: Any?): Double? {
        if (value !is Number) return null
        return value.toDouble().takeIf { it.isFinite() }
    }

    private val BREAK_BASE = doubleArrayOf(
        54.0, 58.0, 62.0, 67.53, 70.51, 73.52, 76.57, 79.64, 82.74, 85.87,
        91.49, 97.07, 102.59, 108.06, 113.47, 118.84, 124.15, 129.41, 134.62, 139.77,
        149.33, 158.8, 168.18, 177.46, 186.65, 195.75, 204.75, 213.66, 222.48, 231.2,
        246.43, 261.18, 275.47, 289.32, 302.73, 315.71, 328.29, 340.47, 352.26, 363.67,
        408.12, 451.79, 494.68, 536.82, 578.22, 618.92, 658.91, 698.23, 736.89, 774.9,
        871.06, 964.87, 1056.42, 1145.79, 1233.06, 1318.3, 1401.58, 1482.96, 1562.52, 1640.31,
        1752.32, 1861.9, 1969.12, 2074.07, 2176.8, 2277.39, 2375.91, 2472.42, 2566.97, 2659.64,
        2780.3, 2898.6, 3014.6, 3128.37, 3239.98, 3349.47, 3456.92, 3562.38, 3665.91, 3767.55
    )
}
