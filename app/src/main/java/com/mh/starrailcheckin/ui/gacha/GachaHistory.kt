package com.mh.starrailcheckin.ui.gacha

import android.content.Context
import com.mh.starrailcheckin.util.LogHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽卡分析历史快照（轻量 JSON 文件，无数据库）。
 * 每次成功分析后把整套 records_json 存一条快照，点击可离线回看，不重新联网/重算。
 */
object GachaHistory {
    data class Entry(
        val index: Int,
        val date: String,
        val uid: String,
        val records: String
    )

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "gacha_history.json")

    private fun readAll(ctx: Context): JSONArray {
        return try {
            val f = file(ctx)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (_: Exception) { JSONArray() }
    }

    private fun writeAll(ctx: Context, arr: JSONArray) {
        try {
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            LogHelper.e("GachaHistory", "写入抽卡历史快照失败", e)
        }
    }

    /** 保存一条新快照（置顶）。 */
    fun save(ctx: Context, uid: String, records: String) {
        val arr = readAll(ctx)
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        val obj = JSONObject().apply {
            put("ts", now)
            put("date", date)
            put("uid", uid)
            put("records", records)
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.put(it) }
        // 最多保留 50 条
        val trimmed = JSONArray()
        for (i in 0 until minOf(out.length(), 50)) trimmed.put(out.get(i))
        writeAll(ctx, trimmed)
    }

    /** 导入 UIGF 时按 id 去重合并到该 UID 的最近快照，避免覆盖已有记录。 */
    fun saveMerged(ctx: Context, uid: String, records: String) {
        val incoming = runCatching { JSONArray(records) }.getOrNull() ?: return
        val existingEntry = list(ctx).firstOrNull { it.uid == uid }
        val existing = existingEntry?.let { runCatching { JSONArray(it.records) }.getOrNull() }
        val ids = HashSet<String>()
        val merged = JSONArray()
        if (existing != null) {
            for (i in 0 until existing.length()) {
                val o = existing.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) ids.add(id)
                merged.put(o)
            }
        }
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotBlank() && !ids.add(id)) continue
            merged.put(o)
        }
        if (merged.length() == 0) return
        save(ctx, uid, merged.toString())
    }

    fun list(ctx: Context): List<Entry> {
        val arr = readAll(ctx)
        val res = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let {
                res.add(Entry(i, it.optString("date"), it.optString("uid"), it.optString("records")))
            }
        }
        return res
    }

    fun delete(ctx: Context, index: Int) {
        val arr = readAll(ctx)
        if (index < 0 || index >= arr.length()) return
        val out = JSONArray()
        for (i in 0 until arr.length()) if (i != index) arr.optJSONObject(i)?.let { out.put(it) }
        writeAll(ctx, out)
    }

    fun clear(ctx: Context) = writeAll(ctx, JSONArray())
}
