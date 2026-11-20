package com.mh.starrailcheckin.util

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据备份/恢复（zip）：
 * - prefs.json：全部 SharedPreferences（设置、Cookie、多账号、主题等）
 * - gacha_history.json：抽卡历史快照
 * 不包含头像/提醒背景等大文件，避免备份体积失控。
 */
object BackupHelper {

    data class ImportSummary(
        val prefCount: Int,
        val gachaRestored: Boolean,
        val message: String
    )

    private const val ENTRY_PREFS = "prefs.json"
    private const val ENTRY_GACHA = "gacha_history.json"
    private const val ENTRY_META = "meta.json"

    fun exportTo(context: Context, uri: Uri): Boolean {
        val sp = Prefs.sp(context)
        val entries = JSONArray()
        sp.all.forEach { (key, value) ->
            val o = JSONObject().put("k", key)
            when (value) {
                is String -> o.put("t", "s").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value)
                is Boolean -> o.put("t", "b").put("v", value)
                is Set<*> -> o.put("t", "ss").put(
                    "v",
                    JSONArray(value.filterIsInstance<String>())
                )
                else -> o.put("t", "s").put("v", value.toString())
            }
            entries.put(o)
        }
        val prefsJson = JSONObject().apply {
            put("version", 1)
            put("exported_at", System.currentTimeMillis())
            put("entries", entries)
        }
        val metaJson = JSONObject().apply {
            put("app", "StarRailCheckin")
            put("version", 1)
            put("exported_at", System.currentTimeMillis())
        }

        val out = context.contentResolver.openOutputStream(uri) ?: return false
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_META))
            zip.write(metaJson.toString().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(ENTRY_PREFS))
            zip.write(prefsJson.toString().toByteArray())
            zip.closeEntry()

            val gacha = File(context.filesDir, ENTRY_GACHA)
            if (gacha.exists()) {
                zip.putNextEntry(ZipEntry(ENTRY_GACHA))
                gacha.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return true
    }

    fun importFrom(context: Context, uri: Uri): ImportSummary {
        var prefsText: String? = null
        var gachaBytes: ByteArray? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        ENTRY_PREFS -> prefsText = zip.readBytes().toString(Charsets.UTF_8)
                        ENTRY_GACHA -> gachaBytes = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val text = prefsText ?: throw IllegalArgumentException("备份文件中缺少 prefs.json")
        val root = JSONObject(text)
        val arr = root.optJSONArray("entries") ?: throw IllegalArgumentException("备份格式不正确")
        val parsed = mutableListOf<Triple<String, String, Any>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            if (k.isBlank()) continue
            when (o.optString("t")) {
                "s" -> parsed.add(Triple(k, "s", o.optString("v")))
                "i" -> parsed.add(Triple(k, "i", o.optInt("v")))
                "l" -> parsed.add(Triple(k, "l", o.optLong("v")))
                "f" -> parsed.add(Triple(k, "f", o.optDouble("v").toFloat()))
                "b" -> parsed.add(Triple(k, "b", o.optBoolean("v")))
                "ss" -> {
                    val ja = o.optJSONArray("v") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (j in 0 until ja.length()) set.add(ja.optString(j))
                    parsed.add(Triple(k, "ss", set))
                }
            }
        }

        val ed = Prefs.sp(context).edit().clear()
        parsed.forEach { (k, t, v) ->
            when (t) {
                "s" -> ed.putString(k, v as String)
                "i" -> ed.putInt(k, v as Int)
                "l" -> ed.putLong(k, v as Long)
                "f" -> ed.putFloat(k, v as Float)
                "b" -> ed.putBoolean(k, v as Boolean)
                "ss" -> ed.putStringSet(k, v as Set<String>)
            }
        }
        ed.commit()

        var gachaRestored = false
        if (gachaBytes != null) {
            File(context.filesDir, ENTRY_GACHA).writeBytes(gachaBytes!!)
            gachaRestored = true
        }

        // 恢复后确保当前账号元信息与账号列表一致
        runCatching { Prefs.saveCurrentAccount(context) }
        LogHelper.i("Backup", "恢复完成 prefs=${parsed.size} gacha=$gachaRestored")
        return ImportSummary(
            prefCount = parsed.size,
            gachaRestored = gachaRestored,
            message = "已恢复 ${parsed.size} 项设置" + if (gachaRestored) "与抽卡历史" else ""
        )
    }
}
