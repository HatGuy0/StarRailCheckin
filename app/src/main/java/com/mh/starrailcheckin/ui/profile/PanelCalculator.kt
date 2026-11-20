package com.mh.starrailcheckin.ui.profile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 星铁常驻面板计算器（参考 Fribbels/云崽通用公式）
 * 公式：最终属性 = (角色白值 + 光锥白值) × (1 + 全部百分比加成) + 全部固定值加成
 * 百分比来源：行迹常驻属性 + 遗器百分比词条 + 套装效果
 * 固定值来源：遗器固定词条
 */
object PanelCalculator {

    data class Result(
        val hp: Int, val atk: Int, val def: Int, val spd: Int,
        val critChance: Double, val critDamage: Double,
        val effectHit: Double, val effectRes: Double,
        val breakDmg: Double, val elemDmg: Double,
        val elation: Double
    )

    fun calculate(c: JSONObject): Result {
        val aid = c.optInt("avatarId", 0)
        val promotion = c.optInt("promotion", 0)
        val level = c.optInt("level", 80)

        // 1. 角色白值
        val charBase = EnkaNameMap.charBase(aid, promotion, level)
        val charHp = charBase?.getOrNull(0) ?: 0.0
        val charAtk = charBase?.getOrNull(1) ?: 0.0
        val charDef = charBase?.getOrNull(2) ?: 0.0
        val charSpd = charBase?.getOrNull(3) ?: 0.0

        // 2. 光锥白值（仅静态BaseHP/BaseAttack/BaseDefence，不含叠影特效）
        var lcHp = 0.0; var lcAtk = 0.0; var lcDef = 0.0
        val eqProps = c.optJSONObject("equipment")?.optJSONObject("_flat")?.optJSONArray("props")
        if (eqProps != null) {
            for (i in 0 until eqProps.length()) {
                val p = eqProps.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "BaseHP" -> lcHp = p.optDouble("value", 0.0)
                    "BaseAttack" -> lcAtk = p.optDouble("value", 0.0)
                    "BaseDefence" -> lcDef = p.optDouble("value", 0.0)
                }
            }
        }

        // 3. 百分比加成池 + 固定值加成池
        val pct = linkedMapOf<String, Double>()
        val flat = linkedMapOf<String, Double>()
        // 3a. 行迹常驻属性
        val stl = c.optJSONArray("skillTreeList")
        if (stl != null) {
            for (i in 0 until stl.length()) {
                val node = stl.optJSONObject(i) ?: continue
                val pid = node.optInt("pointId", 0)
                val lv = node.optInt("level", 0)
                val props = EnkaNameMap.skillTreeProps(pid, lv) ?: continue
                for (j in 0 until props.length()) {
                    val p = props.optJSONObject(j) ?: continue
                    val t = p.optString("type", "")
                    val v = p.optDouble("value", 0.0)
                    if (t.endsWith("Delta")) flat[t] = (flat[t] ?: 0.0) + v
                    else pct[t] = (pct[t] ?: 0.0) + v
                }
            }
        }
        // 3b. 遗器全部词条
        val relicList = c.optJSONArray("relicList")
        if (relicList != null) {
            for (i in 0 until relicList.length()) {
                val r = relicList.optJSONObject(i) ?: continue
                val props = r.optJSONObject("_flat")?.optJSONArray("props") ?: continue
                for (j in 0 until props.length()) {
                    val p = props.optJSONObject(j) ?: continue
                    val t = p.optString("type", "")
                    val v = p.optDouble("value", 0.0)
                    when {
                        t.endsWith("Delta") -> flat[t] = (flat[t] ?: 0.0) + v
                        else -> pct[t] = (pct[t] ?: 0.0) + v
                    }
                }
            }
        }
        // 3c. 遗器套装常驻属性（2件套/4件套效果，直接进站街面板）
        val relicSetCount = mutableMapOf<Int, Int>()
        if (relicList != null) {
            for (i in 0 until relicList.length()) {
                val r = relicList.optJSONObject(i) ?: continue
                val sid = r.optJSONObject("_flat")?.optInt("setID", 0) ?: 0
                if (sid > 0) relicSetCount[sid] = (relicSetCount[sid] ?: 0) + 1
            }
        }
        for ((sid, count) in relicSetCount) {
            val setProps = EnkaNameMap.relicSetProps(sid, count) ?: continue
            for (j in 0 until setProps.length()) {
                val p = setProps.optJSONObject(j) ?: continue
                val t = p.optString("type", "")
                val v = p.optDouble("value", 0.0)
                if (t.endsWith("Delta")) flat[t] = (flat[t] ?: 0.0) + v
                else pct[t] = (pct[t] ?: 0.0) + v
            }
        }
        // 3d. 光锥常驻被动属性（装备后直接进站街面板的属性）
        val eqTid = c.optJSONObject("equipment")?.optInt("tid", 0) ?: 0
        val eqRank = c.optJSONObject("equipment")?.optInt("rank", 1) ?: 1
        val lcProps = EnkaNameMap.lightConeProps(eqTid, eqRank)
        if (lcProps != null) {
            for (i in 0 until lcProps.length()) {
                val p = lcProps.optJSONObject(i) ?: continue
                val t = p.optString("type", "")
                val v = p.optDouble("value", 0.0)
                if (t.endsWith("Delta")) flat[t] = (flat[t] ?: 0.0) + v
                else pct[t] = (pct[t] ?: 0.0) + v
            }
        }

        // 4. 最终面板
        val hpPct = 1.0 + (pct["HPAddedRatio"] ?: 0.0)
        val atkPct = 1.0 + (pct["AttackAddedRatio"] ?: 0.0)
        val defPct = 1.0 + (pct["DefenceAddedRatio"] ?: 0.0)
        val finalHp = (charHp + lcHp) * hpPct + (flat["HPDelta"] ?: 0.0)
        val finalAtk = (charAtk + lcAtk) * atkPct + (flat["AttackDelta"] ?: 0.0)
        val finalDef = (charDef + lcDef) * defPct + (flat["DefenceDelta"] ?: 0.0)
        val finalSpd = charSpd * (1.0 + (pct["SpeedAddedRatio"] ?: 0.0)) + (flat["SpeedDelta"] ?: 0.0)
        val critChance = 0.05 + (pct["CriticalChance"] ?: 0.0) + (pct["CriticalChanceBase"] ?: 0.0)
        // 欢愉度：单独显示的属性（不直接换算成暴伤）
        val elation = (pct["ElationDamageAddedRatioBase"] ?: 0.0) + (pct["ElationDamageAddedRatio"] ?: 0.0)
        val critDamage = 0.50 + (pct["CriticalDamage"] ?: 0.0) + (pct["CriticalDamageBase"] ?: 0.0)
        val effectHit = pct["StatusProbability"] ?: 0.0
        val effectRes = (pct["StatusResist"] ?: 0.0) + (pct["StatusResistance"] ?: 0.0)
        val breakDmg = pct["BreakDamageAddedRatio"] ?: 0.0
        val elemDmg = (pct["PhysicalAddedRatio"] ?: 0.0) + (pct["FireAddedRatio"] ?: 0.0) +
                (pct["IceAddedRatio"] ?: 0.0) + (pct["ThunderAddedRatio"] ?: 0.0) +
                (pct["WindAddedRatio"] ?: 0.0) + (pct["QuantumAddedRatio"] ?: 0.0) +
                (pct["ImaginaryAddedRatio"] ?: 0.0)

        return Result(
            hp = finalHp.toInt(), atk = finalAtk.toInt(), def = finalDef.toInt(),
            spd = finalSpd.toInt(), critChance = critChance, critDamage = critDamage,
            effectHit = effectHit, effectRes = effectRes, breakDmg = breakDmg, elemDmg = elemDmg,
            elation = elation
        )
    }
}
