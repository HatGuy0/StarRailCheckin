package com.mh.starrailcheckin.ui.profile

/**
 * 遗器评分引擎（全套对齐 miao-plugin 喵喵规则 / 云崽Bot）
 *
 * 算法来源：yoimiya-kokomi/miao-plugin
 *  - models/artis/ArtisMark.js（评分核心）
 *  - models/artis/ArtisMarkCfg.js（权重配置）
 *  - resources/meta-sr/artifact/artis-mark.js（角色权重表）
 *  - resources/meta-sr/artifact/meta.js（词条基础值表）
 *
 * 评级阈值（getMarkClass）：
 *  <7 D, <14 C, <21 B, <28 A, <35 S, <42 SS, <49 SSS, <56 ACE, >=56 MAX
 * 总评 = 6件平均分后取评级
 */
object RelicScorer {

    // ===== 词条基础值表（对齐 miao meta.js）=====
    private val ATTR_VALUE = mapOf(
        "atk" to 4.32,        // 大攻击%
        "atkPlus" to 127.0/6, // 小攻击固定
        "def" to 5.4,         // 大防御%
        "defPlus" to 127.0/6, // 小防御固定
        "hp" to 4.32,         // 大生命%
        "hpPlus" to 254.0/6,  // 小生命固定
        "speed" to 2.6,
        "cpct" to 3.24,
        "cdmg" to 6.48,
        "recharge" to 3.11,
        "dmg" to 6.22,
        "heal" to 5.53,
        "stance" to 6.48,
        "effPct" to 4.32,
        "effDef" to 4.32
    )

    // 副词条池
    private val SUB_ATTRS = listOf(
        "atk","atkPlus","def","defPlus","hp","hpPlus",
        "speed","cpct","cdmg","effPct","effDef","stance"
    )

    // 各位置主词条候选（1=头 2=手 3=衣 4=鞋 5=球 6=绳）
    private val MAIN_ATTRS = mapOf(
        3 to listOf("atk","def","hp","cpct","cdmg","heal","effPct"),
        4 to listOf("atk","def","hp","speed"),
        5 to listOf("atk","def","hp","dmg"),
        6 to listOf("atk","def","hp","recharge","stance")
    )

    // Mihomo type → miao key
    private val TYPE_MAP = mapOf(
        "CriticalChanceBase" to "cpct",
        "CriticalDamageBase" to "cdmg",
        "SpeedDelta" to "speed",
        "AttackAddedRatio" to "atk",
        "AttackDelta" to "atkPlus",
        "HPAddedRatio" to "hp",
        "HpAddedRatio" to "hp",
        "HPDelta" to "hpPlus",
        "HpDelta" to "hpPlus",
        "DefenceAddedRatio" to "def",
        "DefenceDelta" to "defPlus",
        "StatusProbabilityBase" to "effPct",
        "StatusResistanceBase" to "effDef",
        "BreakDamageAddedRatioBase" to "stance",
        "SPChargeDelta" to "recharge",
        "HealRatioBase" to "heal",
        "PhysicalAddedRatio" to "dmg",
        "FireAddedRatio" to "dmg",
        "IceAddedRatio" to "dmg",
        "ThunderAddedRatio" to "dmg",
        "WindAddedRatio" to "dmg",
        "QuantumAddedRatio" to "dmg",
        "ImaginaryAddedRatio" to "dmg",
        "AllDamageAddedRatio" to "dmg"
    )

    // 百分比类词条（Mihomo 返回小数，需 ×100）
    private val PCT_ATTRS = setOf(
        "cpct","cdmg","atk","hp","def","effPct","effDef","stance","recharge","dmg","heal"
    )

    /** 默认权重（miao ArtisMarkCfg.js 第99行，sr 通用） */
    private val DEFAULT_WEIGHT = mapOf(
        "atk" to 75.0, "cpct" to 100.0, "cdmg" to 100.0,
        "dmg" to 100.0, "speed" to 100.0
    )

    /** 获取角色权重表（按角色名，来源 EnkaNameMap 内置快照 + 在线补缺 + 磁盘缓存） */
    private fun getWeight(charName: String): Map<String, Double> {
        val w = EnkaNameMap.relicWeightFor(charName) ?: return DEFAULT_WEIGHT
        val ret = mutableMapOf<String, Double>()
        val it = w.keys()
        while (it.hasNext()) {
            val k = it.next()
            ret[k] = w.optDouble(k, 0.0)
        }
        return ret
    }

    /** Mihomo 属性 type → 权重 key（供诊断层判断词条是否有效）。 */
    fun typeToKey(mihomoType: String): String? = TYPE_MAP[mihomoType]

    /** 角色权重表中权重 > 0 的词条 key 集合。 */
    fun weightedKeys(charName: String): Set<String> {
        val w = getWeight(charName)
        return w.filterValues { it > 0 }.keys
    }

    /** 该位置主词条是否为权重最高的候选之一（主词条最优判定）。 */
    fun mainMatchOptimal(charName: String, position: Int, mainType: String): Boolean {
        val candidates = MAIN_ATTRS[position] ?: return false
        val key = TYPE_MAP[mainType] ?: return false
        val weight = getWeight(charName)
        val best = candidates.mapNotNull { weight[it] }.maxOrNull() ?: return false
        val cur = weight[key] ?: return false
        return cur > 0 && cur >= best
    }

    /**
     * 计算 attrs map（对齐 miao ArtisMarkCfg.getCfg）
     * 对每个有权重的词条，计算 mark 和 fixWeight
     */
    private fun buildAttrs(
        weight: Map<String, Double>,
        baseHp: Double,
        baseAtk: Double,
        baseDef: Double
    ): Map<String, AttrInfo> {
        val ret = mutableMapOf<String, AttrInfo>()
        for ((key, baseValue) in ATTR_VALUE) {
            // 找到对应的权重 key
            val weightKey = when (key) {
                "atkPlus" -> "atk"
                "hpPlus" -> "hp"
                "defPlus" -> "def"
                else -> key
            }
            val w = weight[weightKey] ?: continue
            if (w <= 0) continue

            val info = AttrInfo()
            info.weight = w
            info.value = baseValue

            if (weightKey == key) {
                // 固定值类（speed/cpct/cdmg/...）或大百分比（atk%/hp%/def%）
                info.mark = w / baseValue
                info.fixWeight = w
            } else {
                // 小固定值类（atkPlus/hpPlus/defPlus）→ 归一化到大词条
                val plus = if (weightKey == "atk") 520.0 else 0.0
                val baseAttrVal = when (weightKey) {
                    "atk" -> baseAtk + plus
                    "hp" -> baseHp
                    "def" -> baseDef
                    else -> 1.0
                }
                info.mark = w / baseValue / baseAttrVal * 100
                info.fixWeight = w * baseValue / (ATTR_VALUE[weightKey] ?: baseValue) / baseAttrVal * 100
            }
            ret[key] = info
        }
        return ret
    }

    data class AttrInfo(
        var weight: Double = 0.0,
        var value: Double = 0.0,
        var mark: Double = 0.0,
        var fixWeight: Double = 0.0
    )

    /** 计算各位置理论最高分 posMaxMark */
    private fun getMaxMark(attrs: Map<String, AttrInfo>): Map<Int, Double> {
        val ret = mutableMapOf<Int, Double>()
        for (idx in 1..6) {
            var totalMark = 0.0
            var mAttr = ""
            if (idx == 1) {
                mAttr = "hpPlus"
            } else if (idx == 2) {
                mAttr = "atkPlus"
            } else {
                // 选权重最高的主词条
                val candidates = MAIN_ATTRS[idx] ?: continue
                val sorted = candidates.filter { attrs.containsKey(it) }
                    .sortedByDescending { attrs[it]?.fixWeight ?: 0.0 }
                if (sorted.isNotEmpty()) {
                    mAttr = sorted[0]
                    val mMark = attrs[mAttr]?.fixWeight ?: 0.0
                    totalMark += mMark * 2
                } else {
                    mAttr = candidates[0]
                }
            }

            // 选权重最高的4个副词条（排除主词条）
            val subSorted = SUB_ATTRS.filter { it != mAttr && attrs.containsKey(it) }
                .sortedByDescending { attrs[it]?.fixWeight ?: 0.0 }
                .take(4)
            subSorted.forEachIndexed { i, attr ->
                val fw = attrs[attr]?.fixWeight ?: 0.0
                totalMark += fw * if (i == 0) 6 else 1
            }
            ret[idx] = totalMark
        }
        return ret
    }

    /** 评级（对齐 miao getMarkClass） */
    fun scoreToGrade(score: Double): String {
        return when {
            score < 7 -> "D"
            score < 14 -> "C"
            score < 21 -> "B"
            score < 28 -> "A"
            score < 35 -> "S"
            score < 42 -> "SS"
            score < 49 -> "SSS"
            score < 56 -> "ACE"
            else -> "MAX"
        }
    }

    /**
     * 计算单件遗器评分（对齐 miao getMark）
     *
     * @param charName 角色名（中文）
     * @param position 位置 1-6（1头 2手 3衣 4鞋 5球 6绳）
     * @param mainType Mihomo 主词条 type（如 AttackAddedRatio）
     * @param mainValue Mihomo 主词条数值
     * @param subList 副词条列表，每项 Pair(mihomoType, value)
     * @param baseHp 角色基础生命
     * @param baseAtk 角色基础攻击
     * @param baseDef 角色基础防御
     * @return 单件评分（0-70）
     */
    fun scoreRelic(
        charName: String,
        position: Int,
        mainType: String,
        mainValue: Double,
        subList: List<Pair<String, Double>>,
        baseHp: Double,
        baseAtk: Double,
        baseDef: Double
    ): Double {
        val weight = getWeight(charName)
        val attrs = buildAttrs(weight, baseHp, baseAtk, baseDef)
        if (attrs.isEmpty()) return 0.0

        val posMax = getMaxMark(attrs)
        var pMax = posMax[position] ?: return 0.0
        var ret = 0.0
        var fixPct = 1.0

        val mKey = TYPE_MAP[mainType] ?: ""

        if (position >= 3 && mKey.isNotEmpty()) {
            // 主词条分（位置3-6）
            if (mKey != "recharge" && attrs.containsKey(mKey)) {
                val mMax = MAIN_ATTRS[position]?.filter { attrs.containsKey(it) }
                    ?.maxOfOrNull { attrs[it]?.fixWeight ?: 0.0 } ?: 0.0
                fixPct = if (mMax > 0) {
                    maxOf(0.0, minOf(1.0, (attrs[mKey]?.weight ?: 0.0) / mMax))
                } else 1.0
            }
            val mVal = if (mKey in PCT_ATTRS) mainValue * 100 else mainValue
            ret += (attrs[mKey]?.mark ?: 0.0) * mVal / 4

            // 调整 pMax（如果主词条不是最优）
            val mainMax = getMainMaxMark(attrs, mKey)
            pMax = maxOf(pMax, mainMax)
        }

        // 副词条分
        for ((type, value) in subList) {
            val key = TYPE_MAP[type] ?: continue
            if (!attrs.containsKey(key)) continue
            val v = if (key in PCT_ATTRS) value * 100 else value
            ret += (attrs[key]?.mark ?: 0.0) * v
        }

        if (pMax <= 0) return 0.0
        val score = ret * (1 + fixPct) / 2 / pMax * 66
        return minOf(score, 70.0)
    }

    /** 获取指定主词条下该位置的裸分上限 */
    private fun getMainMaxMark(attrs: Map<String, AttrInfo>, mainKey: String): Double {
        var totalMark = (attrs[mainKey]?.fixWeight ?: 0.0) * 2
        val subSorted = SUB_ATTRS.filter { it != mainKey && attrs.containsKey(it) }
            .sortedByDescending { attrs[it]?.fixWeight ?: 0.0 }
            .take(4)
        subSorted.forEachIndexed { i, attr ->
            totalMark += attrs[attr]?.fixWeight ?: 0.0 * if (i == 0) 6.0 else 1.0
        }
        return totalMark
    }

    /**
     * 计算歪次数（对齐 miao 逻辑：权重=0的词条被强化的次数）
     * @param subList 副词条列表，每项 Triple(mihomoType, value, count)
     */
    fun countFail(
        charName: String,
        subList: List<Triple<String, Double, Int>>
    ): Int {
        val weight = getWeight(charName)
        var fail = 0
        for ((type, _, cnt) in subList) {
            val key = TYPE_MAP[type] ?: continue
            val weightKey = when (key) {
                "atkPlus" -> "atk"
                "hpPlus" -> "hp"
                "defPlus" -> "def"
                else -> key
            }
            val w = weight[weightKey] ?: 0.0
            if (w <= 0 && cnt > 1) {
                fail += (cnt - 1)
            }
        }
        return fail
    }

    /** 兼容性：旧接口保留（不再使用） */
    fun scoreByType(type: String, value: Double, avatarId: Int): Double = 0.0
    fun isValidType(type: String, avatarId: Int): Boolean = true
    fun classify(avatarId: Int): String = "unknown"
    fun normalize(type: String): String = type
    fun label(key: String): String = key
    fun getMaxScore(avatarId: Int): Double = 100.0
}
