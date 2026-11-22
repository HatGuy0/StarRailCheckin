package com.mh.starrailcheckin.ui.profile

import android.content.Context
import com.mh.starrailcheckin.network.ApiHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * miao-plugin 角色数据源：resources/meta-sr/character/{中文名}/data.json。
 *
 * 与 StarRailRes 的裸 params 数组不同，miao 的 talent.{a,e,q,t,xe,z}.tables 是【带命名行】的：
 *   "tables": { "1": { "name": "欢愉伤害", "values": [0.25, ...] }, ... }
 * 按行名取倍率，彻底避免取错槽位（阿哈战技 [欢愉度, 追加攻击倍率] 取 [0] 的 bug）。
 *
 * 数据随 miao-plugin 上游更新，新角色 data.json 落地后 App 自动可用。
 */
object MiaoDataClient {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private var bundled: JSONObject? = null

    private fun mirrors(charName: String): List<String> {
        val enc = URLEncoder.encode(charName, "UTF-8")
        val path = "resources/meta-sr/character/$enc/data.json"
        return listOf(
            "${ApiHosts.GITHUB_RAW_MIAO}/$path",
            "${ApiHosts.GH_PROXIES[0]}${ApiHosts.GITHUB_RAW_MIAO}/$path",
            "${ApiHosts.GH_PROXIES[1]}${ApiHosts.GITHUB_RAW_MIAO}/$path",
            "${ApiHosts.JSDELIVERT_MIAO}/$path"
        )
    }

    /** 确保角色数据已加载（内存 → 磁盘 → 上游 → 内置资产）。 */
    suspend fun ensureLoaded(ctx: Context, charName: String): Boolean {
        if (cache.containsKey(charName)) return true
        return withContext(Dispatchers.IO) {
            val disk = File(ctx.cacheDir, "miao_${charName.hashCode()}.json")
            if (disk.exists() && disk.length() > 2000) {
                runCatching { JSONObject(disk.readText()) }.getOrNull()?.let {
                    cache[charName] = it
                    return@withContext true
                }
            }
            for (url in mirrors(charName)) {
                val body = httpGet(url) ?: continue
                val parsed = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (parsed.optJSONObject("talent") != null) {
                    cache[charName] = parsed
                    runCatching { disk.writeText(parsed.toString()) }
                    return@withContext true
                }
            }
            val local = loadBundled(ctx, charName)
            if (local != null) {
                cache[charName] = local
                return@withContext true
            }
            false
        }
    }

    private fun loadBundled(ctx: Context, charName: String): JSONObject? {
        val root = bundled ?: runCatching {
            ctx.assets.open("miao_data.json").bufferedReader().use { JSONObject(it.readText()) }
        }.getOrNull()?.also { bundled = it } ?: return null
        return root.optJSONObject("characters")?.optJSONObject(charName)
    }

    fun loaded(charName: String): Boolean = cache.containsKey(charName)

    fun data(charName: String): JSONObject? = cache[charName]

    /**
     * 取命名行倍率。
     * @param talentKey "a"/"e"/"q"/"t"/"xe"/"z"
     * @param rowName   tables 里的行名，如 "欢愉伤害" / "追加攻击欢愉伤害" / "全队暴击伤害提高"
     * @param level     技能等级（1-based），自动 clamp 到 values 长度
     */
    fun tableValue(charName: String, talentKey: String, rowName: String, level: Int): Double? {
        val talent = cache[charName]?.optJSONObject("talent")?.optJSONObject(talentKey) ?: return null
        val tables = talent.optJSONObject("tables") ?: return null
        val it = tables.keys()
        while (it.hasNext()) {
            val row = tables.optJSONObject(it.next()) ?: continue
            if (row.optString("name") != rowName) continue
            val values = row.optJSONArray("values") ?: return null
            if (values.length() == 0) return null
            val idx = (level - 1).coerceIn(0, values.length() - 1)
            val v = values.opt(idx)
            return if (v is Number) v.toDouble() else null
        }
        return null
    }

    /** 技能名（用于 UI 完整标题）。 */
    fun talentName(charName: String, talentKey: String): String? =
        cache[charName]?.optJSONObject("talent")?.optJSONObject(talentKey)?.optString("name")?.ifBlank { null }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            }
            conn = c
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }
        finally { runCatching { conn?.disconnect() } }
    }
}
