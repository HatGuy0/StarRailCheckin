package com.mh.starrailcheckin.network

import android.content.Context
import okhttp3.Request
import java.io.File

/** 更新包下载结果。 */
sealed interface UpdateDownloadResult {
    data class Success(val file: File) : UpdateDownloadResult
    data class Failed(val message: String) : UpdateDownloadResult
}

/**
 * 把 Release 中的 APK 下载到应用外部私有目录 `downloads/`。
 * 先写临时文件，完成后再改名，避免安装到半成品；下载失败会清理残留。
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

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .get()
                .build()

            HttpClientProvider.default.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching UpdateDownloadResult.Failed("下载失败（${resp.code}）")
                val body = resp.body ?: return@runCatching UpdateDownloadResult.Failed("下载内容为空")
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

            if (expectedSize > 0 && tmp.length() != expectedSize) {
                tmp.delete()
                return@runCatching UpdateDownloadResult.Failed("安装包大小校验不一致，请重试")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@runCatching UpdateDownloadResult.Failed("安装包保存失败")
            }
            UpdateDownloadResult.Success(target)
        }.getOrElse {
            tmp.delete()
            UpdateDownloadResult.Failed("网络连接失败，请稍后再试")
        }
    }
}
