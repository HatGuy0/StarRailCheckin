package com.mh.starrailcheckin.network

import okhttp3.Request
import org.json.JSONObject

/** 远端最新 Release 信息（来自 GitHub Releases latest 接口）。 */
data class RemoteRelease(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String?,
    val releaseUrl: String,
    val apkSize: Long,
    val tag: String
)

/** 检查更新结果：已是最新 / 发现新版 / 检查失败。 */
sealed interface UpdateCheckResult {
    /** 已是最新版本。 */
    data class UpToDate(val localVersionName: String) : UpdateCheckResult

    /** 发现新版本。 */
    data class HasUpdate(val release: RemoteRelease) : UpdateCheckResult

    /** 检查失败（网络异常 / 解析失败 / 接口限流等）。 */
    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * 检查 GitHub Releases 是否有新版本。
 *
 * 版本比对一律以整数 versionCode 为准，不比较 versionName 字符串。
 * versionCode 优先从 Release 正文 `versionCode: 67`（兼容中文冒号）解析，
 * 兜底取 tag 中第一个数字；versionName 优先取正文，兜底用 tag。
 */
object UpdateChecker {

    private const val REPO_OWNER = "HatGuy0"
    private const val REPO_NAME = "StarRailCheckin"
    private const val LATEST_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val UA = "StarRailCheckin-App"

    /**
     * 拉取最新 Release 并与本地版本比对。必须在 IO 线程调用；
     * 任何异常都收敛为 [UpdateCheckResult.Failed]，不向上抛出打断页面。
     */
    fun check(localVersionCode: Int, localVersionName: String): UpdateCheckResult = runCatching {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UA)
            .get()
            .build()
        HttpClientProvider.default.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return@runCatching UpdateCheckResult.Failed("服务器返回异常（${resp.code}）")
            }
            val body = resp.body?.string().orEmpty()
            val release = parse(JSONObject(body))
                ?: return@runCatching UpdateCheckResult.Failed("无法解析版本信息")
            if (release.versionCode > localVersionCode) {
                UpdateCheckResult.HasUpdate(release)
            } else {
                UpdateCheckResult.UpToDate(localVersionName)
            }
        }
    }.getOrElse { UpdateCheckResult.Failed("网络连接失败，请稍后再试") }

    private fun parse(json: JSONObject): RemoteRelease? {
        val tag = json.optString("tag_name").ifBlank { return null }
        val notes = json.optString("body").orEmpty()
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    apkSize = asset.optLong("size")
                    break
                }
            }
        }
        val versionCode = parseVersionCode(notes, tag) ?: return null
        val versionName = parseVersionName(notes) ?: tag.removePrefix("v")
        val releaseUrl = json.optString("html_url").ifBlank {
            "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$tag"
        }
        return RemoteRelease(
            versionCode = versionCode,
            versionName = versionName,
            releaseNotes = notes.trim(),
            apkUrl = apkUrl,
            releaseUrl = releaseUrl,
            apkSize = apkSize,
            tag = tag
        )
    }

    /** 优先正文 `versionCode: 67`，兜底 tag 中第一个数字。 */
    private fun parseVersionCode(notes: String, tag: String): Int? {
        Regex("versionCode\\s*[:：]\\s*(\\d+)").find(notes)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("(\\d+)").find(tag)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun parseVersionName(notes: String): String? =
        Regex("versionName\\s*[:：]\\s*(\\S+)").find(notes)?.let {
            it.groupValues[1].trim().trim('-').ifBlank { null }
        }
}
