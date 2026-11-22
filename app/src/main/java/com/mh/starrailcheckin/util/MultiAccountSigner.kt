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
        val label = account.nickname.ifBlank { account.uid }
        val snapshot = Prefs.session(context)
        try {
            if (!Prefs.switchAccount(context, account.id)) {
                LogHelper.w("Sign", "单账号签到：账号不存在 $label")
                return ResultItem(account, false, "账号不存在")
            }
            LogHelper.i("Sign", "手动单账号签到 account=$label")
            val r = runCatching { MicaApi.doSignWithRetry(context) }.getOrElse {
                SignResult(false, UiError.message(it, "签到异常"))
            }
            if (r.success) Prefs.markLastSign(context, account.id, System.currentTimeMillis())
            NotifyHelper.showSignResultNotification(
                context, r.success, label, r.message
            )
            LogHelper.i("Sign", "单账号签到结果 success=${r.success} account=$label msg=${r.message}")
            ResultItem(account, r.success, r.message)
        } finally {
            Prefs.restoreSession(context, snapshot)
        }
    }

    fun signAll(context: Context, accounts: List<Prefs.Account>): List<ResultItem> = synchronized(lock) {
        LogHelper.i("Sign", "批量签到开始，共 ${accounts.size} 个账号")
        val snapshot = Prefs.session(context)
        val results = mutableListOf<ResultItem>()
        try {
            accounts.forEach { account ->
                val label = account.nickname.ifBlank { account.uid }
                if (!Prefs.switchAccount(context, account.id)) {
                    LogHelper.w("Sign", "批量签到：账号不存在 $label")
                    results.add(ResultItem(account, false, "账号不存在"))
                    return@forEach
                }
                val r = runCatching { MicaApi.doSignWithRetry(context) }.getOrElse {
                    SignResult(false, UiError.message(it, "签到异常"))
                }
                if (r.success) Prefs.markLastSign(context, account.id, System.currentTimeMillis())
                NotifyHelper.showSignResultNotification(
                    context, r.success, label, r.message
                )
                LogHelper.i("Sign", "批量签到结果 success=${r.success} account=$label msg=${r.message}")
                results.add(ResultItem(account, r.success, r.message))
            }
        } finally {
            Prefs.restoreSession(context, snapshot)
        }
        val ok = results.count { it.success }
        LogHelper.i("Sign", "批量签到完成，成功 $ok/${results.size}")
        results
    }
}
