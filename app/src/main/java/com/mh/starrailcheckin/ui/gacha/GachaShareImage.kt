package com.mh.starrailcheckin.ui.gacha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成可分享的抽卡统计长图（1080 宽，内容自适应高度）。
 * 纯 Canvas 绘制，不依赖 Compose/XML，保证在任何设备上输出一致。
 */
object GachaShareImage {

    private val standardChars = setOf(
        "彦卿", "白露", "杰帕德", "布洛妮娅", "瓦尔特", "克拉拉", "姬子", "符玄"
    )

    private data class PoolStat(val pulls: Int, val five: Int, val carry: Int)

    fun create(recordsJson: String, uid: String, region: String): Bitmap {
        val arr = runCatching { JSONArray(recordsJson) }.getOrElse { JSONArray() }
        val records = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { records.add(it) }

        val total = records.size
        val five = records.filter { it.optInt("rank", 1) >= 5 }
        val rate = if (total > 0) five.size * 100.0 / total else 0.0
        val avgPity = if (five.isNotEmpty()) total.toDouble() / five.size else 0.0
        val starJade = total * 160L
        val ouLabel = when {
            five.isEmpty() -> "—"
            avgPity <= 55 -> "欧皇"
            avgPity <= 65 -> "小欧"
            avgPity <= 75 -> "亚洲"
            avgPity <= 85 -> "非酋"
            else -> "酋长"
        }

        fun poolStat(type: Int): PoolStat {
            val list = records.filter { it.optInt("gachaType", 0) == type }
            var cur = 0
            list.reversed().forEach {
                if (it.optInt("rank", 1) >= 5) cur = 0 else cur++
            }
            return PoolStat(list.size, list.count { it.optInt("rank", 1) >= 5 }, cur)
        }

        val charPool = poolStat(11)
        val lcPool = poolStat(12)
        val stdPool = poolStat(1)

        val pityById = HashMap<String, Int>()
        var cur = 0
        records.reversed().forEach { r ->
            if (r.optInt("rank", 1) >= 5) {
                cur += 1
                pityById[r.optString("id")] = cur
                cur = 0
            } else {
                cur += 1
            }
        }
        val displayFive = five.take(30)

        val w = 1080
        val margin = 48f
        val headerH = 330f
        val metricsH = 300f
        val poolH = 390f
        val listTitleH = 100f
        val listRowH = 70f
        val footerH = 120f
        val height = (headerH + metricsH + poolH + listTitleH + displayFive.size.coerceAtLeast(1) * listRowH + footerH).toInt()

        val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                Color.parseColor("#F7F5FC"), Color.parseColor("#E9E6F7"),
                Shader.TileMode.CLAMP
            )
        })

        val bold = Typeface.create("sans-serif", Typeface.BOLD)
        val normal = Typeface.create("sans-serif", Typeface.NORMAL)
        fun paint(size: Float, color: Int, isBold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (isBold) bold else normal
        }

        var y = 82f
        c.drawText("星穹铁道 · 跃迁统计", margin, y, paint(54f, Color.parseColor("#241F3D"), true))
        y += 48f
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val sub = buildString {
            if (uid.isNotBlank()) append("UID $uid")
            if (region.isNotBlank()) append("  ·  $region")
            append("  ·  $date")
        }
        c.drawText(sub, margin, y, paint(26f, Color.parseColor("#726C8C")))
        y += 28f
        c.drawRoundRect(
            RectF(margin, y, w - margin, y + 3f), 2f, 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    margin, y, w - margin, y,
                    intArrayOf(Color.parseColor("#8B7CF6"), Color.parseColor("#F0A6CA"), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP
                )
            }
        )

        // 6 个核心指标
        y = headerH
        val gap = 18f
        val cardW = (w - margin * 2 - gap * 2) / 3f
        val cardH = 120f
        val metrics = listOf(
            "总抽数" to "$total",
            "五星数量" to "${five.size}",
            "五星出率" to "%.1f%%".format(Locale.CHINA, rate),
            "平均抽/金" to if (five.isEmpty()) "—" else "%.1f".format(Locale.CHINA, avgPity),
            "欧非评价" to ouLabel,
            "历史星琼" to "$starJade"
        )
        metrics.forEachIndexed { i, (label, value) ->
            val col = i % 3
            val row = i / 3
            val left = margin + col * (cardW + gap)
            val top = y + row * (cardH + gap)
            drawCard(c, left, top, left + cardW, top + cardH)
            val cx = left + cardW / 2
            val pValue = paint(40f, Color.parseColor("#6C5CE7"), true)
            pValue.textAlign = Paint.Align.CENTER
            c.drawText(value, cx, top + 62f, pValue)
            val pLabel = paint(23f, Color.parseColor("#8A849F"))
            pLabel.textAlign = Paint.Align.CENTER
            c.drawText(label, cx, top + 96f, pLabel)
        }

        // 卡池统计
        y += 2 * (cardH + gap) + 24f
        c.drawText("卡池统计", margin, y, paint(34f, Color.parseColor("#241F3D"), true))
        y += 22f
        val poolRows = listOf(
            "角色池" to charPool,
            "光锥池" to lcPool,
            "常驻池" to stdPool
        )
        poolRows.forEachIndexed { i, (name, stat) ->
            val top = y + i * 112f
            drawCard(c, margin, top, w - margin, top + 96f)
            val r = if (stat.pulls > 0) stat.five * 100.0 / stat.pulls else 0.0
            c.drawText(name, margin + 28f, top + 42f, paint(30f, Color.parseColor("#241F3D"), true))
            val right = "已垫 %d 抽".format(stat.carry)
            val pRight = paint(24f, Color.parseColor("#8A849F"))
            pRight.textAlign = Paint.Align.RIGHT
            c.drawText(right, w - margin - 28f, top + 42f, pRight)
            val detail = "%d 抽  ·  %d 金  ·  出率 %.1f%%".format(Locale.CHINA, stat.pulls, stat.five, r)
            c.drawText(detail, margin + 28f, top + 78f, paint(24f, Color.parseColor("#6E6886")))
        }
        y += poolRows.size * 112f + 14f

        // 五星记录
        c.drawText("五星记录（最近 ${displayFive.size} 个）", margin, y + 36f, paint(34f, Color.parseColor("#241F3D"), true))
        y += listTitleH
        if (displayFive.isEmpty()) {
            c.drawText("暂无五星记录", margin + 8f, y + 30f, paint(26f, Color.parseColor("#8A849F")))
        } else {
            displayFive.forEachIndexed { i, r ->
                val top = y + i * listRowH
                drawCard(c, margin, top, w - margin, top + listRowH - 12f, Color.parseColor("#FFFFFF"))
                c.drawCircle(margin + 34f, top + 28f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F4B740")
                })
                val name = r.optString("name", "未知")
                c.drawText(name, margin + 60f, top + 38f, paint(28f, Color.parseColor("#3A3357"), true))
                val pity = pityById[r.optString("id")] ?: 0
                val type = r.optInt("gachaType", 11)
                val poolName = when (type) {
                    11 -> "角色池"
                    12 -> "光锥池"
                    1 -> "常驻池"
                    else -> "新手池"
                }
                val time = r.optString("time", "").take(16)
                val info = "第 %d 抽  ·  %s  ·  %s".format(pity, poolName, time)
                val pInfo = paint(22f, Color.parseColor("#8A849F"))
                pInfo.textAlign = Paint.Align.RIGHT
                c.drawText(info, w - margin - 24f, top + 38f, pInfo)
            }
        }

        // 页脚
        val footerY = height - 54f
        val pFooter = paint(22f, Color.parseColor("#9A94AC"))
        pFooter.textAlign = Paint.Align.CENTER
        c.drawText("由 星铁打卡助手 生成 · UIGF 数据统计", w / 2f, footerY, pFooter)

        return bmp
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
