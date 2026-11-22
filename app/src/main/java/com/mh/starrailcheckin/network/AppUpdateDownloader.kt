package com.mh.starrailcheckin.network

import android.content.Context
import okhttp3.Request
import java.net.URL
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import com.mh.starrailcheckin.util.LogHelper

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
 * 前缀重试；跨源走 Range 断点续传，保留已下载部分，避免每次切源都从零开始。
 * 先写临时文件，全部源完成后再改名，避免安装到半成品。必须在 IO 线程调用。
 */
object AppUpdateDownloader {

    private const val TAG = "UpdateDownload"
    private const val UA = "StarRailCheckin-App"
    private const val FILE_NAME = "StarRailCheckin-update.apk"
    private const val TMP_NAME = "$FILE_NAME.tmp"
    private const val BUFFER_SIZE = 8 * 1024

    /** 下载专用客户端：读超时放宽到 60s，避免慢代理中途无新数据被误判超时切源。 */
    private val downloadClient by lazy {
        HttpClientProvider.builder(connectSec = 15, readSec = 60, writeSec = 15).build()
    }

    /**
     * @param expectedSize Release 声明的 APK 字节数，>0 时作为进度总大小基准并在完成后校验长度；0 表示不校验。
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
        var maxPercent = -1
        val startedAt = System.currentTimeMillis()

        for ((index, candidate) in candidates.withIndex()) {
            val attempt = runCatching {
                downloadFromSource(candidate, tmp, expectedSize) { percent, downloaded, total ->
                    // 进度单调不回退：跨源续传 / 重连时 UI 不掉百分比
                    val shown = if (percent < 0) percent else {
                        if (maxPercent < 0 || percent > maxPercent) maxPercent = percent
                        maxPercent
                    }
                    onProgress(shown, downloaded, total)
                }
            }
            if (attempt.isSuccess) {
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return UpdateDownloadResult.Failed("安装包保存失败")
                }
                LogHelper.i(TAG, "下载完成 ${target.length()} 字节，耗时 ${(System.currentTimeMillis() - startedAt) / 1000}s")
                return UpdateDownloadResult.Success(target)
            }
            // 保留 tmp 供下一源断点续传，仅记录失败原因，不立即清空
            lastReason = classify(attempt.exceptionOrNull())
            LogHelper.w(TAG, "下载源 #$index 失败：$lastReason（已下载 ${tmp.length()} 字节，源 ${hostOf(candidate)}）")
        }
        tmp.delete()
        return UpdateDownloadResult.Failed("所有下载源均失败：$lastReason")
    }

    /** 从单个候选源下载到临时文件；支持 Range 续传，失败抛带分类的异常。 */
    private fun downloadFromSource(
        source: String,
        tmp: File,
        expectedSize: Long,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit
    ) {
        val resumeFrom = if (tmp.exists() && tmp.length() > 0) tmp.length() else 0L
        val builder = Request.Builder()
            .url(source)
            .header("User-Agent", UA)
            .get()
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

        downloadClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code)
            val body = resp.body ?: throw IOException("下载内容为空")
            // 总大小优先用 Release 声明值，避免不同源 contentLength 不一致导致百分比基准跳变
            val total = if (expectedSize > 0) expectedSize
            else body.contentLength().let { if (it > 0) it else -1 }
            // 206 才是真正的断点续传；200 表示服务端不支持续传，从头覆盖
            val resumed = resp.code == 206 && resumeFrom > 0
            val append = resumed
            var downloaded = if (append) resumeFrom else 0L

            body.byteStream().use { input ->
                FileOutputStream(tmp, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
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

    /** 仅取源域名用于日志，不暴露完整链接。 */
    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull() ?: url

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
