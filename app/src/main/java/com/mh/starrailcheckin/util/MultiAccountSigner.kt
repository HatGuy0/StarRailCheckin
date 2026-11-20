package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.SignResult

/** 多账号批量/单独签到：临时切换登录态，结束后恢复原账号。 */
object MultiAccountSigner {

    data class ResultItem(
        val account: Prefs.Account,
        val success: Boolean,
        val message: String
    )

    private val lock = Any()

    fun signOne(context: Context, account: Prefs.Account): ResultItem = synchronized(lock) {
        val snapshot = Prefs.session(context)
        try {
            if (!Prefs.switchAccount(context, account.id)) {
                return ResultItem(account, false, "账号不存在")
            }
            val r = runCatching { MicaApi.doSignWithRetry(context) }.getOrElse {
                SignResult(false, it.message ?: "签到异常")
            }
            if (r.success) Prefs.markLastSign(context, account.id, System.currentTimeMillis())
            NotifyHelper.showSignResultNotification(
                context, r.success, account.nickname.ifBlank { account.uid }, r.message
            )
            ResultItem(account, r.success, r.message)
        } finally {
            Prefs.restoreSession(context, snapshot)
        }
    }

    fun signAll(context: Context, accounts: List<Prefs.Account>): List<ResultItem> = synchronized(lock) {
        val snapshot = Prefs.session(context)
        val results = mutableListOf<ResultItem>()
        try {
            accounts.forEach { account ->
                if (!Prefs.switchAccount(context, account.id)) {
                    results.add(ResultItem(account, false, "账号不存在"))
                    return@forEach
                }
                val r = runCatching { MicaApi.doSignWithRetry(context) }.getOrElse {
                    SignResult(false, it.message ?: "签到异常")
                }
                if (r.success) Prefs.markLastSign(context, account.id, System.currentTimeMillis())
                NotifyHelper.showSignResultNotification(
                    context, r.success, account.nickname.ifBlank { account.uid }, r.message
                )
                results.add(ResultItem(account, r.success, r.message))
            }
        } finally {
            Prefs.restoreSession(context, snapshot)
        }
        results
    }
}
