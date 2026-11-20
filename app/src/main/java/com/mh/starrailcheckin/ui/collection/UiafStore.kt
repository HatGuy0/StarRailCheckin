package com.mh.starrailcheckin.ui.collection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * UIAF v1.1 成就记录本地留存。
 *
 * 说明：米游社 Cookie 接口不下发游戏内成就明细，这里仅做 UIAF 文件的本地导入 / 导出与去重留存，
 * 不伪造成就数据。成就字段对齐 UIAF 规范：id / time / order。
 */
object UiafStore {

    data class Achievement(
        val id: Int,
        val time: String,
        val order: Int
    )

    private const val FILE = "uiaf_achievements.json"
    private const val APP_NAME = "StarRailCheckin"
    private const val APP_VERSION = "2.1"

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, FILE)

    fun list(ctx: Context): List<Achievement> {
        val raw = try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            JSONArray(f.readText())
        } catch (_: Exception) { return emptyList() }

        val out = ArrayList<Achievement>()
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id <= 0) continue
            out += Achievement(
                id = id,
                time = o.optString("time", ""),
                order = o.optInt("order", i + 1)
            )
        }
        return out
    }

    /** 按成就 id 去重合并保存，新导入的覆盖同 id 旧记录。 */
    fun saveMerged(ctx: Context, incoming: List<Achievement>): Int {
        if (incoming.isEmpty()) return 0
        val merged = LinkedHashMap<Int, Achievement>()
        list(ctx).forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        val arr = JSONArray()
        merged.values.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("time", a.time)
                put("order", a.order)
            })
        }
        try { file(ctx).writeText(arr.toString()) } catch (_: Exception) { }
        return merged.size
    }

    /** 解析 UIAF v1.1 文件，兼容 achievement / achievement_list / list 三种数组键。 */
    fun parse(text: String): List<Achievement> {
        val trimmed = text.trim()
        val rootArr = if (trimmed.startsWith("[")) {
            runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        } else {
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            root.optJSONArray("achievement")
                ?: root.optJSONArray("achievement_list")
                ?: root.optJSONArray("list")
                ?: return emptyList()
        }

        val out = ArrayList<Achievement>()
        for (i in 0 until rootArr.length()) {
            val o = rootArr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id <= 0) continue
            out += Achievement(
                id = id,
                time = o.optString("time", ""),
                order = o.optInt("order", i + 1)
            )
        }
        return out
    }

    /** 把本地留存记录导出为 UIAF v1.1 文件内容。 */
    fun export(ctx: Context, uid: String): String {
        val records = list(ctx)
        val arr = JSONArray()
        records.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("time", a.time)
                put("order", a.order)
            })
        }
        return JSONObject().apply {
            put("info", JSONObject().apply {
                put("uid", uid)
                put("export_timestamp", System.currentTimeMillis() / 1000)
                put("export_app", APP_NAME)
                put("export_app_version", APP_VERSION)
                put("uiaf_version", "v1.1")
            })
            put("achievement", arr)
        }.toString(2)
    }
}
