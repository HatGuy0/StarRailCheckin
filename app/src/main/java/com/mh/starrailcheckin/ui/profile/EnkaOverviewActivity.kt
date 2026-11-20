package com.mh.starrailcheckin.ui.profile

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.util.LogHelper
import org.json.JSONObject

/**
 * 练度总览 + 遗器仓库（方向一 / 方向二）。
 * 复用橱窗 raw_json 全量数据，跨角色聚合练度梯队，并平铺全部遗器供筛选排序。
 * 评分口径与 EnkaShowcaseActivity 一致（RelicScorer，miao 规则）。
 */
class EnkaOverviewActivity : LightBaseActivity() {

    private lateinit var rootJson: JSONObject
    private val charList = mutableListOf<JSONObject>()

    private var currentTab = 0 // 0=练度总览 1=遗器仓库
    private var relicFilterType = 0 // 0=全部 1..6=部位
    private var sortMode = 0 // 0=评分 1=双暴 2=等级

    private val summaries = mutableListOf<CharSummary>()
    private val relicEntries = mutableListOf<RelicEntry>()

    // 配色（与单角色面板一致）
    private val bgDeep = 0xFFF0EDF7.toInt()
    private val textWhite = 0xFFEDEBFA.toInt()
    private val textGray = 0xFFA39DBA.toInt()
    private val accentGold = 0xFFF5B041.toInt()
    private val accentGreen = 0xFF2ECC71.toInt()
    private val accentRed = 0xFFE74C3C.toInt()
    private val accentBlue = 0xFF5DADE2.toInt()
    private val primaryColor = 0xFF7B6CF6.toInt()

    private data class CharSummary(
        val aid: Int, val name: String, val level: Int, val rank: Int,
        val element: String, val path: String,
        val hasLc: Boolean, val lcName: String, val lcLevel: Int, val lcRank: Int,
        val relicCount: Int, val avgScore: Double, val grade: String,
        val skillMax: Int, val skillCount: Int, val tier: String,
        val raw: JSONObject
    )

    private data class RelicEntry(
        val charName: String, val charAid: Int,
        val relic: JSONObject, val score: Double, val grade: String, val fail: Int,
        val setID: Int, val type: Int, val setName: String,
        val critSum: Double, val level: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContentView(R.layout.activity_enka_overview)

        val raw = intent.getStringExtra("raw_json").orEmpty()
        if (raw.isBlank()) { finish(); return }
        rootJson = try { JSONObject(raw) } catch (e: Exception) {
            Toast.makeText(this, "面板数据已损坏，无法查看", Toast.LENGTH_SHORT).show(); finish(); return
        }

        EnkaNameMap.load(applicationContext)

        val chars = rootJson.optJSONObject("detailInfo")?.optJSONArray("avatarDetailList")
        if (chars == null || chars.length() == 0) {
            findViewById<TextView>(R.id.tvOverviewPlayer).text = "该玩家未展示角色"
            return
        }
        for (i in 0 until chars.length()) chars.optJSONObject(i)?.let { charList.add(it) }

        buildSummaries()
        buildRelicEntries()
        renderPlayer()
        renderTabs()
        switchTab(0)

        findViewById<TextView>(R.id.btnOverviewBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnOverviewSort).setOnClickListener { cycleSort() }
    }

    // ---------------- 数据聚合 ----------------

    private fun baseAttrsOf(c: JSONObject): Triple<Double, Double, Double> {
        var hp = 1200.0; var atk = 600.0; var def = 500.0
        c.optJSONArray("_baseAttrs")?.let {
            for (i in 0 until it.length()) {
                val a = it.optJSONObject(i) ?: continue
                when (a.optString("field")) {
                    "hp" -> hp = a.optDouble("value", hp)
                    "atk" -> atk = a.optDouble("value", atk)
                    "def" -> def = a.optDouble("value", def)
                }
            }
        }
        return Triple(hp, atk, def)
    }

    private fun relicCells(c: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        c.optJSONArray("relicList")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun scoreOne(
        c: JSONObject, r: JSONObject, base: Triple<Double, Double, Double>
    ): Pair<Double, List<Pair<String, Double>>> {
        val charName = c.optString("name", "").ifBlank { EnkaNameMap.charName(c.optInt("avatarId", 0)) }
        val position = r.optInt("type", 1)
        val props = r.optJSONObject("_flat")?.optJSONArray("props")
        val mainType = props?.optJSONObject(0)?.optString("type", "") ?: ""
        val mainValue = props?.optJSONObject(0)?.optDouble("value", 0.0) ?: 0.0
        val subs = mutableListOf<Pair<String, Double>>()
        if (props != null) for (j in 1 until props.length()) {
            val p = props.optJSONObject(j) ?: continue
            subs.add(Pair(p.optString("type", ""), p.optDouble("value", 0.0)))
        }
        val score = RelicScorer.scoreRelic(
            charName, position, mainType, mainValue, subs,
            base.first, base.second, base.third
        )
        return Pair(score, subs)
    }

    private fun buildSummaries() {
        summaries.clear()
        for (c in charList) {
            val aid = c.optInt("avatarId", 0)
            val name = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
            val level = c.optInt("level", 0)
            val rank = c.optInt("rank", 0)
            val element = c.optString("elementName", "")
            val path = c.optString("pathName", "")

            val eq = c.optJSONObject("equipment")
            val hasLc = eq != null && eq.optInt("tid", 0) != 0
            val lcName = if (hasLc) EnkaNameMap.lcName(eq!!.optInt("tid", 0)) else "未装备光锥"
            val lcLevel = eq?.optInt("level", 0) ?: 0
            val lcRank = eq?.optInt("rank", 0) ?: 0

            val base = baseAttrsOf(c)
            val cells = relicCells(c)
            var total = 0.0
            for (r in cells) total += scoreOne(c, r, base).first
            val avg = if (cells.isNotEmpty()) total / cells.size else 0.0
            val grade = RelicScorer.scoreToGrade(avg)

            var skillMax = 0; var skillCount = 0
            c.optJSONArray("skillsDetail")?.let { sk ->
                skillCount = sk.length()
                for (i in 0 until sk.length()) {
                    val lv = sk.optJSONObject(i)?.optInt("level", 1) ?: 1
                    if (lv > skillMax) skillMax = lv
                }
            }

            val tier = when {
                level < 60 || !hasLc || cells.isEmpty() -> "待养成"
                level >= 70 && hasLc && avg >= 28.0 -> "成型"
                else -> "培养中"
            }
            summaries.add(CharSummary(
                aid, name, level, rank, element, path,
                hasLc, lcName, lcLevel, lcRank,
                cells.size, avg, grade, skillMax, skillCount, tier, c
            ))
        }
        val tierOrder = mapOf("成型" to 0, "培养中" to 1, "待养成" to 2)
        summaries.sortWith(compareBy({ tierOrder[it.tier] ?: 3 }, { -it.avgScore }, { -it.level }))
    }

    private fun buildRelicEntries() {
        relicEntries.clear()
        for (c in charList) {
            val aid = c.optInt("avatarId", 0)
            val name = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
            val base = baseAttrsOf(c)
            for (r in relicCells(c)) {
                val (score, subs) = scoreOne(c, r, base)
                val failList = mutableListOf<Triple<String, Double, Int>>()
                val subsArr = r.optJSONArray("subAffixList")
                subs.forEachIndexed { idx, (t, v) ->
                    val cnt = subsArr?.optJSONObject(idx)?.optInt("cnt", 0) ?: 0
                    failList.add(Triple(t, v, cnt))
                }
                val fail = RelicScorer.countFail(name, failList)
                val setID = r.optJSONObject("_flat")?.optInt("setID", 0) ?: 0
                val setName = r.optString("setName", "").ifBlank { EnkaNameMap.relicSetName(setID) }
                var crit = 0.0
                for ((t, v) in subs) when (t) {
                    "CriticalChanceBase", "CriticalChance" -> crit += v * 100
                    "CriticalDamageBase", "CriticalDamage" -> crit += v * 100
                }
                relicEntries.add(RelicEntry(
                    name, aid, r, score, RelicScorer.scoreToGrade(score), fail,
                    setID, r.optInt("type", 0), setName, crit, r.optInt("level", 0)
                ))
            }
        }
    }

    // ---------------- 渲染 ----------------

    private fun renderPlayer() {
        val detail = rootJson.optJSONObject("detailInfo")
        val nick = detail?.optString("nickname", "未知") ?: "未知"
        val lvl = detail?.optInt("level", 0) ?: 0
        val uid = detail?.optString("uid", "") ?: rootJson.optString("uid", "")
        findViewById<TextView>(R.id.tvOverviewPlayer).text = "$nick  Lv.$lvl  UID:$uid  · 共 ${charList.size} 名角色"
    }

    private fun renderTabs() {
        val row = findViewById<LinearLayout>(R.id.overviewTabRow)
        row.removeAllViews()
        val titles = listOf("练度总览", "遗器仓库(${relicEntries.size})")
        titles.forEachIndexed { idx, title ->
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener { switchTab(idx) }
                tag = idx
            }
            row.addView(tv)
        }
        paintTabs()
    }

    private fun paintTabs() {
        val row = findViewById<LinearLayout>(R.id.overviewTabRow)
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as TextView
            val selected = (tv.tag as Int) == currentTab
            tv.setTextColor(if (selected) ColorWhite else textGray)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(if (selected) primaryColor else 0x00000000)
            }
        }
    }

    private fun switchTab(idx: Int) {
        currentTab = idx
        paintTabs()
        findViewById<LinearLayout>(R.id.overviewFilterRow).visibility =
            if (idx == 1) View.VISIBLE else View.GONE
        val container = findViewById<LinearLayout>(R.id.overviewContainer)
        container.removeAllViews()
        if (idx == 0) renderOverview(container) else renderRelicRepo()
    }

    // ---------------- 练度总览 ----------------

    private fun renderOverview(container: LinearLayout) {
        val done = summaries.count { it.tier == "成型" }
        val growing = summaries.count { it.tier == "培养中" }
        val wait = summaries.count { it.tier == "待养成" }

        // 汇总卡
        val summaryCard = card()
        val sumRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        sumRow.addView(metricCol("${summaries.size}", "角色总数", textWhite))
        sumRow.addView(metricCol("$done", "成型", accentGreen))
        sumRow.addView(metricCol("$growing", "培养中", accentBlue))
        sumRow.addView(metricCol("$wait", "待养成", accentGold))
        summaryCard.addView(sumRow)
        container.addView(summaryCard)

        if (summaries.isEmpty()) {
            container.addView(emptyTip("暂无角色数据"))
            return
        }

        summaries.forEach { s ->
            container.addView(buildCharCard(s))
        }
    }

    private fun metricCol(value: String, label: String, color: Int): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = value; setTextColor(color); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = label; setTextColor(textGray); textSize = 11f; gravity = Gravity.CENTER
        })
        return col
    }

    private fun buildCharCard(s: CharSummary): View {
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val avatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(bgDeep)
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
        }
        EnkaImageLoader.loadAvatar(this, s.aid, avatar)
        row.addView(avatar)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(TextView(this).apply {
            text = s.name; setTextColor(textWhite); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1
        })
        nameRow.addView(TextView(this).apply {
            text = "  LV.${s.level}"; setTextColor(accentGold); textSize = 11f
        })
        col.addView(nameRow)

        val elemPath = listOf(s.element, s.path).filter { it.isNotBlank() }.joinToString(" · ")
        if (elemPath.isNotBlank()) col.addView(TextView(this).apply {
            text = elemPath; setTextColor(textGray); textSize = 11f
        })

        val lcText = if (s.hasLc) "${s.lcName} Lv.${s.lcLevel} 叠影${s.lcRank}" else "未装备光锥"
        col.addView(TextView(this).apply {
            text = lcText; setTextColor(textGray); textSize = 11f; maxLines = 1
        })

        val relicText = if (s.relicCount > 0)
            "遗器 ${fmt1(s.avgScore)} · ${s.grade}（${s.relicCount}件）"
        else "未穿戴遗器"
        col.addView(TextView(this).apply {
            text = relicText; setTextColor(gradeColor(s.grade)); textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        row.addView(col)

        // 右侧档位 + 技能
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tierColor = when (s.tier) {
            "成型" -> accentGreen; "培养中" -> accentBlue; else -> accentGold
        }
        right.addView(TextView(this).apply {
            text = s.tier; setTextColor(ColorWhite); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE; cornerRadius = dp(10).toFloat(); setColor(tierColor)
            }
        })
        if (s.skillCount > 0) right.addView(TextView(this).apply {
            text = "技能最高 ${s.skillMax} 级"; setTextColor(textGray); textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })
        row.addView(right)

        card.addView(row)
        return card
    }

    // ---------------- 遗器仓库 ----------------

    private fun renderRelicRepo() {
        val container = findViewById<LinearLayout>(R.id.overviewContainer) ?: return
        try {
            container.removeAllViews()
            renderFilterChips()
            val filtered = relicEntries.filter { relicFilterType == 0 || it.type == relicFilterType }
            val sorted = when (sortMode) {
                1 -> filtered.sortedByDescending { it.critSum }
                2 -> filtered.sortedByDescending { it.level }
                else -> filtered.sortedByDescending { it.score }
            }

            // 重复件统计：同 setID + type
            val dupKeys = filtered.groupingBy { "${it.setID}_${it.type}" }.eachCount().filter { it.value > 1 }.keys

            if (sorted.isEmpty()) {
                container.addView(emptyTip("该部位暂无遗器"))
                return
            }
            for (e in sorted) {
                runCatching {
                    container.addView(buildRelicCard(e, dupKeys.contains("${e.setID}_${e.type}")))
                }.onFailure { LogHelper.e("Enka", "单件遗器卡片渲染失败", it) }
            }
        } catch (t: Throwable) {
            LogHelper.e("Enka", "遗器仓库渲染失败", t)
            container.removeAllViews()
            container.addView(emptyTip("仓库渲染异常：${t.javaClass.simpleName}（已记录日志）"))
        }
    }

    private fun renderFilterChips() {
        val chips = findViewById<LinearLayout>(R.id.overviewFilterChips) ?: return
        chips.removeAllViews()
        val labels = listOf("全部", "头", "手", "衣", "鞋", "球", "绳")
        labels.forEachIndexed { idx, label ->
            val tv = TextView(this).apply {
                text = label; textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { relicFilterType = idx; renderRelicRepo() }
                tag = idx
            }
            paintChip(tv, relicFilterType == idx)
            chips.addView(tv)
        }
    }

    private fun cycleSort() {
        sortMode = (sortMode + 1) % 3
        val label = when (sortMode) { 1 -> "排序:双暴"; 2 -> "排序:等级"; else -> "排序:评分" }
        findViewById<TextView>(R.id.btnOverviewSort)?.text = label
        renderRelicRepo()
    }

    private fun buildRelicCard(e: RelicEntry, duplicated: Boolean): View {
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        EnkaImageLoader.loadRelic(this, e.setID, e.type, icon)
        row.addView(icon)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val topLine = listOf(partName(e.type), e.setName.ifBlank { "遗器${e.setID}" }).filter { it.isNotBlank() }.joinToString(" · ")
        col.addView(TextView(this).apply {
            text = topLine; setTextColor(textWhite); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = "${e.charName}  ·  +${e.level}"; setTextColor(textGray); textSize = 11f
        })

        // 主词条
        val flat = e.relic.optJSONObject("_flat")
        val props = flat?.optJSONArray("props")
        val main = props?.optJSONObject(0)
        if (main != null) {
            col.addView(TextView(this).apply {
                text = "主词条 ${propLabel(main.optString("type", ""))}  ${propValueText(main)}"
                setTextColor(accentBlue); textSize = 11f
            })
        }

        // 副词条逐行（名称 + 强化次数标记 + 数值）
        val subsArr = e.relic.optJSONArray("subAffixList")
        if (props != null) {
            for (j in 1 until props.length()) {
                val p = props.optJSONObject(j) ?: continue
                val ptype = p.optString("type", "")
                if (ptype.isBlank()) continue
                val cnt = subsArr?.optJSONObject(j - 1)?.optInt("cnt", 0) ?: 0
                val valid = RelicScorer.isValidType(ptype, e.charAid)

                val subRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                subRow.addView(TextView(this).apply {
                    text = propLabel(ptype)
                    setTextColor(textGray); textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                subRow.addView(TextView(this).apply {
                    text = if (cnt >= 3) ">>" else ">"
                    setTextColor(if (cnt >= 3) accentGold else textGray); textSize = 9f
                })
                subRow.addView(TextView(this).apply {
                    text = "+${propValueText(p)}"
                    setTextColor(if (valid) accentGreen else textGray); textSize = 10f
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                col.addView(subRow)
            }
        }

        val badges = mutableListOf<String>()
        badges.add("${fmt1(e.score)} ${e.grade}")
        if (e.fail > 0) badges.add("歪${e.fail}次")
        if (duplicated) badges.add("重复")
        col.addView(TextView(this).apply {
            text = badges.joinToString("  ·  ")
            setTextColor(gradeColor(e.grade)); textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        })

        row.addView(col)
        card.addView(row)
        return card
    }

    // ---------------- 公共组件 / 工具 ----------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(14).toFloat(); setColor(0x14FFFFFF)
        }
    }

    private fun emptyTip(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(textGray); textSize = 13f
        setPadding(dp(8), dp(16), dp(8), dp(16)); gravity = Gravity.CENTER
    }

    private fun paintChip(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) ColorWhite else textGray)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp(12).toFloat()
            setColor(if (selected) primaryColor else 0x14FFFFFF)
        }
    }

    private fun gradeColor(grade: String): Int = when (grade) {
        "ACE", "MAX" -> 0xFFF1C40F.toInt()
        "SSS", "SS" -> accentRed
        "S" -> accentGold
        "A" -> accentBlue
        "B" -> accentGreen
        else -> textGray
    }

    private fun partName(type: Int): String = when (type) {
        1 -> "头部"; 2 -> "手部"; 3 -> "躯干"; 4 -> "脚部"; 5 -> "位面球"; 6 -> "连结绳"; else -> "部位$type"
    }

    private fun propLabel(type: String): String = when (type) {
        "BaseHP", "HPDelta", "HPAddedRatio" -> "生命值"
        "BaseAttack", "AttackDelta", "AttackAddedRatio" -> "攻击力"
        "BaseDefence", "DefenceDelta", "DefenceAddedRatio" -> "防御力"
        "Speed", "SpeedDelta" -> "速度"
        "CriticalChance", "CriticalChanceBase" -> "暴击率"
        "CriticalDamage", "CriticalDamageBase" -> "暴击伤害"
        "StatusProbability", "StatusProbabilityBase" -> "效果命中"
        "StatusResist", "StatusResistanceBase" -> "效果抵抗"
        "BreakDamageAddedRatio", "BreakDamageAddedRatioBase" -> "击破特攻"
        "PhysicalAddedRatio" -> "物理伤害加成"
        "FireAddedRatio" -> "火属性伤害加成"
        "IceAddedRatio" -> "冰属性伤害加成"
        "ThunderAddedRatio" -> "雷属性伤害加成"
        "WindAddedRatio" -> "风属性伤害加成"
        "QuantumAddedRatio" -> "量子属性伤害加成"
        "ImaginaryAddedRatio" -> "虚数属性伤害加成"
        else -> type
    }

    private fun propValueText(p: JSONObject): String {
        val disp = p.optString("display", "")
        if (disp.isNotBlank()) return disp
        val t = p.optString("type", "")
        val v = p.optDouble("value", 0.0)
        val isPct = t.endsWith("Ratio") || t == "CriticalChance" || t == "CriticalDamage" ||
            t == "StatusProbability" || t == "StatusResist" || t == "BreakDamageAddedRatio"
        return when {
            isPct -> "${fmt1(v * 100)}%"
            t.endsWith("Delta") || t.startsWith("Base") || t == "Speed" -> v.toInt().toString()
            v < 1.0 -> "${fmt1(v * 100)}%"
            else -> v.toInt().toString()
        }
    }

    private fun fmt1(v: Double): String =
        java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val ColorWhite = 0xFFFFFFFF.toInt()
}
