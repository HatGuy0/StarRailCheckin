package com.mh.starrailcheckin.ui.profile

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.R
import org.json.JSONObject

class EnkaShowcaseActivity : LightBaseActivity() {

    private lateinit var rootJson: JSONObject
    private var selectedIndex = 0
    private var avatarContainers = mutableListOf<FrameLayout>()
    private var charList = mutableListOf<JSONObject>()

    // 深色星空底适配：卡片透明，文字用浅色；bgDeep 仅作头像/图标衬底
    private val bgCard = 0x00000000
    private val bgDeep = 0xFFF0EDF7.toInt()
    private val textWhite = 0xFFEDEBFA.toInt()
    private val textGray = 0xFFA39DBA.toInt()
    private val textLight = 0xFFA39DBA.toInt()
    private val accentGold = 0xFFF5B041.toInt()
    private val accentGreen = 0xFF2ECC71.toInt()
    private val accentRed = 0xFFE74C3C.toInt()
    private val accentBlue = 0xFF5DADE2.toInt()
    private val primaryColor = 0xFF7B6CF6.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 深色星空背景：状态栏图标用浅色
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContentView(R.layout.activity_enka_showcase)
        // 二级页内容下移避开系统状态栏（仅作用于内容层，不挤压背景层）
        findViewById<android.view.View>(R.id.enkaShowcaseScroll)?.let { root ->
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
                val t = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(v.paddingLeft, t, v.paddingRight, v.paddingBottom)
                ins
            }
        }

        val raw = intent.getStringExtra("raw_json").orEmpty()
        if (raw.isBlank()) { finish(); return }
        rootJson = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "角色面板数据已损坏，无法查看", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        EnkaNameMap.load(applicationContext)

        findViewById<android.widget.LinearLayout>(R.id.btnOpenOverview)?.setOnClickListener {
            val intent = android.content.Intent(this, EnkaOverviewActivity::class.java)
            intent.putExtra("raw_json", rootJson.toString())
            startActivity(intent)
        }

        val detail = rootJson.optJSONObject("detailInfo")
        val chars = detail?.optJSONArray("avatarDetailList")
        if (chars == null || chars.length() == 0) {
            findViewById<TextView>(R.id.tvPlayerInfo).text = "该玩家未展示角色"
            return
        }
        charList.clear()
        for (i in 0 until chars.length()) {
            chars.optJSONObject(i)?.let { charList.add(it) }
        }

        renderPlayer()
        renderCharRow()
        showChar(0)
    }

    private fun renderPlayer() {
        val detail = rootJson.optJSONObject("detailInfo") ?: return
        val nick = detail.optString("nickname", "未知")
        val lvl = detail.optInt("level", 0)
        val uid = rootJson.optString("uid", "")
        findViewById<TextView>(R.id.tvPlayerInfo).text = "$nick  Lv.$lvl  UID:$uid"
    }

    /** 顶部头像栏：圆形头像 */
    private fun renderCharRow() {
        val row = findViewById<LinearLayout>(R.id.charAvatarRow)
        row.removeAllViews()
        avatarContainers.clear()

        charList.forEachIndexed { idx, c ->
            val aid = c.optInt("avatarId", 0)
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(8) }
            }
            val img = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgDeep)
                }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                setOnClickListener {
                    selectedIndex = idx
                    showChar(idx)
                    updateSelection()
                }
            }
            frame.addView(img)
            row.addView(frame)
            avatarContainers.add(frame)
            EnkaImageLoader.loadAvatar(this, aid, img)
        }
        updateSelection()
    }

    private fun updateSelection() {
        avatarContainers.forEachIndexed { idx, frame ->
            val img = frame.getChildAt(0) as ImageView
            if (idx == selectedIndex) {
                img.scaleX = 1.1f
                img.scaleY = 1.1f
                img.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgDeep)
                    setStroke(dp(3), accentGold)
                }
            } else {
                img.scaleX = 1f
                img.scaleY = 1f
                img.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgDeep)
                }
            }
        }
    }

    private fun showChar(index: Int) {
        val c = charList.getOrNull(index) ?: return
        val aid = c.optInt("avatarId", 0)
        val lvl = c.optInt("level", 0)
        val rank = c.optInt("rank", 0)

        // 角色名 + 等级徽章
        findViewById<TextView>(R.id.tvCharName).text = EnkaNameMap.charName(aid)
        findViewById<TextView>(R.id.tvCharLevelBadge).text = "LV.$lvl"
        findViewById<TextView>(R.id.tvCharUID).text = "UID:${rootJson.optString("uid","")}"

        // 元素 + 命途
        val elemName = c.optString("elementName", "")
        val pathName = c.optString("pathName", "")
        findViewById<TextView>(R.id.tvElement).text = elemName
        findViewById<TextView>(R.id.tvPath).text = pathName

        // 立绘大图
        val portrait = findViewById<ImageView>(R.id.ivCharPortrait)
        portrait.scaleType = ImageView.ScaleType.CENTER_CROP
        EnkaImageLoader.loadPortrait(this, aid, portrait)

        // 属性面板（statistics + 基础白值差值 = 绿色加成）
        renderPanelStats(c)

        // 技能栏（5个技能图标 + 等级）
        renderSkillsRow(c)

        // 光锥
        val eq = c.optJSONObject("equipment")
        val tvLc = findViewById<TextView>(R.id.tvLightCone)
        val tvLcName = findViewById<TextView>(R.id.tvLightConeName)
        val ivLc = findViewById<ImageView>(R.id.ivLightCone)
        if (eq != null) {
            val tid = eq.optInt("tid", 0)
            tvLcName.text = EnkaNameMap.lcName(tid)
            tvLc.text = "Lv.${eq.optInt("level", 0)}  叠影${eq.optInt("rank", 0)}阶"
            EnkaImageLoader.loadLightCone(this, tid, ivLc)
        } else {
            tvLcName.text = "未装备光锥"
            tvLc.text = ""
            ivLc.setImageBitmap(null)
        }

        // 遗器评分 + 评级
        val relicList = c.optJSONArray("relicList")
        val kind = RelicScorer.classify(aid)
        val cells = mutableListOf<JSONObject>()
        if (relicList != null) {
            for (i in 0 until relicList.length()) relicList.optJSONObject(i)?.let { cells.add(it) }
        }

        // 遗器评分（miao-plugin 喵喵规则）
        val charName = c.optString("name", "").ifBlank { EnkaNameMap.charName(aid) }
        val baseAttrs = c.optJSONArray("_baseAttrs")
        var baseHp = 1200.0; var baseAtk = 600.0; var baseDef = 500.0
        baseAttrs?.let {
            for (i in 0 until it.length()) {
                val a = it.optJSONObject(i) ?: continue
                when (a.optString("field")) {
                    "hp" -> baseHp = a.optDouble("value", 1200.0)
                    "atk" -> baseAtk = a.optDouble("value", 600.0)
                    "def" -> baseDef = a.optDouble("value", 500.0)
                }
            }
        }

        // 统计总分（miao 算法：每件独立打分后累加，评级用平均分）
        var totalScore = 0.0
        for (r in cells) {
            val position = r.optInt("type", 1)
            val props = r.optJSONObject("_flat")?.optJSONArray("props")
            val subs = r.optJSONArray("subAffixList")
            val mainType = props?.optJSONObject(0)?.optString("type", "") ?: ""
            val mainValue = props?.optJSONObject(0)?.optDouble("value", 0.0) ?: 0.0
            val subList = mutableListOf<Pair<String, Double>>()
            if (props != null) {
                for (j in 1 until props.length()) {
                    val p = props.optJSONObject(j) ?: continue
                    subList.add(Pair(p.optString("type", ""), p.optDouble("value", 0.0)))
                }
            }
            totalScore += RelicScorer.scoreRelic(
                charName, position, mainType, mainValue, subList,
                baseHp, baseAtk, baseDef
            )
        }

        // 总评分显示累加总分，评级用平均分（对齐 miao）
        val avgScore = if (cells.isNotEmpty()) totalScore / cells.size else 0.0
        findViewById<TextView>(R.id.tvRelicScore).text = fmt1(totalScore)
        val grade = RelicScorer.scoreToGrade(avgScore)
        findViewById<TextView>(R.id.tvRelicGrade).text = grade

        // 遗器网格（2行3列）
        val container = findViewById<LinearLayout>(R.id.relicContainer)
        container.removeAllViews()
        if (cells.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "  该角色未穿戴遗器"
                setTextColor(textGray)
                textSize = 12f
            })
        } else {
            cells.chunked(3).forEach { rowCells ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                }
                rowCells.forEach { r ->
                    row.addView(buildRelicCell(r, aid,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                        charName, baseHp, baseAtk, baseDef))
                }
                container.addView(row)
            }
        }
    }

    /** 属性面板：每行 图标+名称+最终值+绿色加成 */
    private fun renderPanelStats(c: JSONObject) {
        val container = findViewById<LinearLayout>(R.id.panelStats)
        container.removeAllViews()

        val stats = c.optJSONArray("_stats") ?: return
        val baseAttrs = c.optJSONArray("_baseAttrs")
        // 建立基础白值 map（field → value）
        val baseMap = mutableMapOf<String, Double>()
        if (baseAttrs != null) {
            for (i in 0 until baseAttrs.length()) {
                val a = baseAttrs.optJSONObject(i) ?: continue
                baseMap[a.optString("field", "")] = a.optDouble("value", 0.0)
            }
        }
        // 上游给出的遗器/光锥绿色增量（field → 词条）
        val addMap = mutableMapOf<String, JSONObject>()
        c.optJSONArray("_additions")?.let { adds ->
            for (i in 0 until adds.length()) {
                val a = adds.optJSONObject(i) ?: continue
                addMap[a.optString("field", "")] = a
            }
        }

        val flatFields = listOf("hp", "atk", "def", "spd")
        for (i in 0 until stats.length()) {
            val s = stats.optJSONObject(i) ?: continue
            val field = s.optString("field", "")
            val label = statLabel(field, s.optString("name", field))
            val disp = s.optString("display", "")
            val baseVal = baseMap[field] ?: 0.0
            val percent = s.optBoolean("percent", false)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 属性名（左侧）
            row.addView(TextView(this).apply {
                text = label
                setTextColor(textLight)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            })

            // 右侧：上行最终总数值，下行 基础白值 + 绿色遗器增量
            val rightBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rightBox.addView(TextView(this).apply {
                text = disp
                setTextColor(textWhite)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
            })
            val bottomRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            // 基础白值仅固定值词条展示（暴击/爆伤等百分比词条不显示白值列）
            if (field in flatFields && baseVal > 0.0001) {
                val baseText = if (percent) "${fmt1(baseVal * 100)}%" else baseVal.toInt().toString()
                bottomRow.addView(TextView(this).apply {
                    text = baseText
                    setTextColor(textGray)
                    textSize = 10f
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                })
            }
            // 绿色遗器增量：仅生命/攻击/防御/速度四固定值词条展示，且只采用上游 additions（遗器/套装贡献）
            val addEntry = addMap[field]
            val addDisplay = addEntry?.optString("display", "")?.trim().orEmpty()
            if (field in flatFields && addDisplay.isNotBlank()) {
                bottomRow.addView(TextView(this).apply {
                    text = "+$addDisplay"
                    setTextColor(accentGreen)
                    textSize = 10f
                    gravity = Gravity.END
                })
            }
            rightBox.addView(bottomRow)
            row.addView(rightBox)

            container.addView(row)
        }
    }

    /** 技能栏：5个技能图标 + 等级 */
    private fun renderSkillsRow(c: JSONObject) {
        val row = findViewById<LinearLayout>(R.id.skillsRow)
        row.removeAllViews()

        val skillsDetail = c.optJSONArray("skillsDetail") ?: return
        // 按 type 排序：Normal(普攻) / BPSkill(战技) / Ultra(终结技) / Talent(天赋) / Maze(秘技)
        // 非主技能节点已在数据层过滤，这里只做排序，不再补入未命中节点。
        val ordered = mutableListOf<JSONObject>()
        val typeOrder = listOf("Normal", "BPSkill", "Ultra", "Talent", "Maze")
        for (t in typeOrder) {
            for (i in 0 until skillsDetail.length()) {
                val s = skillsDetail.optJSONObject(i) ?: continue
                if (s.optString("type", "") == t) {
                    ordered.add(s)
                    break
                }
            }
        }

        ordered.forEach { s ->
            // 每列：固定宽度容器，所有元素水平居中对齐
            val skCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 技能图标（固定 46dp，圆角方框背景保证尺寸一致）
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(bgDeep)
                }
                clipToOutline = true
            }
            EnkaImageLoader.loadMihomoIcon(this, s.optString("icon", ""), iv)
            skCol.addView(iv)

            // 等级（固定 14sp，水平居中）
            skCol.addView(TextView(this).apply {
                text = s.optInt("level", 1).toString()
                setTextColor(textWhite)
                textSize = 14f
                gravity = Gravity.CENTER_HORIZONTAL
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })

            // 类型文字（固定 11sp，水平居中，单行）
            skCol.addView(TextView(this).apply {
                text = s.optString("typeText", "")
                setTextColor(textGray)
                textSize = 11f
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })

            row.addView(skCol)
        }
    }

    /** Mihomo statistics.field → 中文标签 */
    private fun statLabel(field: String, fallback: String): String = when (field) {
        "hp" -> "生命值"
        "atk" -> "攻击力"
        "def" -> "防御力"
        "spd" -> "速度"
        "crit_rate" -> "暴击率"
        "crit_dmg" -> "暴击伤害"
        "break_dmg" -> "击破特攻"
        "effect_hit" -> "效果命中"
        "effect_res" -> "效果抵抗"
        "elation_dmg" -> "欢愉度"
        "heal_rate" -> "治疗加成"
        "energy_regen" -> "能量恢复效率"
        "sp_rate" -> "施法充能效率"
        "physical_dmg" -> "物理伤害加成"
        "fire_dmg" -> "火属性伤害加成"
        "ice_dmg" -> "冰属性伤害加成"
        "thunder_dmg" -> "雷属性伤害加成"
        "wind_dmg" -> "风属性伤害加成"
        "quantum_dmg" -> "量子属性伤害加成"
        "imaginary_dmg" -> "虚数加伤"
        else -> fallback
    }

    /** 单件遗器评级（阈值表） */
    private fun scoreToGradeSingle(score: Double): String = when {
        score >= 45 -> "ACE"
        score >= 40 -> "SSS"
        score >= 35 -> "SS"
        score >= 30 -> "S"
        score >= 25 -> "A"
        score >= 20 -> "B"
        else -> "C"
    }

    /** 6件遗器总评评级（阈值表） */
    private fun scoreToGradeTotal(score: Double): String = when {
        score >= 260 -> "ACE"
        score >= 220 -> "SSS"
        score >= 180 -> "SS"
        score >= 150 -> "S"
        score >= 120 -> "A"
        score >= 100 -> "B"
        else -> "C"
    }

    /** 分数 → 评价 */
    private fun scoreToEval(score: Double, avatarId: Int): String {
        val maxScore = RelicScorer.getMaxScore(avatarId)
        val ratio = if (maxScore > 0) score / maxScore else 0.0
        return when {
            ratio >= 0.95 -> "大毕业+"
            ratio >= 0.8 -> "大毕业"
            ratio >= 0.6 -> "小毕业"
            ratio >= 0.4 -> "可用"
            else -> "过渡"
        }
    }

    /** 精美遗器卡片（对齐参考图） */
    private fun buildRelicCell(r: JSONObject, avatarId: Int, lp: LinearLayout.LayoutParams,
                               charName: String, baseHp: Double, baseAtk: Double, baseDef: Double): View {
        val type = r.optInt("type", 0)
        val rlvl = r.optInt("level", 0)
        val rarity = r.optInt("rarity", 5)
        val flat = r.optJSONObject("_flat")
        val setID = flat?.optInt("setID", 0) ?: 0
        val props = flat?.optJSONArray("props")
        val subs = r.optJSONArray("subAffixList")

        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = lp.apply { marginEnd = dp(6) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(bgCard)
            }
        }

        // 上部行：+15标签 + 主词条名称 + 数值
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // +15 标签（左上角）
        topRow.addView(TextView(this).apply {
            text = "+$rlvl"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(accentRed)
            }
            setPadding(dp(4), dp(1), dp(4), dp(1))
        })

        // 主词条名称
        val mainProp = props?.optJSONObject(0)
        topRow.addView(TextView(this).apply {
            text = propLabel(mainProp?.optString("type", "") ?: "")
            setTextColor(textLight)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        })

        // 主词条数值（右上角大字号）
        topRow.addView(TextView(this).apply {
            text = mainProp?.let { propValueText(it) } ?: ""
            setTextColor(textWhite)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        })

        cell.addView(topRow)

        // 中部行：遗器图标 + 星级 + 歪次
        val midRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        // 遗器图标
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        EnkaImageLoader.loadRelic(this, setID, type, icon)
        midRow.addView(icon)

        // 右侧：星星 + 歪次
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }

        // 星星（5星=5颗橙星）
        rightCol.addView(TextView(this).apply {
            text = "★".repeat(rarity)
            setTextColor(accentGold)
            textSize = 10f
        })

        // 评分（miao-plugin 喵喵规则）+ 歪次数
        val position = r.optInt("type", 1)
        val mainType = props?.optJSONObject(0)?.optString("type", "") ?: ""
        val mainValue = props?.optJSONObject(0)?.optDouble("value", 0.0) ?: 0.0
        val subList = mutableListOf<Pair<String, Double>>()
        val subFailList = mutableListOf<Triple<String, Double, Int>>()
        if (props != null && subs != null) {
            for (j in 1 until props.length()) {
                val p = props.optJSONObject(j) ?: continue
                val ptype = p.optString("type", "")
                val pval = p.optDouble("value", 0.0)
                val cnt = subs.optJSONObject(j - 1)?.optInt("cnt", 0) ?: 0
                subList.add(Pair(ptype, pval))
                subFailList.add(Triple(ptype, pval, cnt))
            }
        }
        val relicScore = RelicScorer.scoreRelic(
            charName, position, mainType, mainValue, subList,
            baseHp, baseAtk, baseDef
        )
        val failCount = RelicScorer.countFail(charName, subFailList)
        rightCol.addView(TextView(this).apply {
            text = if (failCount > 0) "歪${failCount}次" else ""
            setTextColor(accentRed)
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })

        midRow.addView(rightCol)
        cell.addView(midRow)

        val grade = RelicScorer.scoreToGrade(relicScore)
        cell.addView(TextView(this).apply {
            text = "${fmt1(relicScore)} - $grade"
            setTextColor(when (grade) {
                "ACE", "MAX" -> 0xFFF1C40F.toInt()
                "SSS", "SS" -> accentRed
                "S" -> accentGold
                "A" -> accentBlue
                "B" -> accentGreen
                else -> textGray
            })
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        // 分隔线
        val divider = View(this).apply {
            setBackgroundColor(0x1FFFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        cell.addView(divider)

        // 副词条列表
        if (props != null && subs != null) {
            for (j in 1 until props.length()) {
                val p = props.optJSONObject(j) ?: continue
                val ptype = p.optString("type", "")
                val cnt = subs.optJSONObject(j - 1)?.optInt("cnt", 0) ?: 0
                val valid = RelicScorer.isValidType(ptype, avatarId)

                val subRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                // 副词条名称
                subRow.addView(TextView(this).apply {
                    text = propLabel(ptype)
                    setTextColor(textGray)
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                // 箭头标记（>>表示高roll）
                subRow.addView(TextView(this).apply {
                    text = if (cnt >= 3) ">>" else ">"
                    setTextColor(if (cnt >= 3) accentGold else textGray)
                    textSize = 9f
                })

                // 数值
                subRow.addView(TextView(this).apply {
                    text = "+${propValueText(p)}"
                    setTextColor(if (valid) accentGreen else textLight)
                    textSize = 10f
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                cell.addView(subRow)
            }
        }

        return cell
    }

    /** 词条数值展示：优先用接口给的 display */
    private fun propValueText(p: JSONObject): String {
        val disp = p.optString("display", "")
        if (disp.isNotBlank()) return disp
        val t = p.optString("type", "")
        return formatPropValue(t, p.optDouble("value", 0.0))
    }

    private fun fmt1(v: Double): String =
        java.math.BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()

    private fun propLabel(type: String): String = when (type) {
        "BaseHP", "HPDelta", "HPAddedRatio" -> "生命值"
        "BaseAttack", "AttackDelta", "AttackAddedRatio" -> "攻击力"
        "BaseDefence", "DefenceDelta", "DefenceAddedRatio" -> "防御力"
        "Speed", "SpeedDelta" -> "速度"
        "CriticalChance", "CriticalChanceBase" -> "暴击率"
        "CriticalDamage", "CriticalDamageBase" -> "暴击伤害"
        "StatusProbability", "StatusProbabilityBase" -> "效果命中"
        "StatusResist", "StatusResistance" -> "效果抵抗"
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

    private fun formatPropValue(type: String, value: Double): String {
        val isPct = type.endsWith("Ratio") || type == "CriticalChance" || type == "CriticalDamage" ||
                    type == "StatusProbability" || type == "StatusResist" || type == "BreakDamageAddedRatio"
        return when {
            isPct -> {
                val pct = java.math.BigDecimal.valueOf(value * 100)
                    .setScale(1, java.math.RoundingMode.HALF_EVEN)
                    .toPlainString()
                "${pct}%"
            }
            type.endsWith("Delta") || type.startsWith("Base") || type == "Speed" -> value.toInt().toString()
            value < 1.0 -> {
                val pct = java.math.BigDecimal.valueOf(value * 100)
                    .setScale(1, java.math.RoundingMode.HALF_EVEN)
                    .toPlainString()
                "${pct}%"
            }
            else -> value.toInt().toString()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
