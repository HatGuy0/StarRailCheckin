package com.mh.starrailcheckin.ui.profile

/**
 * 遗器单件词条诊断（对齐 RelicScorer 权重口径，纯本地计算）。
 *
 * 输出供卡片 UI 呈现：
 *  - 有效词条（角色权重 > 0）/ 无效词条（权重 0）标识
 *  - 歪条占比（无效词条强化次数 / 总强化次数）
 *  - 副词条 roll 档位（按等效基础词条个数近似分高 / 中 / 低）
 *  - 主词条是否为该位置最优候选
 *
 * 结论为配装参考，非官方评价。
 */
object RelicInsight {

    /** roll 档位：0 低 / 1 中 / 2 高。 */
    const val ROLL_LOW = 0
    const val ROLL_MID = 1
    const val ROLL_HIGH = 2

    data class SubInsight(
        val index: Int,
        val weighted: Boolean,
        val rollTier: Int
    )

    data class Insight(
        val subInsights: List<SubInsight>,
        val effectiveCount: Int,
        val failRatio: Double,
        val mainOptimal: Boolean?
    )

    internal fun inspect(
        charName: String,
        position: Int,
        main: Prop?,
        subs: List<SubUi>,
        failCount: Int
    ): Insight {
        val weighted = RelicScorer.weightedKeys(charName)
        val insights = subs.mapIndexed { i, s ->
            val key = RelicScorer.typeToKey(s.prop.type)
            val isWeighted = key != null && weighted.contains(key)
            val tier = when {
                s.countValue >= 1.2 -> ROLL_HIGH
                s.countValue >= 0.8 -> ROLL_MID
                else -> ROLL_LOW
            }
            SubInsight(i, isWeighted, tier)
        }
        val effective = insights.count { it.weighted }
        val totalTimes = subs.sumOf { it.times.coerceAtLeast(1) }
        val failRatio = if (totalTimes > 0) failCount.toDouble() / totalTimes else 0.0
        val mainOptimal = main?.type?.let {
            if (position in 3..6) RelicScorer.mainMatchOptimal(charName, position, it) else null
        }
        return Insight(insights, effective, failRatio, mainOptimal)
    }
}
