package com.mh.starrailcheckin.util

import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 异常 → 用户可读文案的统一口径。
 *
 * 分三类，避免把内部技术细节或堆栈抛给用户：
 * - [MicaException]：按 retcode 走 [MicaApi.retcodeMessage]，缺省再回退原始 message；
 * - 网络 / IO 异常：统一提示检查网络后重试；
 * - 解析异常：提示数据解析失败；
 * - 其余未知异常：只给通用兜底文案，具体细节写入日志，不回显到界面。
 */
object UiError {

    fun message(e: Throwable, fallback: String = "操作失败，请稍后重试"): String = when (e) {
        is MicaException -> MicaApi.retcodeMessage(e.retcode).ifBlank { e.message ?: fallback }
        is UnknownHostException, is SocketTimeoutException -> "网络异常，请检查网络后重试"
        is IOException -> "网络异常，请检查网络后重试"
        is JSONException -> "数据解析失败，请稍后重试"
        else -> fallback
    }
}
