package com.mh.starrailcheckin.network

import okhttp3.Request
import org.json.JSONObject

/**
 * 作者 B 站公开资料拉取（免登录 card 接口）。
 * 仅用于「关于」页展示作者昵称与头像；接口受风控影响可能失败，
 * 失败时由调用方回退到本地内置昵称与头像，不抛异常打断页面。
 */
data class BiliProfile(val name: String, val face: String)

object BilibiliApi {

    const val MID = "517768640"
    const val SPACE_URL = "https://space.bilibili.com/$MID"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 拉取作者公开昵称与头像；失败 / 风控 / 空字段均返回 null。 */
    fun fetchProfile(): BiliProfile? = runCatching {
        val url = "https://api.bilibili.com/x/web-interface/card?mid=$MID&photo=false"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", SPACE_URL)
            .get()
            .build()
        HttpClientProvider.default.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val j = JSONObject(text)
            if (j.optInt("code", -1) != 0) return null
            val data = j.optJSONObject("data") ?: return null
            val card = data.optJSONObject("card") ?: data
            var name = card.optString("name", "").ifBlank { data.optString("name", "") }
            var face = card.optString("face", "").ifBlank { data.optString("face", "") }
            // 协议相对地址补全为 https
            if (face.startsWith("//")) face = "https:$face"
            if (name.isBlank() && face.isBlank()) return null
            BiliProfile(name.trim(), face.trim())
        }
    }.getOrNull()
}
