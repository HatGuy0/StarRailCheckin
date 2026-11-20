package com.mh.starrailcheckin.ui.profile

/**
 * 欢愉（Elation）伤害公式，完整还原 miao-plugin models/dmg/DmgCalc.js 的 elation 分支。
 *
 * 与普通攻击伤害的本质区别：欢愉伤害【不吃 ATK】，吃固定等级基底 elationBaseDmg[level]，
 * 再乘技能倍率、笑点曲线、欢愉度、增笑、暴击、防御、抗性、减伤、易伤。
 *
 * dmg = elationBase × 倍率 × merrymakes × joy × punchlineCals × (1+cdmg) × defNum × kNum × 0.9 × enemydmg
 * avg = 同上，暴击项换成 (1 + cpct×cdmg)
 */
object ElationDamage {

    /** miao DmgCalcMeta.elationBaseDmg，1~80 级固定基底（游戏常量，不随版本变）。 */
    private val ELATION_BASE = doubleArrayOf(
        108.0, 116.0, 124.0, 135.05, 141.02, 147.05, 153.11, 159.28,
        165.48, 171.74, 182.99, 194.14, 205.12, 216.12, 226.95, 237.68,
        248.3, 258.82, 269.23, 279.54, 298.66, 317.6, 336.34, 354.92,
        373.3, 391.49, 409.5, 427.32, 444.95, 462.4, 492.86, 522.36,
        550.95, 578.64, 605.45, 631.43, 656.58, 680.94, 704.51, 727.33,
        816.25, 903.58, 989.36, 1073.64, 1156.45, 1237.83, 1317.83, 1396.47,
        1473.78, 1549.8, 1742.12, 1929.74, 2112.84, 2291.58, 2466.12, 2636.59,
        2803.15, 2965.92, 3125.04, 3280.61, 3504.64, 3723.8, 3938.25, 4148.13,
        4353.6, 4554.78, 4751.82, 4944.83, 5133.95, 5319.28, 5560.61, 5797.2,
        6029.21, 6256.76, 6479.95, 6698.95, 6913.85, 7124.77, 7331.82, 7535.11
    )

    fun baseOf(level: Int): Double = ELATION_BASE[(level - 1).coerceIn(0, 79)]

    /** 笑点曲线：1 + punchline×5/(punchline+240)。100 好活 → 2.4706；20 笑点 → 1.3846。 */
    fun punchlineCals(punchline: Double): Double =
        1.0 + punchline * 5.0 / (punchline + 240.0)

    /**
     * miao 版防御乘区（星铁）：
     * defNum = (200+lvl×10) / ((200+lvl×10) + (200+enemyLv×10)×(1-defDown-ignore))
     * 与 miao DmgCalc 的星铁防御乘区一致，集中在此统一调用。
     */
    fun defNum(level: Int, enemyLv: Int = 80, defDown: Double = 0.0, ignore: Double = 0.0): Double {
        val defAll = (defDown + ignore).coerceAtMost(1.0)
        val atkSide = 200.0 + level * 10.0
        return atkSide / (atkSide + (200.0 + enemyLv * 10.0) * (1.0 - defAll))
    }

    /** 抗性乘区：kNum = 1 + kx/100，kx = 抗性穿透 − 敌人抗性（百分数）。弱点 0 抗性 → kNum=1。 */
    fun kNum(resPenMinusRes: Double = 0.0): Double = 1.0 + resPenMinusRes / 100.0

    /** 欢愉伤害输入。 */
    data class Input(
        val level: Int,               // 角色等级（决定基底）
        val multiplier: Double,       // 技能倍率（miao data.json tables 命名行，已是小数）
        val punchline: Double = 100.0,// 本次攻击计入的笑点/好活当赏
        val joy: Double = 0.0,        // 欢愉度（面板 12% → 0.12）
        val merrymakes: Double = 0.0, // 增笑（1 魂 15% → 0.15）
        val critRate: Double,         // 已含 buff 的暴击率（小数）
        val critDmg: Double,          // 已含 buff 的暴击伤害（小数）
        val defDown: Double = 0.0,    // 减防
        val defIgnore: Double = 0.0,  // 无视防御（欢愉万相 30%）
        val kx: Double = 0.0,         // 抗性穿透 − 敌人抗性（百分数，弱点=0）
        val enemyDmg: Double = 0.0,   // 易伤（光锥"受到欢愉伤害提高6%"→0.06）
        val enemyLv: Int = 80,
        val broken: Boolean = false   // 已破韧 → 减伤区 1.0，否则 0.9
    )

    data class Result(val crit: Double, val avg: Double)

    fun calc(i: Input): Result {
        val base = baseOf(i.level)
        val joyNum = 1.0 + i.joy
        val merryNum = 1.0 + i.merrymakes
        val punch = punchlineCals(i.punchline)
        val def = defNum(i.level, i.enemyLv, i.defDown, i.defIgnore)
        val k = kNum(i.kx)
        val reduce = if (i.broken) 1.0 else 0.9
        val enemy = 1.0 + i.enemyDmg
        val cpct = i.critRate.coerceIn(0.0, 1.0)
        val cdmg = if (cpct == 0.0) 0.0 else i.critDmg
        val common = base * i.multiplier * merryNum * joyNum * punch * def * k * reduce * enemy
        return Result(
            crit = common * (1.0 + cdmg),
            avg = common * (1.0 + cpct * cdmg)
        )
    }
}
