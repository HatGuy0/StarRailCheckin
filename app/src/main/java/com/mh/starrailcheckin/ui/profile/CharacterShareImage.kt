package com.mh.starrailcheckin.ui.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 角色配装分享卡（纯 Canvas 绘制，不依赖 Compose/XML，保证各设备输出一致）。
 *
 * 数据来自详情页 [DetailUi]；立绘由调用方在 IO 线程取好 Bitmap 传入，失败可传 null 降级。
 * 显示项与签名文案由 [ShareOpts] 控制，偏好持久化在 Prefs。
 */
object CharacterShareImage {

    data class ShareOpts(
        val caption: String,
        val showUid: Boolean,
        val showStats: Boolean,
        val showRelics: Boolean,
        val showScore: Boolean
    )

    private const val W = 1080
    private const val MARGIN = 48f

    /** 面板展示顺序（field → 标题）。 */
    private val STAT_ORDER = listOf(
        "hp" to "生命",
        "atk" to "攻击",
        "def" to "防御",
        "spd" to "速度",
        "crit_rate" to "暴击",
        "crit_dmg" to "爆伤",
        "energy_regen" to "充能",
        "break_dmg" to "击破"
    )

    internal fun create(d: DetailUi, portrait: Bitmap?, opts: ShareOpts): Bitmap {
        // ===== 先按显示项估算高度 =====
        var height = 360f                    // 头部
        if (opts.showStats) height += 300f   // 面板网格
        if (d.lc != null) height += 150f     // 光锥
        if (opts.showRelics && d.relics.isNotEmpty()) {
            height += 90f + (d.relics.size / 3 + d.relics.size % 3) * 132f
        }
        if (opts.caption.isNotBlank()) height += 90f
        height += 130f                       // 页脚

        val bmp = Bitmap.createBitmap(W, height.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        fun paint(size: Float, color: Int, isBold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (isBold) bold else normal
        }

        // 背景渐变
        c.drawPaint(Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height,
                Color.parseColor("#F7F5FC"), Color.parseColor("#E9E6F7"),
                Shader.TileMode.CLAMP
            )
        })

        var y = 82f

        // ===== 头部：立绘 + 标题 =====
        portrait?.let { p ->
            val headH = 300f
            val src = Rect(0, 0, p.width, p.height)
            val dst = RectF(0f, 40f, W * 0.62f, 40f + headH)
            c.drawBitmap(p, src, dst, null)
        }

        // 标题（右上）
        val titleX = W - MARGIN
        c.drawText(d.name, titleX, y + 30f, paint(52f, Color.parseColor("#241F3D"), true).apply {
            textAlign = Paint.Align.RIGHT
        })
        y += 64f
        val meta = buildString {
            append("Lv.${d.level}")
            if (d.rank > 0) append("  ·  ${d.rank}唤")
            if (opts.showUid && d.uid.isNotBlank()) append("  ·  UID ${d.uid}")
        }
        c.drawText(meta, titleX, y, paint(26f, Color.parseColor("#726C8C")).apply {
            textAlign = Paint.Align.RIGHT
        })
        y += 40f
        val chips = listOf(d.element, d.path).filter { it.isNotBlank() }
        if (chips.isNotEmpty()) {
            val chipText = chips.joinToString("  ·  ")
            c.drawText(chipText, titleX, y, paint(26f, Color.parseColor("#6C5CE7"), true).apply {
                textAlign = Paint.Align.RIGHT
            })
        }
        y = 360f

        // ===== 面板网格 =====
        if (opts.showStats) {
            y += 16f
            c.drawText("属性面板", MARGIN, y, paint(34f, Color.parseColor("#241F3D"), true))
            y += 24f
            val statByField = d.stats.associateBy { it.field }
            val gap = 18f
            val cardW = (W - MARGIN * 2 - gap * 3) / 4f
            val cardH = 110f
            STAT_ORDER.forEachIndexed { i, (field, label) ->
                val col = i % 4
                val row = i / 4
                val left = MARGIN + col * (cardW + gap)
                val top = y + row * (cardH + gap)
                drawCard(c, left, top, left + cardW, top + cardH)
                val rowStat = statByField[field]
                val value = rowStat?.total ?: "—"
                val pValue = paint(32f, Color.parseColor("#6C5CE7"), true)
                pValue.textAlign = Paint.Align.CENTER
                c.drawText(value, left + cardW / 2, top + 52f, pValue)
                val pLabel = paint(22f, Color.parseColor("#8A849F"))
                pLabel.textAlign = Paint.Align.CENTER
                c.drawText(label, left + cardW / 2, top + 84f, pLabel)
            }
            y += 2 * (cardH + gap) + 16f
        }

        // ===== 光锥 =====
        d.lc?.let { lc ->
            drawCard(c, MARGIN, y, W - MARGIN, y + 120f)
            c.drawText(lc.name, MARGIN + 28f, y + 46f, paint(30f, Color.parseColor("#241F3D"), true))
            val lcMeta = "S${lc.superimpose}  ·  Lv.${lc.level}"
            val pRight = paint(24f, Color.parseColor("#8A849F"))
            pRight.textAlign = Paint.Align.RIGHT
            c.drawText(lcMeta, W - MARGIN - 28f, y + 46f, pRight)
            val lcStats = listOfNotNull(
                "生命 ${lc.hp}".takeIf { lc.hp.isNotBlank() },
                "攻击 ${lc.atk}".takeIf { lc.atk.isNotBlank() },
                "防御 ${lc.def}".takeIf { lc.def.isNotBlank() }
            ).joinToString("  ·  ")
            if (lcStats.isNotBlank()) {
                c.drawText(lcStats, MARGIN + 28f, y + 86f, paint(24f, Color.parseColor("#6E6886")))
            }
            y += 150f
        }

        // ===== 遗器 =====
        if (opts.showRelics && d.relics.isNotEmpty()) {
            c.drawText("遗器评分", MARGIN, y, paint(34f, Color.parseColor("#241F3D"), true))
            if (opts.showScore) {
                val scoreText = "%.1f 分 · ${d.totalGrade}".format(Locale.US, d.totalScore)
                val pScore = paint(30f, gradeColor(d.totalGrade), true)
                pScore.textAlign = Paint.Align.RIGHT
                c.drawText(scoreText, W - MARGIN, y, pScore)
            }
            y += 24f
            val gap = 18f
            val cardW = (W - MARGIN * 2 - gap * 2) / 3f
            val cardH = 118f
            d.relics.sortedBy { it.type }.forEachIndexed { i, r ->
                val col = i % 3
                val row = i / 3
                val left = MARGIN + col * (cardW + gap)
                val top = y + row * (cardH + gap)
                drawCard(c, left, top, left + cardW, top + cardH)
                val relicName = r.setName.ifBlank { EnkaNameMap.relicTypeName(r.type) }
                c.drawText(relicName, left + 20f, top + 34f, paint(23f, Color.parseColor("#3A3357"), true))
                val scoreLine = "%.1f分 ${r.grade}".format(Locale.US, r.score)
                c.drawText(scoreLine, left + 20f, top + 68f, paint(24f, gradeColor(r.grade), true))
                val subLine = buildString {
                    if (r.failCount > 0) append("歪${r.failCount}")
                    if (r.effectiveCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("有效 ${r.effectiveCount} 条")
                    }
                }
                if (subLine.isNotBlank()) {
                    c.drawText(subLine, left + 20f, top + 96f, paint(20f, Color.parseColor("#8A849F")))
                }
            }
            y += (d.relics.size / 3 + d.relics.size % 3) * (cardH + gap) + 16f
        }

        // ===== 签名文案 =====
        if (opts.caption.isNotBlank()) {
            val pCaption = paint(28f, Color.parseColor("#5A5470"), true)
            pCaption.textAlign = Paint.Align.CENTER
            c.drawText(opts.caption, W / 2f, y + 30f, pCaption)
            y += 70f
        }

        // ===== 页脚 =====
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val pFooter = paint(22f, Color.parseColor("#9A94AC"))
        pFooter.textAlign = Paint.Align.CENTER
        c.drawText("由 星铁打卡助手 生成 · 评分口径 miao-plugin · $date", W / 2f, height - 44f, pFooter)

        return bmp
    }

    /** 评级分色与详情页 gradeColor 口径保持一致：ACE/MAX 红，SSS/SS/S 黄，A/B 蓝，其余绿。 */
    private fun gradeColor(grade: String): Int = when (grade.uppercase()) {
        "MAX", "ACE" -> Color.parseColor("#E74C3C")
        "SSS", "SS", "S" -> Color.parseColor("#E6A23C")
        "A", "B" -> Color.parseColor("#4D8CF7")
        else -> Color.parseColor("#4CC38A")
    }

    private fun drawCard(
        c: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int = Color.parseColor("#FFFFFF")
    ) {
        c.drawRoundRect(RectF(left, top, right, bottom), 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            setShadowLayer(12f, 0f, 6f, Color.parseColor("#16000000"))
        })
    }
}
