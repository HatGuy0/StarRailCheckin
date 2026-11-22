package com.mh.starrailcheckin.network

import android.content.Context
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 更新包下载结果。 */
sealed interface UpdateDownloadResult {
    data class Success(val file: File) : UpdateDownloadResult
    data class Failed(val message: String) : UpdateDownloadResult
}

/** HTTP 非 2xx 响应。 */
private class HttpStatusException(val code: Int) : IOException("服务器返回 $code")

/** 下载字节数与 Release 声明大小不一致。 */
private class SizeMismatchException : IOException("安装包大小校验不一致")

/**
 * 把 Release 中的 APK 下载到应用外部私有目录 `downloads/`。
 *
 * 下载链路多源回退：先直连 GitHub 原始链接，失败后依次套用 [ApiHosts.GH_PROXIES]
 * 前缀重试；先写临时文件，全部源完成后再改名，避免安装到半成品，任一源失败都会清理残留。
 * 必须在 IO 线程调用。
 */
object AppUpdateDownloader {

    private const val UA = "StarRailCheckin-App"
    private const val FILE_NAME = "StarRailCheckin-update.apk"
    private const val TMP_NAME = "$FILE_NAME.tmp"
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * @param expectedSize Release 声明的 APK 字节数，>0 时用于完成后校验长度；0 表示不校验。
     * @param onProgress 进度回调（在 IO 线程）：百分比 0..100（未知为 -1）、已下载字节、总字节（未知为 -1）。
     */
    fun download(
        context: Context,
        url: String,
        expectedSize: Long,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ): UpdateDownloadResult {
        val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, TMP_NAME)
        target.delete()
        tmp.delete()

        val candidates = buildList {
            add(url)
            ApiHosts.GH_PROXIES.forEach { prefix -> add(prefix + url) }
        }

        var lastReason = "网络连接失败，请稍后再试"
        for (candidate in candidates) {
            val attempt = runCatching {
                downloadFromSource(candidate, tmp, expectedSize, onProgress)
            }
            if (attempt.isSuccess) {
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return UpdateDownloadResult.Failed("安装包保存失败")
                }
                return UpdateDownloadResult.Success(target)
            }
            tmp.delete()
            lastReason = classify(attempt.exceptionOrNull())
        }
        return UpdateDownloadResult.Failed("所有下载源均失败：$lastReason")
    }

    /** 从单个候选源下载到临时文件；失败抛带分类的异常。 */
    private fun downloadFromSource(
        source: String,
        tmp: File,
        expectedSize: Long,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ) {
        val req = Request.Builder()
            .url(source)
            .header("User-Agent", UA)
            .get()
            .build()

        HttpClientProvider.default.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
            val body = resp.body ?: throw IOException("下载内容为空")
            val total = body.contentLength().let { if (it > 0) it else expectedSize }

            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent, downloaded, total)
                        }
                    }
                    output.flush()
                }
            }
        }

        if (expectedSize > 0 && tmp.length() != expectedSize) throw SizeMismatchException()
    }

    /** 异常 → 用户可读文案：区分超时 / 解析失败 / SSL / 连接拒绝 / 服务器状态码。 */
    private fun classify(e: Throwable?): String = when (e) {
        is HttpStatusException -> "服务器返回 ${e.code}"
        is SizeMismatchException -> "安装包大小校验不一致，请重试"
        is SocketTimeoutException -> "下载超时，请换网络或重试"
        is UnknownHostException -> "域名解析失败，请检查网络"
        is SSLException -> "安全连接失败（证书异常）"
        is ConnectException -> "连接被拒绝，请稍后重试"
        is IOException -> "网络连接失败，请稍后再试"
        else -> e?.message ?: "网络连接失败，请稍后再试"
    }
}
