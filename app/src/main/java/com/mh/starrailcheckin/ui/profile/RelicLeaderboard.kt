package com.mh.starrailcheckin.ui.profile

import com.mh.starrailcheckin.network.ApiHosts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * scoremyrelic 遗器评分榜（公开接口，服务端每小时刷新）。
 *
 * GET /api/leaderboards/{avatarId}               第 1 页，含 totalCount / lastUpdated / 前 50 名
 * GET /api/leaderboards/{avatarId}?page=N        第 N 页
 * GET /api/leaderboards/{avatarId}/find/{uid}    指定账号名次（仅收录账号有结果）
 *
 * 本地评分与榜单同为遗器 RS 口径，可直接插入排序估算名次；接口失败时由 UI 兜底。
 */
object RelicLeaderboard {
    private const val BASE = ApiHosts.SCOREMYRELIC_BASE
    private const val PAGE_SIZE = 50
    private const val MAX_PAGE_REQUESTS = 12

    data class Result(
        val total: Int,
        val myRank: Int,          // 1 起
        val estimated: Boolean,
        val topName: String,
        val topScore: Double,
        val updatedText: String,
        val error: String? = null
    )

    suspend fun query(aid: Int, uid: String, score: Double): Result? =
        withContext(Dispatchers.IO) {
            val first = fetchPage(aid, 0)
                ?: return@withContext Result(0, 0, false, "", 0.0, "", "network")
            val total = first.total
            if (total <= 0) return@withContext null

            val topName = first.players.firstOrNull()?.name.orEmpty()
            val topScore = first.players.firstOrNull()?.score ?: 0.0
            val updated = formatTime(first.updatedAt)

            // 1) 优先用官方 find 拿精确名次
            val found = findRank(aid, uid)
            if (found != null) {
                return@withContext Result(
                    total = total,
                    myRank = found,
                    estimated = false,
                    topName = topName,
                    topScore = topScore,
                    updatedText = updated
                )
            }

            // 2) 未收录则用本地评分估算插入位置
            if (score <= 0.0) return@withContext null
            val rank = estimateRank(aid, score, total)
                ?: return@withContext Result(
                    total = total,
                    myRank = 0,
                    estimated = true,
                    topName = topName,
                    topScore = topScore,
                    updatedText = updated
                )
            Result(
                total = total,
                myRank = rank.coerceIn(1, total + 1),
                estimated = true,
                topName = topName,
                topScore = topScore,
                updatedText = updated
            )
        }

    // ==================== 网络 ====================

    private data class Player(val name: String, val score: Double)

    private data class Page(
        val total: Int,
        val updatedAt: String,
        val players: List<Player>
    )

    private fun fetchPage(aid: Int, page: Int): Page? {
        val url = if (page == 0) "$BASE/$aid" else "$BASE/$aid?page=$page"
        val body = get(url) ?: return null
        val trimmed = body.trim()
        // 越界页返回 []：视为空页，而不是网络失败（否则会被误判成"最后一名"）
        if (trimmed.startsWith("[")) {
            return Page(total = 0, updatedAt = "", players = emptyList())
        }
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val arr = obj.optJSONArray("players") ?: return null
        val players = ArrayList<Player>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            players.add(
                Player(
                    name = o.optString("displayName", ""),
                    score = o.optDouble("totalRs", 0.0)
                )
            )
        }
        return Page(
            total = obj.optInt("totalCount", 0),
            updatedAt = obj.optString("lastUpdated", ""),
            players = players
        )
    }

    /** 返回 1 起的精确名次；未收录或请求失败返回 null。 */
    private fun findRank(aid: Int, uid: String): Int? {
        if (uid.isBlank() || uid == "0") return null
        val body = get("$BASE/$aid/find/$uid") ?: return null
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (obj.has("error")) return null
        val rank = obj.optInt("rank", -1)
        return if (rank >= 0) rank + 1 else null
    }

    /** 榜单按 totalRs 倒序，二分页码定位插入位置，最多请求 10 次。 */
    private fun estimateRank(aid: Int, score: Double, total: Int): Int? {
        var lo = 0
        var hi = (total - 1) / PAGE_SIZE
        var answer: Int? = null
        var guard = 0
        while (lo <= hi && guard < MAX_PAGE_REQUESTS) {
            guard++
            val mid = (lo + hi) / 2
            val page = fetchPage(aid, mid) ?: return answer?.plus(1)
            if (page.players.isEmpty()) {
                hi = mid - 1
                continue
            }
            val firstIdx = mid * PAGE_SIZE
            val firstScore = page.players.first().score
            val lastIdx = firstIdx + page.players.size - 1
            val lastScore = page.players.last().score

            when {
                score >= firstScore -> {
                    answer = firstIdx
                    hi = mid - 1
                }
                score < lastScore -> {
                    lo = mid + 1
                    if (lastIdx >= total - 1) answer = total
                }
                else -> {
                    for (i in page.players.indices) {
                        if (score >= page.players[i].score) return firstIdx + i + 1
                    }
                    return lastIdx + 2
                }
            }
        }
        return answer?.plus(1)
    }

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                setRequestProperty("Accept", "application/json")
            }
            conn = c
            if (c.responseCode != 200) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun formatTime(raw: String): String {
        if (raw.isBlank()) return ""
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)
            }.getOrNull() ?: continue
            return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
        }
        return raw
    }
}
