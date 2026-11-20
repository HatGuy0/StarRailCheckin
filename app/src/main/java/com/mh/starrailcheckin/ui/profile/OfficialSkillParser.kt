package com.mh.starrailcheckin.ui.profile

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** 米游社官方面板技能输入，对齐 miao MysPanelHSRData + CharTalent + ProfileDmg.trees。 */
internal object OfficialSkillParser {
    data class Result(
        val allNodes: JSONArray,
        val characterNodes: JSONArray,
        val skillLevels: Map<Int, Int>,
        val originalTalentLevels: Map<String, Int>,
        val talentLevels: Map<String, Int>,
        val treeIds: Set<String>
    )

    private val CHARACTER_KEYS = arrayOf(
        "skillTreeList", "skill_tree_list", "skillTree", "skill_tree", "skills", "skill_list"
    )
    private val SERVANT_KEYS = arrayOf(
        "servantSkillList", "servant_skill_list", "memospriteSkillList", "memosprite_skill_list",
        "servant_skills", "servantSkills"
    )
    private val REMAKE_KEYS = mapOf(
        "普攻" to "a", "战技" to "e", "终结技" to "q", "天赋" to "t", "秘技" to "z",
        "欢愉技" to "xe", "忆灵技" to "me", "忆灵天赋" to "mt"
    )
    private val TALENT_STEPS = mapOf("a" to 1, "e" to 2, "q" to 2, "t" to 2, "xe" to 1, "me" to 1, "mt" to 1)
    private val TALENT_MAX = mapOf("a" to 10, "e" to 15, "q" to 15, "t" to 15, "xe" to 15, "me" to 10, "mt" to 10)

    fun parse(raw: JSONObject, rank: Int, talentCons: JSONObject?): Result {
        val character = LinkedHashMap<Int, JSONObject>()
        collect(raw, CHARACTER_KEYS, character)
        val all = LinkedHashMap<Int, JSONObject>(character)
        collect(raw, SERVANT_KEYS, all)
        for (nestedKey in arrayOf("servant_detail", "servantDetail", "memosprite_detail", "memospriteDetail")) {
            raw.optJSONObject(nestedKey)?.let { collect(it, SERVANT_KEYS, all) }
        }

        val skillLevels = mutableMapOf<Int, Int>()
        val displayed = mutableMapOf<String, Int>()
        for ((pid, node) in all) {
            val level = node.optInt("level", 0)
            if (level > 0) skillLevels[pid] = level
            val key = REMAKE_KEYS[node.optString("remake", "")] ?: continue
            if (level > 0) displayed[key] = level
        }

        val original = mutableMapOf<String, Int>()
        val effective = mutableMapOf<String, Int>()
        for ((key, shownLevel) in displayed) {
            if (key == "z") continue
            val plus = consBonus(talentCons?.opt(key), rank, TALENT_STEPS[key] ?: 0)
            val originalLevel = if (plus > 0) max(1, shownLevel - plus) else shownLevel
            original[key] = originalLevel
            val maxLevel = TALENT_MAX[key] ?: Int.MAX_VALUE
            effective[key] = min(maxLevel, originalLevel + plus)
        }

        val trees = mutableSetOf<String>()
        val treePattern = Regex("1?\\d{4}(\\d{3})")
        for ((pid, node) in character) {
            if (!isActivatedStatNode(node)) continue
            val suffix = treePattern.find(pid.toString())?.groupValues?.get(1) ?: continue
            trees += suffix
        }
        return Result(
            allNodes = toArray(all.values),
            characterNodes = toArray(character.values),
            skillLevels = skillLevels,
            originalTalentLevels = original,
            talentLevels = effective,
            treeIds = trees
        )
    }

    fun isActivatedStatNode(node: JSONObject): Boolean {
        val pointType = if (node.has("point_type")) node.optInt("point_type", 0) else node.optInt("pointType", 0)
        if (pointType == 2) return false
        val activated = when {
            node.has("is_activated") -> node.optBoolean("is_activated", false)
            node.has("isActivated") -> node.optBoolean("isActivated", false)
            else -> node.optInt("level", 0) > 0
        }
        return activated
    }

    private fun consBonus(value: Any?, rank: Int, step: Int): Int {
        if (step == 0 || value == null || value == JSONObject.NULL) return 0
        if (value is JSONArray) {
            var count = 0
            for (i in 0 until value.length()) if (rank >= value.optInt(i, Int.MAX_VALUE)) count++
            return count * step
        }
        val required = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: return 0
        return if (required > 0 && rank >= required) step else 0
    }

    private fun collect(container: JSONObject, keys: Array<String>, out: LinkedHashMap<Int, JSONObject>) {
        for (key in keys) {
            val array = container.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                val pid = pointId(node)
                if (pid <= 0) continue
                val previous = out[pid]
                if (previous == null || node.optInt("level", 0) > previous.optInt("level", 0)) out[pid] = node
            }
        }
    }

    fun pointId(node: JSONObject): Int {
        for (key in arrayOf("pointId", "point_id", "id", "skill_id")) {
            val value = node.opt(key) ?: continue
            val id = (value as? Number)?.toInt() ?: value.toString().toIntOrNull() ?: continue
            if (id > 0) return id
        }
        return 0
    }

    private fun toArray(nodes: Collection<JSONObject>): JSONArray =
        JSONArray().also { array -> nodes.forEach(array::put) }
}
