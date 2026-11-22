package com.mh.starrailcheckin.ui.gacha

import java.util.Random

/**
 * 跃迁保底概率规划（纯本地计算，不联网）。
 *
 * 规则口径（公开概率规则的近似估算，非官方保证）：
 *  - 角色活动跃迁：90 抽五星保底，小保底 50%  UP；歪出常驻后下一个五星必 UP（大保底）。
 *  - 光锥活动跃迁：80 抽五星保底，75% UP；歪后下一个五星必 UP。
 *  - 五星基础概率 0.6%，73 抽后进入软保底递增，保底抽数必出。
 *
 * 达成概率用固定种子的蒙特卡洛模拟估算；期望抽数 / 最坏保底抽数走解析递推。
 */
object WarpPlanner {

    const val POOL_CHAR = 0
    const val POOL_LC = 1

    /** 常驻五星角色名单（活动池出金：出常驻=歪，出限定 UP=未歪）。新版本常驻加入后在此补全。 */
    val STANDARD_CHARS = setOf(
        "彦卿", "白露", "杰帕德", "布洛妮娅", "瓦尔特", "克拉拉", "姬子", "符玄"
    )

    private const val CHAR_CAP = 90
    private const val LC_CAP = 80
    private const val BASE_RATE = 0.006
    private const val SOFT_PITY_START = 73
    private const val SOFT_PITY_STEP = 0.06

    /** 每金历史平均抽数（仅用于期望参考，概率不依赖该值）。 */
    private const val CHAR_AVG_PITY = 65.0
    private const val LC_AVG_PITY = 65.0

    private const val SIMULATION_ROUNDS = 20000
    private const val SEED = 20261122L

    data class PlanRow(
        val label: String,
        val targetUp: Int,
        val probability: Double,   // 0..1
        val expectedPulls: Int,
        val worstPulls: Int,
        val gapWorst: Int          // 最坏情况下还差几抽（<=0 表示已足够）
    )

    data class PlanResult(
        val pool: Int,
        val carry: Int,
        val guaranteed: Boolean,
        val pulls: Int,
        val rows: List<PlanRow>
    )

    private fun capOf(pool: Int) = if (pool == POOL_LC) LC_CAP else CHAR_CAP
    private fun upRateOf(pool: Int) = if (pool == POOL_LC) 0.75 else 0.50
    private fun avgPityOf(pool: Int) = if (pool == POOL_LC) LC_AVG_PITY else CHAR_AVG_PITY

    private fun fiveStarRate(pity: Int): Double {
        if (pity < SOFT_PITY_START) return BASE_RATE
        return minOf(1.0, BASE_RATE + (pity - SOFT_PITY_START + 1) * SOFT_PITY_STEP)
    }

    /**
     * 规划入口。
     *
     * @param pool POOL_CHAR / POOL_LC
     * @param carry 当前距上一金已垫抽数
     * @param guaranteed 是否处于大保底（下一五星必 UP）
     * @param pulls 当前可用于该池的抽数（星琼÷160 + 专属票）
     * @param targets 目标 UP 个数列表（如角色 [1,2,3]，光锥 [1,2,3,5]）
     */
    fun plan(pool: Int, carry: Int, guaranteed: Boolean, pulls: Int, targets: List<Int>): PlanResult {
        val cap = capOf(pool)
        val upRate = upRateOf(pool)
        val avgPity = avgPityOf(pool)
        val safeCarry = carry.coerceIn(0, cap - 1)

        val rows = targets.map { target ->
            val prob = simulateSuccess(cap, upRate, guaranteed, safeCarry, target, pulls)
            val (expGold, worstGold) = goldEstimate(guaranteed, upRate, target)
            val expected = (expGold * avgPity).toInt().coerceAtLeast(0)
            val worst = worstGold * cap
            PlanRow(
                label = labelFor(pool, target),
                targetUp = target,
                probability = prob,
                expectedPulls = expected,
                worstPulls = worst,
                gapWorst = (worst - pulls).coerceAtLeast(0)
            )
        }
        return PlanResult(pool, safeCarry, guaranteed, pulls, rows)
    }

    private fun labelFor(pool: Int, target: Int): String {
        if (pool == POOL_LC) return when (target) {
            1 -> "S1 · 1 把专武"
            2 -> "S2 · 2 把专武"
            3 -> "S3 · 3 把专武"
            5 -> "S5 · 5 把专武"
            else -> "S$target · $target 把专武"
        }
        return when (target) {
            1 -> "E0 · 1 个 UP"
            2 -> "E1 · 2 个 UP"
            3 -> "E2 · 3 个 UP"
            4 -> "E3 · 4 个 UP"
            5 -> "E4 · 5 个 UP"
            6 -> "E5 · 6 个 UP"
            7 -> "E6 · 7 个 UP"
            else -> "E${target - 1} · $target 个 UP"
        }
    }

    /** 期望金数 / 最坏金数（解析递推）。 */
    private fun goldEstimate(guaranteed: Boolean, upRate: Double, target: Int): Pair<Double, Int> {
        // 小保底视角：每拿 1 个 UP 期望 1/upRate 金（歪一次后下金必 UP）。
        val expectPerUp = 1.0 / upRate
        val expGold: Double
        val worstGold: Int
        if (guaranteed) {
            expGold = 1.0 + (target - 1) * expectPerUp
            worstGold = 1 + (target - 1) * 2
        } else {
            expGold = target * expectPerUp
            worstGold = target * 2
        }
        return expGold to worstGold
    }

    /** 蒙特卡洛估算达成目标 UP 个数的概率。 */
    private fun simulateSuccess(
        cap: Int,
        upRate: Double,
        startGuaranteed: Boolean,
        startPity: Int,
        targetUp: Int,
        pulls: Int
    ): Double {
        if (pulls <= 0) return 0.0
        val rng = Random(SEED)
        var success = 0
        repeat(SIMULATION_ROUNDS) {
            var guaranteed = startGuaranteed
            var up = 0
            var pity = startPity
            var spent = 0
            while (spent < pulls && up < targetUp) {
                pity++
                spent++
                if (pity >= cap || rng.nextDouble() < fiveStarRate(pity)) {
                    val isUp = guaranteed || rng.nextDouble() < upRate
                    pity = 0
                    if (isUp) {
                        up++
                        guaranteed = false
                    } else {
                        guaranteed = true
                    }
                }
            }
            if (up >= targetUp) success++
        }
        return success.toDouble() / SIMULATION_ROUNDS
    }
}
