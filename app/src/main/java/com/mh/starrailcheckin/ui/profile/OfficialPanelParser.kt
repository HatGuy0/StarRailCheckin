package com.mh.starrailcheckin.ui.profile

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/** 米游社官方面板重建，对齐 MysPanelHSRData + Avatar/Attr + ArtisAttr。 */
internal object OfficialPanelParser {
    data class AttrPart(
        val base: Double = 0.0,
        val plus: Double = 0.0,
        val pct: Double = 0.0,
        val inc: Double = 0.0
    )

    data class WeaponResult(
        val id: Int,
        val name: String,
        val level: Int,
        val promote: Int,
        val affix: Int,
        val attr: Map<String, Double>
    )

    data class Result(
        val skills: OfficialSkillParser.Result,
        val attr: Map<String, Double>,
        val staticAttr: Map<String, AttrPart>,
        val base: Map<String, Double>,
        val sets: List<SetUi>,
        val weapon: WeaponResult?,
        val promote: Int,
        val relicMainIds: Map<Int, Int>,
        val relicAttrIds: Map<Int, List<String>>
    )

    private val ATTR_KEYS = listOf(
        "atk", "def", "hp", "speed", "recharge", "cpct", "cdmg", "dmg", "heal",
        "stance", "effPct", "effDef", "joy"
    )
    private val FLAT_KEYS = setOf("hp", "atk", "def", "speed")
    private val ATTR_SUFFIX = Regex("^(atk|def|hp|speed|recharge|cpct|cdmg|dmg|heal|stance|effPct|effDef|joy)(Base|Plus|Pct|Inc)$")
    private val PROMOTE_BOUNDARIES = intArrayOf(1, 20, 30, 40, 50, 60, 70, 80)
    private val PROPERTY_KEYS = mapOf(
        1 to "hpPlus", 2 to "atkPlus", 3 to "defPlus", 4 to "speed", 5 to "cpct",
        6 to "cdmg", 7 to "heal", 9 to "recharge", 10 to "effPct", 11 to "effDef",
        12 to "phy", 14 to "fire", 16 to "ice", 18 to "elec", 20 to "wind",
        22 to "quantum", 24 to "imaginary", 32 to "hp", 33 to "atk", 34 to "def",
        58 to "stance", 51 to "speed", 52 to "cpct", 53 to "cdmg", 27 to "hpPlus",
        29 to "atkPlus", 31 to "defPlus", 54 to "recharge", 55 to "heal",
        56 to "effPct", 57 to "effDef", 59 to "stance"
    )
    private val ELEMENTS = mapOf(
        "phy" to "物理", "fire" to "火", "ice" to "冰", "elec" to "雷",
        "wind" to "风", "quantum" to "量子", "imaginary" to "虚数"
    )

    private data class SubId(val id: Int, val count: Int, val step: Int) {
        override fun toString(): String = "$id,$count,$step"
    }

    private data class RelicInput(
        val id: Int,
        val level: Int,
        val sourceStar: Int,
        val star: Int,
        val setName: String,
        val mainId: Int,
        val attrIds: MutableList<SubId>
    )

    private class AttrBuilder(private val element: String) {
        private val parts = ATTR_KEYS.associateWith { MutablePart() }
        private val baseTotals = ATTR_KEYS.associateWithTo(linkedMapOf()) { 0.0 }

        fun add(rawKey: String, value: Double, isBase: Boolean = false) {
            var key = rawKey
            ELEMENTS[key]?.let { elem ->
                if (elem != element) return
                key = "dmg"
            }
            parts[key]?.let { part ->
                part.plus += value
                if (isBase) baseTotals[key] = (baseTotals[key] ?: 0.0) + value
                return
            }
            val match = ATTR_SUFFIX.matchEntire(key) ?: return
            val part = parts[match.groupValues[1]] ?: return
            val field = match.groupValues[2]
            when (field) {
                "Base" -> part.base += value
                "Plus" -> part.plus += value
                "Pct" -> part.pct += value
                "Inc" -> part.inc += value
            }
            if (field == "Base" || isBase) {
                val baseKey = match.groupValues[1]
                baseTotals[baseKey] = (baseTotals[baseKey] ?: 0.0) + value
            }
        }

        fun attr(): Map<String, Double> = linkedMapOf<String, Double>().also { out ->
            for (key in ATTR_KEYS) {
                val part = parts.getValue(key)
                out[key] = part.base * (1 + part.pct / 100) + part.plus
                if (key in FLAT_KEYS) out["${key}Base"] = part.base
            }
        }

        fun staticAttr(): Map<String, AttrPart> = ATTR_KEYS.associateWith { key ->
            parts.getValue(key).let { AttrPart(it.base, it.plus, it.pct, it.inc) }
        }

        fun base(): Map<String, Double> = baseTotals.toMap()
    }

    private data class MutablePart(
        var base: Double = 0.0,
        var plus: Double = 0.0,
        var pct: Double = 0.0,
        var inc: Double = 0.0
    )

    fun parse(raw: JSONObject, character: JSONObject, equipment: JSONObject, artifact: JSONObject): Result {
        val rank = raw.optInt("rank", 0)
        val skills = OfficialSkillParser.parse(raw, rank, character.optJSONObject("talentCons"))
        val level = raw.optInt("level", 1)
        val equip = raw.optJSONObject("equip") ?: raw.optJSONObject("equipment")
        val weaponData = equip?.let { findWeapon(equipment, it.optInt("id", it.optInt("tid", 0))) }
        var promote = calcPromote(level)
        var weaponPromote = equip?.let { calcPromote(it.optInt("level", 1)) }

        if (level in intArrayOf(20, 30, 40, 50, 60, 70) ||
            (equip?.optInt("level", 1) ?: -1) in intArrayOf(20, 30, 40, 50, 60, 70)
        ) {
            val baseHp = property(raw.optJSONArray("properties"), 1)?.let { number(it.opt("base")) } ?: 0.0
            if (baseHp > 0) {
                val charPromotes = mutableListOf(promote)
                if (level in intArrayOf(20, 30, 40, 50, 60, 70)) charPromotes += promote + 1
                val weaponPromotes = when {
                    weaponData != null -> mutableListOf(weaponPromote ?: 0).also { list ->
                        val weaponLevel = equip.optInt("level", 1)
                        if (weaponLevel in intArrayOf(20, 30, 40, 50, 60, 70)) list += (weaponPromote ?: 0) + 1
                    }
                    else -> mutableListOf<Int?>(null)
                }
                var minDiff = Double.POSITIVE_INFINITY
                var bestChar = promote
                var bestWeapon = weaponPromote
                for (charCandidate in charPromotes) {
                    val charHp = characterAttr(character, level, charCandidate)["hp"] ?: continue
                    for (weaponCandidate in weaponPromotes) {
                        val weaponHp = if (weaponData != null && weaponCandidate != null) {
                            weaponAttr(weaponData, equip.optInt("level", 1), weaponCandidate)["hp"] ?: 0.0
                        } else 0.0
                        val diff = abs(baseHp - charHp - weaponHp)
                        if (diff < minDiff) {
                            minDiff = diff
                            bestChar = charCandidate
                            if (weaponData != null) bestWeapon = weaponCandidate
                        }
                    }
                }
                promote = bestChar
                weaponPromote = bestWeapon
            }
        }
        promote = min(promote, 6)
        weaponPromote = weaponPromote?.let { min(it, 6) }

        val weapon = if (equip != null && weaponData != null) {
            val weaponLevel = equip.optInt("level", 1)
            val affix = equip.optInt("rank", 1)
            WeaponResult(
                id = weaponData.optString("id").toIntOrNull() ?: equip.optInt("id", 0),
                name = weaponData.optString("name"),
                level = weaponLevel,
                promote = weaponPromote ?: 0,
                affix = affix,
                attr = weaponAttr(weaponData, weaponLevel, weaponPromote ?: 0)
            )
        } else null
        val relics = parseRelics(raw, artifact)
        val sets = relicSets(relics.values)

        fun calculate(): Triple<Map<String, Double>, Map<String, AttrPart>, Map<String, Double>> {
            val builder = AttrBuilder(character.optString("elem"))
            for ((key, value) in characterAttr(character, level, promote)) {
                builder.add(if (key in FLAT_KEYS) "${key}Base" else key, value, true)
            }
            val tree = character.optJSONObject("tree")
            if (tree != null) {
                for (node in skills.characterNodes.objects()) {
                    if (!OfficialSkillParser.isActivatedStatNode(node)) continue
                    val pointId = OfficialSkillParser.pointId(node)
                    val suffix = Regex("1?\\d{4}(\\d{3})").find(pointId.toString())?.groupValues?.get(1)
                    val fullId = tree.keys().asSequence().firstOrNull { id ->
                        Regex("1?\\d{4}(\\d{3})").find(id)?.groupValues?.get(1) == suffix
                    } ?: continue
                    val config = tree.optJSONObject(fullId) ?: continue
                    var key = config.optString("key")
                    if (key in listOf("atk", "hp", "def")) key += "Pct"
                    builder.add(key, config.optDouble("value", 0.0))
                }
            }
            if (weapon != null && weaponData != null) {
                for ((key, value) in weapon.attr) {
                    builder.add(if (key in listOf("hp", "atk", "def")) "${key}Base" else key, value, true)
                }
                if (weaponData.optString("type") == character.optString("weapon")) {
                    for (buff in MiaoRulesEngine.staticWeaponBuffs(weapon.name, weapon.affix, character.optString("weapon"))) {
                        addBuffData(builder, buff["data"] as? Map<*, *>)
                    }
                }
            }
            val mainIdx = artifact.optJSONObject("metaData")?.optJSONObject("mainIdx")
            val starData = artifact.optJSONObject("metaData")?.optJSONObject("starData")
            for ((pos, relic) in relics) {
                val rarity = starData?.optJSONObject(relic.star.toString()) ?: continue
                val mainKey = mainIdx?.optJSONObject(pos.toString())?.optString(relic.mainId.toString()).orEmpty()
                val main = rarity.optJSONObject("main")?.optJSONObject(mainKey)
                if (mainKey.isNotBlank() && main != null) {
                    builder.add(relicAttrKey(mainKey), main.optDouble("base") + main.optDouble("step") * relic.level)
                }
                for (sub in relic.attrIds) {
                    val config = rarity.optJSONObject("sub")?.optJSONObject(sub.id.toString()) ?: continue
                    builder.add(
                        relicAttrKey(config.optString("key")),
                        config.optDouble("base") * sub.count + config.optDouble("step") * sub.step
                    )
                }
            }
            for (buff in MiaoRulesEngine.staticArtifactBuffs(sets, character.optString("elem"))) {
                addBuffData(builder, buff["data"] as? Map<*, *>)
            }
            return Triple(builder.attr(), builder.staticAttr(), builder.base())
        }

        var (attr, staticAttr, base) = calculate()
        val targetSpeed = property(raw.optJSONArray("properties"), 4)?.let { number(it.opt("final")) }
        if (targetSpeed != null && attr["speed"] != null) {
            var speedDiff = targetSpeed - (attr["speed"] ?: 0.0)
            if (speedDiff > 0.2) {
                var fixed = false
                var loops = 0
                while (speedDiff > 0.05 && loops < 20) {
                    var hasSpeed = false
                    for (relic in relics.values) {
                        for (index in relic.attrIds.indices) {
                            if (speedDiff <= 0.05) break
                            val sub = relic.attrIds[index]
                            if (sub.id != 7) continue
                            relic.attrIds[index] = sub.copy(step = sub.step + 1)
                            speedDiff -= if (relic.sourceStar >= 5) 0.3 else 0.2
                            fixed = true
                            hasSpeed = true
                        }
                        if (speedDiff <= 0.05) break
                    }
                    if (!hasSpeed) break
                    loops++
                }
                if (fixed) calculate().also {
                    attr = it.first
                    staticAttr = it.second
                    base = it.third
                }
            }
        }
        return Result(
            skills = skills,
            attr = attr,
            staticAttr = staticAttr,
            base = base,
            sets = sets,
            weapon = weapon,
            promote = promote,
            relicMainIds = relics.mapValues { it.value.mainId },
            relicAttrIds = relics.mapValues { entry -> entry.value.attrIds.map(SubId::toString) }
        )
    }

    private fun addBuffData(builder: AttrBuilder, data: Map<*, *>?) {
        data?.forEach { (key, value) -> builder.add(key.toString(), MiaoJsEvaluator.number(value)) }
    }

    private fun relicAttrKey(key: String): String =
        if (key in listOf("atk", "hp", "def")) "${key}Pct" else key

    private fun characterAttr(character: JSONObject, level: Int, promote: Int): Map<String, Double> {
        val container = character.opt("attr")
        val data = when (container) {
            is JSONObject -> container.optJSONObject(promote.toString())
            is JSONArray -> container.optJSONObject(promote)
            else -> null
        } ?: return emptyMap()
        val result = linkedMapOf<String, Double>()
        val attrs = data.optJSONObject("attrs")
        attrs?.keys()?.forEach { key -> result[key] = attrs.optDouble(key) }
        val grow = data.optJSONObject("grow")
        grow?.keys()?.forEach { key -> result[key] = (result[key] ?: 0.0) + grow.optDouble(key) * (level - 1) }
        return result
    }

    private fun weaponAttr(weapon: JSONObject, level: Int, promote: Int): Map<String, Double> {
        val data = weapon.optJSONObject("attr")?.optJSONObject(promote.toString()) ?: return emptyMap()
        val result = linkedMapOf<String, Double>()
        val attrs = data.optJSONObject("attrs")
        attrs?.keys()?.forEach { key -> result[key] = attrs.optDouble(key) }
        val grow = weapon.optJSONObject("growAttr")
        grow?.keys()?.forEach { key -> result[key] = (result[key] ?: 0.0) + grow.optDouble(key) * (level - 1) }
        return result
    }

    private fun findWeapon(equipment: JSONObject, id: Int): JSONObject? {
        if (id <= 0) return null
        val weapons = equipment.optJSONObject("weaponData") ?: return null
        for (key in weapons.keys()) {
            val weapon = weapons.optJSONObject(key) ?: continue
            if ((weapon.optString("id").toIntOrNull() ?: 0) == id) return weapon
        }
        return null
    }

    private fun calcPromote(level: Int): Int {
        var promote = 0
        for (index in 0 until PROMOTE_BOUNDARIES.lastIndex) {
            if (level >= PROMOTE_BOUNDARIES[index] && level <= PROMOTE_BOUNDARIES[index + 1]) return promote
            promote++
        }
        return promote
    }

    private fun property(properties: JSONArray?, type: Int): JSONObject? {
        properties ?: return null
        for (item in properties.objects()) {
            val propertyType = item.opt("property_type") ?: item.opt("propertyType")
            if ((propertyType as? Number)?.toInt() == type || propertyType?.toString()?.toIntOrNull() == type) return item
        }
        return null
    }

    private fun parseRelics(raw: JSONObject, artifact: JSONObject): LinkedHashMap<Int, RelicInput> {
        val result = linkedMapOf<Int, RelicInput>()
        val metadata = artifact.optJSONObject("relics") ?: return result
        val metaData = artifact.optJSONObject("metaData") ?: return result
        val arrays = listOfNotNull(raw.optJSONArray("relics"), raw.optJSONArray("ornaments"))
        for (array in arrays) {
            for (item in array.objects()) {
                val pos = item.optInt("pos", 0)
                if (pos <= 0) continue
                val id = item.optInt("id", 0)
                val relicMeta = metadata.optJSONObject(id.toString()) ?: continue
                val sourceStar = item.optInt("rarity", 5)
                val star = relicMeta.optInt("star", sourceStar)
                val propertyType = item.optJSONObject("main_property")?.optInt("property_type", 0) ?: 0
                val propertyKey = PROPERTY_KEYS[propertyType].orEmpty()
                val mainObj = metaData.optJSONObject("mainIdx")?.optJSONObject(pos.toString())
                var mainId = 0
                mainObj?.keys()?.forEach { key -> if (mainObj.optString(key) == propertyKey) mainId = key.toIntOrNull() ?: 0 }
                val subConfigs = metaData.optJSONObject("starData")?.optJSONObject(sourceStar.toString())?.optJSONObject("sub")
                val attrIds = mutableListOf<SubId>()
                val properties = item.optJSONArray("properties")
                if (properties != null && subConfigs != null) {
                    for (subProperty in properties.objects()) {
                        if (subProperty.optBoolean("is_preview", false)) continue
                        val subKey = PROPERTY_KEYS[subProperty.optInt("property_type", 0)]
                        val subId = subConfigs.keys().asSequence().firstOrNull { idKey ->
                            subConfigs.optJSONObject(idKey)?.optString("key") == subKey
                        }?.toIntOrNull() ?: continue
                        val config = subConfigs.optJSONObject(subId.toString()) ?: continue
                        val count = subProperty.optInt("times", 0)
                        val value = number(subProperty.opt("value"))
                        val step = floor((value - count * config.optDouble("base")) / config.optDouble("step") + 0.5).toInt()
                        attrIds += SubId(subId, count, step)
                    }
                }
                result[pos] = RelicInput(
                    id = id,
                    level = min(15, item.optInt("level", 0)),
                    sourceStar = sourceStar,
                    star = star,
                    setName = relicMeta.optString("set"),
                    mainId = mainId,
                    attrIds = attrIds
                )
            }
        }
        return result
    }

    private fun relicSets(relics: Collection<RelicInput>): List<SetUi> {
        val counts = linkedMapOf<String, Int>()
        for (relic in relics) counts[relic.setName] = (counts[relic.setName] ?: 0) + 1
        return counts.mapNotNull { (name, rawCount) ->
            if (rawCount < 2) null else SetUi(name, if (rawCount >= 4) 4 else 2, "")
        }
    }

    private fun number(value: Any?): Double = when (value) {
        is Number -> value.toDouble()
        else -> value?.toString()?.trim()?.removeSuffix("%")?.toDoubleOrNull() ?: 0.0
    }

    private fun JSONArray.objects(): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        for (index in 0 until length()) optJSONObject(index)?.let(result::add)
        return result
    }
}
