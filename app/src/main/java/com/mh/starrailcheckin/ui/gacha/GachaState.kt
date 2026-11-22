package com.mh.starrailcheckin.ui.gacha

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 抽卡统计纯状态层：卡池分类、筛选、保底水位、指标与汇总文本全部在此计算，不触碰 View / Compose。
 * 口径与旧 GachaShowcaseActivity 完全一致。
 */
internal class GachaStats(val records: List<JSONObject>) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // ---------------- 卡池分类 ----------------

    fun poolRecords(pool: Int): List<JSONObject> {
        val types = poolFlatTypes(pool)
        // records 为接口返回顺序(新→旧)；反转为旧→新即为真实抽卡顺序。
        return records.filter { types.contains(it.optInt("gachaType")) }.reversed()
    }

    /** 该卡池中按时间正序的五星列表 */
    fun fiveStars(pool: Int): List<JSONObject> =
        poolRecords(pool).filter { it.optInt("rank") >= 5 }

    fun isStandard(name: String?): Boolean =
        WarpPlanner.STANDARD_CHARS.contains(name?.trim())

    /** 当前某池距上一金之后已垫的抽数（旧→新顺序）。 */
    fun carryOf(pool: Int): Int {
        var cur = 0
        for (r in poolRecords(pool)) {
            if (r.optInt("rank") >= 5) cur = 0 else cur += 1
        }
        return cur
    }

    /** 角色池是否处于大保底：上一金为常驻（歪了）→ 下次五星必出 UP；无记录按小保底处理。 */
    fun charGuaranteed(): Boolean {
        val f = fiveStars(0)
        if (f.isEmpty()) return false
        return isStandard(f.last().optString("name"))
    }

    /** 角色池保底状态：上一金歪 → 大保底必 UP；未歪 → 小保底。 */
    fun charBannerState(): String {
        val f = fiveStars(0)
        if (f.isEmpty()) return "暂无角色出金记录"
        val last = f.last()
        return if (isStandard(last.optString("name"))) "大保底 · 下次小保底必出 UP"
        else "小保底 · 存在歪的可能"
    }

    // ---------------- 筛选 ----------------

    /** 记录时间是否落在当前选择的时间范围内（无时间字段的历史数据默认保留）。 */
    private fun withinTimeRange(r: JSONObject, timeFilter: Int): Boolean {
        if (timeFilter == 0) return true
        val ts = runCatching { timeFmt.parse(r.optString("time"))?.time ?: 0L }.getOrDefault(0L)
        if (ts <= 0L) return true
        val days = when (timeFilter) {
            1 -> 7L
            2 -> 30L
            else -> 90L
        }
        return ts >= System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
    }

    /** 筛选后的展示条目：垫值行 + 五星行 + 四星行。 */
    fun visibleItems(
        pool: Int,
        query: String,
        rankFilter: Int,
        timeFilter: Int
    ): List<VisibleItem> {
        val records = poolRecords(pool)
        val filtered = records.filter { r ->
            val name = r.optString("name", "")
            val rank = r.optInt("rank", 1)
            val matchSearch = query.isBlank() || name.contains(query, ignoreCase = true)
            val matchRank = when (rankFilter) {
                1 -> rank >= 5
                2 -> rank == 4
                else -> true
            }
            matchSearch && matchRank && withinTimeRange(r, timeFilter)
        }

        if (filtered.isEmpty()) return emptyList()

        val pityById = HashMap<String, Int>()
        var cur = 0
        for (r in records) {
            val rank = r.optInt("rank", 1)
            val name = r.optString("name", "")
            if (name.isBlank() || rank !in 3..5) continue
            cur += 1
            if (rank >= 5) {
                pityById[r.optString("id")] = cur
                cur = 0
            }
        }

        val items = mutableListOf<VisibleItem>()
        val plainFilter = query.isBlank() && rankFilter == 0 && timeFilter == 0
        if (cur > 0 && plainFilter) items.add(VisibleItem.Carry(cur))

        val displayList = if (!plainFilter) filtered.reversed()
        else filtered.filter { it.optInt("rank") >= 5 }.reversed()

        displayList.forEach { item ->
            if (item.optInt("rank") >= 5) {
                val pity = pityById[item.optString("id")] ?: 0
                items.add(VisibleItem.FiveStar(item, pity))
            } else {
                items.add(VisibleItem.FourStar(item))
            }
        }
        return items
    }

    // ---------------- 顶部四指标 ----------------

    fun metrics(): GachaMetric {
        val totalPulls = records.size

        val charFive = fiveStars(0)
        val notLose = charFive.count { !isStandard(it.optString("name")) }
        val noLoseRate = if (charFive.isEmpty()) "—"
        else "${notLose * 100 / charFive.size}%"

        val charPerUp = if (notLose > 0) {
            val charPulls = poolRecords(0).size
            fmt1(charPulls.toDouble() / notLose) + "抽"
        } else "—"

        val lcFive = fiveStars(1)
        val lcLimited = lcFive.size
        val lcPerUp = if (lcLimited > 0) {
            val lcPulls = poolRecords(1).size
            fmt1(lcPulls.toDouble() / lcLimited) + "抽"
        } else "—"

        return GachaMetric(noLoseRate, totalPulls.toString(), charPerUp, lcPerUp)
    }

    // ---------------- 保底详情 ----------------

    fun pityDetail(pool: Int): String {
        val records = poolRecords(pool)
        if (records.isEmpty()) return "${poolName(pool)}暂无抽卡记录"

        val cap = if (pool == 1) 80 else 90
        val fiveList = mutableListOf<Pair<JSONObject, Int>>()
        var cur = 0
        for (r in records) {
            if (r.optString("name").isBlank()) continue
            cur += 1
            if (r.optInt("rank") >= 5) {
                fiveList.add(r to cur)
                cur = 0
            }
        }
        val carry = cur
        val left = (cap - carry).coerceAtLeast(0)
        val avg = if (fiveList.isEmpty()) 0.0 else fiveList.sumOf { it.second }.toDouble() / fiveList.size
        val best = fiveList.minOfOrNull { it.second }
        val worst = fiveList.maxOfOrNull { it.second }

        return buildString {
            appendLine("${poolName(pool)}：已垫 $carry 抽 · 距五星还差 $left 抽（$cap 抽保底）")
            when (pool) {
                0 -> {
                    appendLine(charBannerState())
                    val lose = fiveStars(0).count { isStandard(it.optString("name")) }
                    if (fiveList.isNotEmpty()) {
                        appendLine("角色池出金 ${fiveList.size} 次 · 歪 $lose 次 · 未歪 ${fiveList.size - lose} 次")
                    }
                }
                1 -> {
                    val last = fiveStars(1).lastOrNull()
                    appendLine(
                        if (last == null) "光锥池暂无出金记录"
                        else "上次出金：${last.optString("name")}（75% 为 UP 光锥，歪了则下次必出 UP）"
                    )
                }
                else -> appendLine("常驻池无 UP 机制，仅按 90 抽五星保底计算")
            }
            if (fiveList.isNotEmpty()) {
                appendLine("平均 %.1f 抽/金 · 最欧 %d 抽 · 最非 %d 抽".format(Locale.CHINA, avg, best ?: 0, worst ?: 0))
                appendLine("最近出金：")
                fiveList.takeLast(5).reversed().forEach { (r, p) ->
                    val tag = if (pool == 0) {
                        if (isStandard(r.optString("name"))) " · 歪" else " · 未歪"
                    } else ""
                    appendLine("  ${r.optString("name")} · 第 $p 抽 · ${r.optString("time").take(10)}$tag")
                }
            } else {
                append("暂无出金记录，继续积累保底水位吧")
            }
        }.trimEnd()
    }

    // ---------------- 累计统计 ----------------

    fun summary(jade: Int, pass: Int, stdPass: Int): String {
        val totalPulls = records.size
        val totalFive = records.count { it.optInt("rank") >= 5 }
        val starJade = totalPulls * 160L
        val charFive = fiveStars(0)
        val charLose = charFive.count { isStandard(it.optString("name")) }
        val charWin = charFive.size - charLose
        val lcLimited = fiveStars(1).size
        val limitedFive = charWin + lcLimited
        val standardFive = fiveStars(2).size + charLose

        val charCarry = carryOf(0)
        val lcCarry = carryOf(1)
        val charLeft = (90 - charCarry).coerceAtLeast(0)
        val lcLeft = (80 - lcCarry).coerceAtLeast(0)
        val loseRate = if (charFive.isEmpty()) "—" else "${charLose * 100 / charFive.size}%"
        val fiveRate = if (totalPulls > 0) totalFive * 100.0 / totalPulls else 0.0
        val avgFivePity = if (totalFive > 0) totalPulls.toDouble() / totalFive else 0.0

        val ouLabel = when {
            totalFive == 0 -> "—"
            avgFivePity <= 55 -> "欧皇"
            avgFivePity <= 65 -> "小欧"
            avgFivePity <= 75 -> "亚洲"
            avgFivePity <= 85 -> "非酋"
            else -> "酋长"
        }

        fun poolLine(name: String, pool: Int): String {
            val pulls = poolRecords(pool).size
            val five = fiveStars(pool).size
            val rate = if (pulls > 0) five * 100.0 / pulls else 0.0
            return "  $name：$pulls 抽 · $five 金 · 出率 %.1f%%".format(Locale.CHINA, rate)
        }

        val upPulls = jade / 160 + pass
        val guaranteed = charGuaranteed()
        val charCap = 90
        val worstNeed = if (guaranteed) charLeft else charLeft + charCap
        val planConclusion = when {
            upPulls >= worstNeed -> "已足够拿下目标 UP（含最坏情况）"
            guaranteed -> "距稳拿 UP 还差 ${worstNeed - upPulls} 抽（下次出金即 UP）"
            upPulls >= charLeft ->
                "够搏小保底（出金即有 50% 拿下）；若歪，还需 ${worstNeed - upPulls} 抽才稳"
            else -> "距下一金还差 ${charLeft - upPulls} 抽，距最坏大保底还差 ${worstNeed - upPulls} 抽"
        }

        return buildString {
            appendLine("┌─ 累计统计")
            appendLine("  五星获取：$totalFive  位（限定 $limitedFive · 常驻 $standardFive）")
            appendLine("  五星出率：%.1f%%  平均 %.1f 抽/金  欧非：%s".format(Locale.CHINA, fiveRate, avgFivePity, ouLabel))
            appendLine("  历史星琼估算：$starJade（按 160/抽）")
            appendLine("├─ 卡池统计")
            appendLine(poolLine("角色池", 0))
            appendLine(poolLine("光锥池", 1))
            appendLine(poolLine("常驻池", 2))
            appendLine("├─ 保底水位")
            appendLine("  角色池：已垫 $charCarry 抽，距五星还差 $charLeft 抽（90 保底）")
            appendLine("  光锥池：已垫 $lcCarry 抽，距五星还差 $lcLeft 抽（80 保底）")
            appendLine("  角色保底：${charBannerState()}")
            appendLine("├─ 角色池歪率")
            appendLine("  UP $charWin · 歪 $charLose · 歪率 $loseRate")
            appendLine("├─ 攒抽规划")
            appendLine("  持有：星琼 $jade · 专票 $pass · 通票 $stdPass → UP 可用 $upPulls 抽（通票仅常驻池）")
            appendLine("  保底：${if (guaranteed) "大保底 · 下次五星必出 UP" else "小保底 · 50% 概率出 UP"}")
            appendLine("  拿下目标 UP：约 1~$worstNeed 抽${if (guaranteed) "（下次出金即 UP）" else "（小保底最省，歪一次后大保底）"}")
            append("  $planConclusion")
        }.trimEnd()
    }

    fun poolName(pool: Int): String = when (pool) {
        0 -> "角色池"; 1 -> "光锥池"; 2 -> "常驻池"; else -> "联动池"
    }

    companion object {
        fun poolOf(type: Int): Int = when {
            type == 11 -> 0
            type == 12 -> 1
            type == 1 -> 2
            else -> 3
        }

        fun poolFlatTypes(pool: Int): Set<Int> = when (pool) {
            0 -> setOf(11)
            1 -> setOf(12)
            2 -> setOf(1)
            else -> setOf(2)
        }

        private fun fmt1(v: Double): String =
            java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()
    }
}

/** 顶部四指标。 */
internal data class GachaMetric(
    val noLoseRate: String,
    val totalPulls: String,
    val charPerUp: String,
    val lcPerUp: String
)

/** 列表展示条目。 */
internal sealed interface VisibleItem {
    data class Carry(val carry: Int) : VisibleItem
    data class FiveStar(val item: JSONObject, val pity: Int) : VisibleItem
    data class FourStar(val item: JSONObject) : VisibleItem
}
