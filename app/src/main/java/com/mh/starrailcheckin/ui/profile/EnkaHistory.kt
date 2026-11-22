package com.mh.starrailcheckin.ui.profile

import android.content.Context
import com.mh.starrailcheckin.util.LogHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 角色橱窗查询历史快照（轻量 JSON 文件，无数据库）。
 * 每次查询成功后把 enka 返回的原始 json 存一条快照，
 * 点击可离线回看（不再联网、不再重算）。
 */
object EnkaHistory {
    data class Entry(
        val index: Int,
        val date: String,
        val uid: String,
        val nickname: String,
        val rawJson: String
    )

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "enka_history.json")

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
            LogHelper.e("EnkaHistory", "写入橱窗历史快照失败", e)
        }
    }

    /** 保存一条新快照（置顶）。 */
    fun save(ctx: Context, uid: String, nickname: String, rawJson: String) {
        val arr = readAll(ctx)
        val now = System.currentTimeMillis()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        val obj = JSONObject().apply {
            put("ts", now)
            put("date", date)
            put("uid", uid)
            put("nickname", nickname)
            put("raw", rawJson)
        }
        val out = JSONArray()
        out.put(obj)
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.put(it) }
        // 最多保留 50 条
        val trimmed = JSONArray()
        for (i in 0 until minOf(out.length(), 50)) trimmed.put(out.get(i))
        writeAll(ctx, trimmed)
    }

    fun list(ctx: Context): List<Entry> {
        val arr = readAll(ctx)
        val res = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let {
                res.add(Entry(
                    i,
                    it.optString("date"),
                    it.optString("uid"),
                    it.optString("nickname"),
                    it.optString("raw")
                ))
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
