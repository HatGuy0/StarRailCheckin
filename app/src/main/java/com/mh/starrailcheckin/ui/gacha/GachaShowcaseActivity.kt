package com.mh.starrailcheckin.ui.gacha

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import com.mh.starrailcheckin.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * 抽卡统计页（星铁小助手风格）。
 * 接收序列化传入的全量抽卡记录 + 链接UID，完成：
 *  卡池分类 / 小保底歪不歪判定 / 每UP花费 / 累计五星星琼 / 五星明细条
 * 深浅色：使用 ?attr 解析出的主题色做控件配色，随系统自动切换。
 */
class GachaShowcaseActivity : AppCompatActivity() {

    // 主题色（按 App 主题模式显式判定深浅，不再依赖被强制日间的 XML attr）
    private var isDark = false
    private var rootBg = 0xFFF5F5FA.toInt()
    private var onSurface = 0xFF1A1A1A.toInt()
    private var onSurfaceVariant = 0xFF666666.toInt()
    private var surfaceCard = 0xFFF3EDF7.toInt()
    private var primary = 0xFF7B6CF6.toInt()
    private var gold = 0xFFB8860B.toInt()

    // 数据
    private val allRecords = mutableListOf<JSONObject>()
    private var headerUid = ""
    private var headerRegion = ""
    private var currentPool = 0 // 0角色 1光锥 2常驻
    private var searchQuery = ""
    private var rankFilter = 0 // 0全部 1五星 2四星
    private var timeFilter = 0 // 0全部 1近7天 2近30天 3近90天
    private val timeFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // 常驻五星角色名单（活动池出金：出常驻=歪，出限定UP=未歪）。
    private val standardChars = setOf(
        "彦卿", "白露", "杰帕德", "布洛妮娅", "瓦尔特", "克拉拉", "姬子",
        "符玄"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gacha_showcase)
        applyStatusBarInset()

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val raw = intent.getStringExtra("records_json").orEmpty()
        if (raw.isBlank()) { finish(); return }
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "抽卡记录数据已损坏，无法查看", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { allRecords.add(it) }
        headerUid = intent.getStringExtra("uid").orEmpty()
        headerRegion = intent.getStringExtra("region").orEmpty()

        EnkaNameMap.load(applicationContext)
        resolveColors()
        applyThemeTint()

        val summaryCard = findViewById<FrameLayout>(R.id.summaryCard)
        val planBtn = TextView(this).apply {
            text = "🎯 攒抽规划"
            setTextColor(primary)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { showPlanDialog() }
        }
        summaryCard.addView(planBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ))

        findViewById<TextView>(R.id.btnShareLongImage).setOnClickListener { shareLongImage() }

        renderHeader()
        buildPoolTabs()
        setupSearchAndFilter()
        selectPool(0)
    }

    private fun setupSearchAndFilter() {
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearch)
            .addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    renderFiveStarList(currentPool, poolRecords(currentPool))
                }
            })
        findViewById<TextView>(R.id.btnRankAll).setOnClickListener { setRankFilter(0) }
        findViewById<TextView>(R.id.btnRank5).setOnClickListener { setRankFilter(1) }
        findViewById<TextView>(R.id.btnRank4).setOnClickListener { setRankFilter(2) }
        paintRankFilter()
        findViewById<TextView>(R.id.btnTimeAll).setOnClickListener { setTimeFilter(0) }
        findViewById<TextView>(R.id.btnTime7).setOnClickListener { setTimeFilter(1) }
        findViewById<TextView>(R.id.btnTime30).setOnClickListener { setTimeFilter(2) }
        findViewById<TextView>(R.id.btnTime90).setOnClickListener { setTimeFilter(3) }
        paintTimeFilter()
    }

    private fun setTimeFilter(t: Int) {
        timeFilter = t
        paintTimeFilter()
        renderFiveStarList(currentPool, poolRecords(currentPool))
    }

    private fun paintTimeFilter() {
        val pairs = listOf(
            R.id.btnTimeAll to 0,
            R.id.btnTime7 to 1,
            R.id.btnTime30 to 2,
            R.id.btnTime90 to 3
        )
        pairs.forEach { (id, value) ->
            val tv = findViewById<TextView>(id)
            tv.setTextColor(if (timeFilter == value) primary else onSurfaceVariant)
            tv.setTypeface(
                android.graphics.Typeface.DEFAULT,
                if (timeFilter == value) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    /** 记录时间是否落在当前选择的时间范围内（无时间字段的历史数据默认保留）。 */
    private fun withinTimeRange(r: JSONObject): Boolean {
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

    private fun setRankFilter(r: Int) {
        rankFilter = r
        paintRankFilter()
        renderFiveStarList(currentPool, poolRecords(currentPool))
    }

    private fun paintRankFilter() {
        val btnAll = findViewById<TextView>(R.id.btnRankAll)
        val btn5 = findViewById<TextView>(R.id.btnRank5)
        val btn4 = findViewById<TextView>(R.id.btnRank4)
        btnAll.setTextColor(if (rankFilter == 0) primary else onSurfaceVariant)
        btnAll.setTypeface(android.graphics.Typeface.DEFAULT, if (rankFilter == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btn5.setTextColor(if (rankFilter == 1) primary else onSurfaceVariant)
        btn5.setTypeface(android.graphics.Typeface.DEFAULT, if (rankFilter == 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        btn4.setTextColor(if (rankFilter == 2) primary else onSurfaceVariant)
        btn4.setTypeface(android.graphics.Typeface.DEFAULT, if (rankFilter == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** 二级页内容下移避开系统状态栏，防止顶部 Toolbar/标题被遮挡。 */
    private fun applyStatusBarInset() {
        val root = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0) ?: return
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val t = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, t, v.paddingRight, v.paddingBottom)
            ins
        }
    }

    private fun resolveColors() {
        val themeMode = Prefs.themeMode(this)
        val sysDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        isDark = when (themeMode) {
            1 -> false
            2 -> true
            else -> sysDark
        }
        rootBg = if (isDark) 0xFF121212.toInt() else 0xFFF5F5FA.toInt()
        surfaceCard = if (isDark) 0xFF232130.toInt() else 0xFFF3EDF7.toInt()
        onSurface = if (isDark) 0xFFE6E1E5.toInt() else 0xFF1A1A1A.toInt()
        onSurfaceVariant = if (isDark) 0xFF9E9AA8.toInt() else 0xFF666666.toInt()
        primary = if (isDark) 0xFF9D8CFF.toInt() else 0xFF7B6CF6.toInt()
        gold = if (isDark) 0xFFFFD54F.toInt() else 0xFFB8860B.toInt()
    }

    private fun renderHeader() {
        val sb = StringBuilder("跃迁记录轨迹")
        if (headerUid.isNotBlank()) sb.append("  UID $headerUid")
        if (headerRegion.isNotBlank()) sb.append("  · $headerRegion")
        findViewById<TextView>(R.id.tvHeaderUid).text = sb.toString()
    }

    // ---------------- 卡池分类 ----------------
    private fun buildPoolTabs() {
        val row = findViewById<LinearLayout>(R.id.poolTabRow)
        row.removeAllViews()
        val names = listOf("角色池", "光锥池", "常驻池", "新手池")
        names.forEachIndexed { idx, name ->
            row.addView(buildTab(name, idx))
        }
    }

    private fun buildTab(name: String, idx: Int): TextView {
        return TextView(this).apply {
            text = name
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener {
                currentPool = idx
                selectPool(idx)
            }
            tag = idx
        }
    }

    private fun paintTab(idx: Int) {
        val row = findViewById<LinearLayout>(R.id.poolTabRow)
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as TextView
            val selected = (tv.tag as Int) == idx
            tv.setTextColor(if (selected) Color.WHITE else onSurfaceVariant)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(if (selected) primary else 0x00000000)
            }
        }
    }

    // ---------------- 池内数据 ----------------
    private fun poolOf(type: Int): Int = when {
        type == 11 -> 0  // 角色活动
        type == 12 -> 1  // 光锥活动
        type == 1 -> 2   // 常驻
        else -> 3        // 新手(2)/其他
    }

    private fun poolFlatTypes(pool: Int): Set<Int> = when (pool) {
        0 -> setOf(11)
        1 -> setOf(12)
        2 -> setOf(1)
        else -> setOf(2)
    }

    private fun poolRecords(pool: Int): List<JSONObject> {
        val types = poolFlatTypes(pool)
        // allRecords 中该池记录是接口返回顺序(新→旧)；反转为旧→新即为真实抽卡顺序。
        return allRecords.filter { types.contains(it.optInt("gachaType")) }.reversed()
    }

    /** 该卡池中按时间正序的五星列表 */
    private fun fiveStars(pool: Int): List<JSONObject> =
        poolRecords(pool).filter { it.optInt("rank") >= 5 }

    private fun isCharStandard(name: String?): Boolean = standardChars.contains(name?.trim())

    // ---------------- 渲染 ----------------
    private fun selectPool(idx: Int) {
        paintTab(idx)
        renderMetricsAndList(idx)
    }

    private fun renderMetricsAndList(idx: Int) {
        val records = poolRecords(idx)
        val globalRecords = allRecords

        val pity0 = findViewById<TextView>(R.id.tvPity0)
        val pity1 = findViewById<TextView>(R.id.tvPity1)
        val pity2 = findViewById<TextView>(R.id.tvPity2)
        val pity3 = findViewById<TextView>(R.id.tvPity3)

        // 总抽数（全池）
        val totalPulls = globalRecords.size
        pity1.text = totalPulls.toString()

        // 小保底不歪比例
        val charFive = fiveStars(0)
        val notLose = charFive.count { !isCharStandard(it.optString("name")) }
        pity0.text = if (charFive.isEmpty()) "—"
        else "${(notLose * 100 / charFive.size)}%"
        pity2.text = if (notLose > 0) {
            val charPulls = poolRecords(0).size
            val avg = charPulls.toDouble() / notLose
            "${java.math.BigDecimal.valueOf(avg).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()}抽"
        } else "—"
        val lcFive = fiveStars(1)
        val lcLimited = lcFive.size
        pity3.text = if (lcLimited > 0) {
            val lcPulls = poolRecords(1).size
            val avg = lcPulls.toDouble() / lcLimited
            "${java.math.BigDecimal.valueOf(avg).setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString()}抽"
        } else "—"

        renderFiveStarList(idx, records)
        renderPityDetail(idx)
        renderSummary(idx)
    }

    private fun renderFiveStarList(idx: Int, records: List<JSONObject>) {
        val container = findViewById<LinearLayout>(R.id.fiveStarContainer)
        container.removeAllViews()

        val filtered = records.filter { r ->
            val name = r.optString("name", "")
            val rank = r.optInt("rank", 1)
            val matchSearch = searchQuery.isBlank() || name.contains(searchQuery, ignoreCase = true)
            val matchRank = when (rankFilter) {
                1 -> rank >= 5
                2 -> rank == 4
                else -> true
            }
            matchSearch && matchRank && withinTimeRange(r)
        }

        if (filtered.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (searchQuery.isNotBlank() || rankFilter != 0 || timeFilter != 0) "无匹配结果"
                else "该卡池暂无出金记录"
                setTextColor(onSurfaceVariant)
                textSize = 13f
                setPadding(dp(4), dp(14), dp(4), dp(14))
            })
            return
        }

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
        if (cur > 0 && searchQuery.isBlank() && rankFilter == 0 && timeFilter == 0) {
            container.addView(buildCarryRow(cur))
        }
        val displayList = if (searchQuery.isNotBlank() || rankFilter != 0 || timeFilter != 0) filtered.reversed()
        else filtered.filter { it.optInt("rank") >= 5 }.reversed()
        displayList.forEach { item ->
            if (item.optInt("rank") >= 5) {
                val pity = pityById[item.optString("id")] ?: 0
                container.addView(buildFiveStarRow(item, pity, idx))
            } else {
                container.addView(buildFourStarRow(item, idx))
            }
        }
    }

    /** 四星简化展示行 */
    private fun buildFourStarRow(item: JSONObject, pool: Int): View {
        val name = item.optString("name", "未知")
        val time = item.optString("time").take(16)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(surfaceCard)
            }
        }
        val badge = TextView(this).apply {
            text = "4"
            setTextColor(0xFFB388FF.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22B388FF)
            }
        }
        card.addView(badge)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = name
            setTextColor(onSurface)
            textSize = 14f
        })
        col.addView(TextView(this).apply {
            text = "$time  ·  ${poolName(pool)}"
            setTextColor(onSurfaceVariant)
            textSize = 11f
        })
        card.addView(col)
        return card
    }

    /** 顶部「当前已垫N抽」占位行，对齐 HoyoGet。 */
    private fun buildCarryRow(carry: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(surfaceCard)
            }
        }
        val avatar = TextView(this).apply {
            text = "?"
            setTextColor(onSurfaceVariant)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x22FFFFFF)
            }
        }
        card.addView(avatar)
        card.addView(TextView(this).apply {
            text = "已垫 $carry 抽"
            setTextColor(0xFF43A047.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        return card
    }

    private fun buildFiveStarRow(five: JSONObject, pity: Int, pool: Int): View {
        val name = five.optString("name", "未知")
        val time = five.optString("time").take(16)
        val isChar = five.optString("itemType") == "角色" || pool == 0

        var zhai = false
        if (pool == 0) zhai = isCharStandard(name)
        val zhaiText = if (pool == 0) (if (zhai) "歪" else "未歪") else ""

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(surfaceCard)
            }
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val avatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }
        if (isChar) {
            EnkaImageLoader.loadAvatar(this, EnkaNameMap.charIdForName(name), avatar)
        } else {
            EnkaImageLoader.loadLightCone(this, EnkaNameMap.lcIdForName(name), avatar)
        }
        topRow.addView(avatar)

        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(this).apply {
            text = name
            setTextColor(gold)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        nameCol.addView(TextView(this).apply {
            text = "$time  ·  ${poolName(pool)}  ·  第 $pity 抽出金"
            setTextColor(onSurfaceVariant)
            textSize = 11f
        })
        topRow.addView(nameCol)

        if (zhaiText.isNotEmpty()) {
            val badge = TextView(this).apply {
                text = zhaiText
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(if (zhai) 0xFFE53935.toInt() else 0xFF43A047.toInt())
                }
            }
            topRow.addView(badge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) })
        }
        card.addView(topRow)

        val barBg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { topMargin = dp(8) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(if (isDarkByColor(surfaceCard)) 0xFF44424F.toInt() else 0xFFE0DDEE.toInt())
            }
        }
        val progressColor = if (zhai) 0xFFE53935.toInt() else 0xFF43A047.toInt()
        val cap = if (pool == 1) 80 else 90
        val fillW = pity.coerceIn(1, cap).toFloat()
        val emptyW = (cap - pity.coerceIn(1, cap)).coerceAtLeast(0).toFloat()
        val fill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fillW)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(progressColor)
            }
        }
        val empty = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, emptyW)
        }
        barBg.addView(fill)
        if (emptyW > 0) barBg.addView(empty)
        card.addView(barBg)

        card.addView(TextView(this).apply {
            text = "$pity 抽出金"
            setTextColor(onSurfaceVariant)
            textSize = 10f
            setPadding(0, dp(3), 0, 0)
        })

        return card
    }

    /** 当前某池距上一金之后已垫的抽数（旧→新顺序）。 */
    private fun carryOf(pool: Int): Int {
        var cur = 0
        for (r in poolRecords(pool)) {
            if (r.optInt("rank") >= 5) cur = 0 else cur += 1
        }
        return cur
    }

    /** 角色池保底状态：上一金歪 → 大保底必 UP；未歪 → 小保底。 */
    private fun charBannerState(): String {
        val f = fiveStars(0)
        if (f.isEmpty()) return "暂无角色出金记录"
        val last = f.last()
        return if (isCharStandard(last.optString("name"))) "大保底 · 下次小保底必出 UP"
        else "小保底 · 存在歪的可能"
    }

    /** 角色池是否处于大保底：上一金为常驻（歪了）→ 下次五星必出 UP；无记录按小保底处理。 */
    private fun charGuaranteed(): Boolean {
        val f = fiveStars(0)
        if (f.isEmpty()) return false
        return isCharStandard(f.last().optString("name"))
    }

    /** 保底详情：当前水位、平均/最欧/最非出金与最近出金记录。 */
    private fun renderPityDetail(idx: Int) {
        val tv = findViewById<TextView>(R.id.tvPityDetail)
        val records = poolRecords(idx)
        if (records.isEmpty()) {
            tv.text = "${poolName(idx)}暂无抽卡记录"
            return
        }
        val cap = if (idx == 1) 80 else 90
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

        tv.text = buildString {
            appendLine("${poolName(idx)}：已垫 $carry 抽 · 距五星还差 $left 抽（$cap 抽保底）")
            when (idx) {
                0 -> {
                    appendLine(charBannerState())
                    val lose = fiveStars(0).count { isCharStandard(it.optString("name")) }
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
                    val tag = if (idx == 0) {
                        if (isCharStandard(r.optString("name"))) " · 歪" else " · 未歪"
                    } else ""
                    appendLine("  ${r.optString("name")} · 第 $p 抽 · ${r.optString("time").take(10)}$tag")
                }
            } else {
                append("暂无出金记录，继续积累保底水位吧")
            }
        }
    }

    private fun renderSummary(idx: Int) {
        val tv = findViewById<TextView>(R.id.tvSummary)
        val all = allRecords
        val totalPulls = all.size
        val totalFive = all.count { it.optInt("rank") >= 5 }
        val starJade = totalPulls * 160L
        val charFive = fiveStars(0)
        val charLose = charFive.count { isCharStandard(it.optString("name")) }
        val charWin = charFive.size - charLose
        val lcLimited = fiveStars(1).size
        val limitedFive = charWin + lcLimited
        var standardFive = fiveStars(2).size + charLose

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

        // 攒抽规划（用户手动填写星琼 / 专票 / 普通通票，按账号本地持久化）
        val jade = Prefs.gachaJade(this)
        val pass = Prefs.gachaPass(this)
        val stdPass = Prefs.gachaStd(this)
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

        tv.text = buildString {
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
        }
        tv.visibility = View.VISIBLE
    }

    /** 攒抽规划输入弹窗：填写当前星琼、专属票与普通通票，本地保存后刷新统计。 */
    private fun showPlanDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val jadeEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "当前星琼"
            setText(Prefs.gachaJade(this@GachaShowcaseActivity).toString())
        }
        val passEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "专属票（星轨专票）"
            setText(Prefs.gachaPass(this@GachaShowcaseActivity).toString())
        }
        val stdEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "普通通票（星轨通票，仅常驻池）"
            setText(Prefs.gachaStd(this@GachaShowcaseActivity).toString())
        }
        container.addView(jadeEdit)
        container.addView(passEdit)
        container.addView(stdEdit)
        AlertDialog.Builder(this)
            .setTitle("攒抽规划")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val j = jadeEdit.text.toString().trim().toIntOrNull() ?: 0
                val p = passEdit.text.toString().trim().toIntOrNull() ?: 0
                val s = stdEdit.text.toString().trim().toIntOrNull() ?: 0
                Prefs.setGachaJade(this, j)
                Prefs.setGachaPass(this, p)
                Prefs.setGachaStd(this, s)
                renderSummary(currentPool)
                Toast.makeText(this, "攒抽规划已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun poolName(pool: Int): String = when (pool) {
        0 -> "角色池"; 1 -> "光锥池"; 2 -> "常驻池"; else -> "联动池"
    }

    /** 生成 1080 宽统计长图并调起系统分享。 */
    private fun shareLongImage() {
        Toast.makeText(this, "正在生成长图…", Toast.LENGTH_SHORT).show()
        val recordsJson = intent.getStringExtra("records_json").orEmpty()
        Thread {
            try {
                val bmp = GachaShareImage.create(recordsJson, headerUid, headerRegion)
                val dir = File(getExternalFilesDir("exports"), "gacha").apply { mkdirs() }
                val file = File(dir, "gacha_share_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                runOnUiThread {
                    val uri = FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "分享抽卡统计长图"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "生成长图失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 按当前深浅模式，刷根背景、静态玻璃卡片与静态文字色。 */
    private fun applyThemeTint() {
        val content = findViewById<View>(android.R.id.content) ?: return
        val root = (content as? ViewGroup)?.getChildAt(0) ?: content
        root.setBackgroundColor(rootBg)
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.setTitleTextColor(onSurface)
        tintTree(root)
    }

    private fun tintTree(v: View) {
        when (v) {
            is TextView -> {
                val c = v.textColors.defaultColor
                when {
                    isAccentColor(c) -> { /* 紫/金/白/绿/红等强调色保留 */ }
                    isSecondaryColor(c) -> v.setTextColor(onSurfaceVariant)
                    else -> v.setTextColor(onSurface)
                }
            }
            is ViewGroup -> {
                // 静态玻璃卡（LayerDrawable）替换为当前主题的纯色圆角卡；代码动态行用 GradientDrawable，保持不动。
                if (v.background is android.graphics.drawable.LayerDrawable) {
                    v.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(20).toFloat()
                        setColor(surfaceCard)
                    }
                }
                for (i in 0 until v.childCount) tintTree(v.getChildAt(i))
            }
        }
    }

    /** 高饱和（紫/金/绿/红）或接近白色，视为强调色。 */
    private fun isAccentColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return (max - min) > 60 || min > 200
    }

    /** 低饱和中灰，视为副文字。 */
    private fun isSecondaryColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        return (max - min) <= 40 && r in 90..170
    }

    private fun isDarkByColor(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
