package com.mh.starrailcheckin.ui.gacha

import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * UIGF v4.0（hkrpg）抽卡记录导入 / 导出。
 *
 * 导出结构对齐 UIGF 规范：info + hkrpg[ { uid, timezone, lang, list: [...] } ]。
 * 导入同时兼容：规范嵌套 `hkrpg[].list`、旧版扁平 `hkrpg/list/records` 数组、
 * 以及旧版历史快照 `[{uid, records}]`，方便从旧版本或其他工具迁移。
 */
object GachaUigf {

    data class Imported(
        val uid: String,
        val recordsJson: String,
        val count: Int
    )

    private const val APP_NAME = "StarRailCheckin"
    private const val APP_VERSION = "2.1"
    private const val UIGF_VERSION = "v4.0"
    private const val TIMEZONE = 8

    /** 把内部 records JSON 转成 UIGF v4.0 文件内容。 */
    fun export(recordsJson: String, uid: String): String {
        val src = runCatching { JSONArray(recordsJson) }.getOrElse { JSONArray() }
        val list = JSONArray()
        for (i in 0 until src.length()) {
            val r = src.optJSONObject(i) ?: continue
            val name = r.optString("name", "")
            val itemType = r.optString("itemType", "")
            val itemId = resolveItemId(itemType, name)
            list.put(JSONObject().apply {
                put("gacha_id", r.optString("gachaId", ""))
                put("gacha_type", r.optInt("gachaType", 11).toString())
                put("item_id", itemId)
                put("count", "1")
                put("time", r.optString("time", ""))
                put("name", name)
                put("lang", "zh-cn")
                put("item_type", itemType)
                put("rank_type", r.optInt("rank", 1).toString())
                put("id", r.optString("id", ""))
            })
        }

        val hkrpg = JSONArray().put(JSONObject().apply {
            put("uid", uid)
            put("timezone", TIMEZONE)
            put("lang", "zh-cn")
            put("list", list)
        })

        return JSONObject().apply {
            put("info", JSONObject().apply {
                put("uid", uid)
                put("lang", "zh-cn")
                put("export_timestamp", System.currentTimeMillis() / 1000)
                put("export_app", APP_NAME)
                put("export_app_version", APP_VERSION)
                put("version", UIGF_VERSION)
            })
            put("hkrpg", hkrpg)
        }.toString(2)
    }

    /** 按物品类型 + 中文名反查内部 item_id；查不到留空串（规范允许可选填充）。 */
    private fun resolveItemId(itemType: String, name: String): String {
        if (name.isBlank()) return ""
        val id = if (itemType.contains("光锥")) {
            EnkaNameMap.lcIdForName(name)
        } else {
            EnkaNameMap.charIdForName(name)
        }
        return if (id > 0) id.toString() else ""
    }

    /**
     * 解析 UIGF v4.0 / 旧版历史快照 / SRGF 风格数组。
     * 返回按 UID 聚合后的记录，可直接写入 GachaHistory。
     */
    fun parse(text: String): List<Imported> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
            val out = mutableListOf<Imported>()
            val bare = LinkedHashMap<String, MutableList<JSONObject>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 旧版快照
                val records = o.optString("records", "")
                if (records.isNotBlank()) {
                    val uid = o.optString("uid", "")
                    out.add(Imported(uid, records, runCatching { JSONArray(records).length() }.getOrDefault(0)))
                    continue
                }
                // 裸记录数组：按 uid 聚合
                val rec = convertRecord(o) ?: continue
                bare.getOrPut(o.optString("uid", "")) { mutableListOf() }.add(rec)
            }
            bare.forEach { (uid, list) ->
                out.add(Imported(uid, JSONArray(list).toString(), list.size))
            }
            return out
        }

        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val infoUid = root.optJSONObject("info")?.optString("uid", "").orEmpty()
        val hkrpgArr = root.optJSONArray("hkrpg")
            ?: root.optJSONArray("list")
            ?: root.optJSONArray("records")
            ?: root.optJSONObject("data")?.optJSONArray("list")
            ?: return emptyList()

        val grouped = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until hkrpgArr.length()) {
            val o = hkrpgArr.optJSONObject(i) ?: continue
            // 规范结构：元素自带 uid + list；旧扁平结构：元素即记录。
            val innerList = o.optJSONArray("list")
            if (innerList != null) {
                val uid = o.optString("uid", infoUid)
                for (j in 0 until innerList.length()) {
                    val rec = innerList.optJSONObject(j)?.let { convertRecord(it) } ?: continue
                    grouped.getOrPut(uid) { mutableListOf() }.add(rec)
                }
            } else {
                val uid = o.optString("uid", infoUid)
                val rec = convertRecord(o) ?: continue
                grouped.getOrPut(uid) { mutableListOf() }.add(rec)
            }
        }
        return grouped.map { (uid, list) ->
            Imported(uid, JSONArray(list).toString(), list.size)
        }
    }

    private fun convertRecord(o: JSONObject): JSONObject? {
        val name = o.optString("name", "")
        val id = o.optString("id", "")
        if (name.isBlank() && id.isBlank()) return null
        val rankType = o.optString("rank_type", o.optString("rank", "1"))
        return JSONObject().apply {
            put("id", id)
            put("gachaType", o.optInt("gacha_type", o.optInt("gachaType", 11)))
            put("gachaId", o.optString("gacha_id", o.optString("gachaId", "")))
            put("itemType", o.optString("item_type", o.optString("itemType", "")))
            put("name", name)
            put("rank", rankType.toString().toIntOrNull() ?: 1)
            put("time", o.optString("time", ""))
        }
    }
}
