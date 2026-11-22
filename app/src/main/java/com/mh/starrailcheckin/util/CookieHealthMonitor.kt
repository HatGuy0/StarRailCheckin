package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException

/** 当前账号的 Cookie 健康检查与自动续期。所有网络调用均为阻塞式。 */
object CookieHealthMonitor {
    const val STATUS_UNKNOWN = "unknown"
    const val STATUS_HEALTHY = "healthy"
    const val STATUS_INVALID = "invalid"
    const val STATUS_ERROR = "error"

    private const val INVALID_MESSAGE = "Cookie 可能已失效，请重新登录"
    private val authErrorCodes = setOf(-100, -101, 10001)
    private val riskControlCodes = setOf(10041, 10045)

    data class CheckResult(
        val status: String,
        val message: String,
        val checkedAt: Long,
        val renewed: Boolean = false
    )

    /** 周期检查：任一监控开关开启时执行，间隔可调（默认 1 小时）。 */
    @Synchronized
    fun checkIfDue(context: Context) {
        if (!Prefs.cookieHealthCheckEnabled(context) && !Prefs.cookieAutoRenewEnabled(context)) return
        if (Prefs.cookie(context).isBlank()) return
        val accountId = Prefs.activeAccountId(context)
        val state = Prefs.cookieHealthState(context)
        val minIntervalMs = Prefs.cookieCheckIntervalMin(context) * 60_000L
        val elapsed = System.currentTimeMillis() - state.lastCheckAt
        if (state.accountId == accountId && elapsed in 0L until minIntervalMs) {
            LogHelper.i("CookieHealth", "节流中，${(minIntervalMs - elapsed) / 60_000L} 分钟后再检查")
            return
        }
        // 离线守卫：不更新检查时刻，避免离线占掉节流窗口
        if (!NetworkHelper.isOnline(context)) {
            LogHelper.w("CookieHealth", "当前离线，跳过 Cookie 检查")
            return
        }
        checkNow(context)
    }

    /** 立即检查当前账号；认证失效时才进入续期或失效提醒流程。 */
    @Synchronized
    fun checkNow(context: Context): CheckResult {
        val accountId = Prefs.activeAccountId(context)
        val rawCookie = Prefs.cookie(context)
        val previous = stateFor(context, accountId)
        val checkedAt = System.currentTimeMillis()
        try {
            MicaApi.validateAccount(context)
            if (!isSameCurrentAccount(context, accountId, rawCookie)) return staleResult()
            val message = "Cookie 正常，角色绑定可用"
            Prefs.saveCookieHealthState(
                context,
                Prefs.CookieHealthState(
                    accountId = accountId,
                    status = STATUS_HEALTHY,
                    message = message,
                    lastCheckAt = checkedAt,
                    lastRenewAt = previous?.lastRenewAt ?: 0L,
                    invalidNotified = false
                )
            )
            NotifyHelper.cancelCookieHealthNotification(context)
            Prefs.setWorkerSuccessAt(context, Prefs.TASK_COOKIE, checkedAt)
            LogHelper.i("CookieHealth", "当前账号 Cookie 检查通过")
            return CheckResult(STATUS_HEALTHY, message, checkedAt)
        } catch (e: Exception) {
            if (!isSameCurrentAccount(context, accountId, rawCookie)) return staleResult()
            if (!isAuthInvalid(e) || rawCookie.isBlank()) {
                return saveCheckError(context, accountId, checkedAt, errorMessage(e), previous)
            }

            if (Prefs.cookieAutoRenewEnabled(context)) {
                if (MicaApi.cookieSupportsAutoRenew(rawCookie)) {
                    return renewInternal(
                        context = context,
                        accountId = accountId,
                        automatic = true,
                        knownInvalid = true,
                        checkTime = checkedAt
                    )
                }
                val detail = "当前 Cookie 不含 stoken，不支持自动续期"
                return saveInvalid(
                    context, accountId, checkedAt, "$INVALID_MESSAGE；$detail", detail, previous
                )
            }

            val detail = errorMessage(e)
            return saveInvalid(
                context, accountId, checkedAt, "$INVALID_MESSAGE：$detail", detail, previous
            )
        }
    }

    /** 立即续期并再次验证角色绑定，返回可直接展示的结果。 */
    @Synchronized
    fun renewNow(context: Context): String {
        val accountId = Prefs.activeAccountId(context)
        val rawCookie = Prefs.cookie(context)
        val previous = stateFor(context, accountId)
        if (rawCookie.isBlank()) {
            val message = "无法续期：请先登录米游社"
            saveError(context, accountId, previous?.lastCheckAt ?: 0L, message, previous)
            return message
        }
        if (!MicaApi.cookieSupportsAutoRenew(rawCookie)) {
            val detail = "当前 Cookie 不含 stoken，需重新登录获取 stoken"
            val knownInvalid = previous?.status == STATUS_INVALID
            val message = if (knownInvalid) "无法续期：$detail；$INVALID_MESSAGE" else "无法续期：$detail"
            if (knownInvalid) {
                saveInvalid(
                    context, accountId, previous.lastCheckAt, message, detail, previous
                )
            } else {
                saveError(context, accountId, previous?.lastCheckAt ?: 0L, message, previous)
            }
            return message
        }
        return renewInternal(
            context = context,
            accountId = accountId,
            automatic = false,
            knownInvalid = previous?.status == STATUS_INVALID,
            checkTime = null
        ).message
    }

    private fun renewInternal(
        context: Context,
        accountId: String,
        automatic: Boolean,
        knownInvalid: Boolean,
        checkTime: Long?
    ): CheckResult {
        val previous = stateFor(context, accountId)
        try {
            MicaApi.renewCookie(context)
            MicaApi.validateAccount(context)
            if (Prefs.activeAccountId(context) != accountId) return staleResult()
            val now = System.currentTimeMillis()
            val message = if (automatic) "Cookie 已自动续期，复验通过" else "Cookie 续期成功，复验通过"
            Prefs.saveCookieHealthState(
                context,
                Prefs.CookieHealthState(
                    accountId = accountId,
                    status = STATUS_HEALTHY,
                    message = message,
                    lastCheckAt = now,
                    lastRenewAt = now,
                    invalidNotified = false
                )
            )
            NotifyHelper.cancelCookieHealthNotification(context)
            Prefs.setWorkerSuccessAt(context, Prefs.TASK_COOKIE, now)
            LogHelper.i("CookieHealth", if (automatic) "Cookie 自动续期并复验成功" else "Cookie 手动续期并复验成功")
            return CheckResult(STATUS_HEALTHY, message, now, renewed = true)
        } catch (e: Exception) {
            if (Prefs.activeAccountId(context) != accountId) return staleResult()
            val reason = errorMessage(e)
            val action = if (automatic) "自动续期失败" else "续期失败"
            val detail = "$action：$reason"
            val lastCheckAt = checkTime ?: previous?.lastCheckAt ?: 0L
            if (!isRiskControl(e) && (knownInvalid || isAuthInvalid(e))) {
                return saveInvalid(
                    context, accountId, lastCheckAt, "$detail；$INVALID_MESSAGE", detail, previous
                )
            }
            saveError(context, accountId, lastCheckAt, detail, previous)
            LogHelper.e("CookieHealth", detail, e)
            return CheckResult(STATUS_ERROR, detail, lastCheckAt)
        }
    }

    private fun saveCheckError(
        context: Context,
        accountId: String,
        checkedAt: Long,
        reason: String,
        previous: Prefs.CookieHealthState?
    ): CheckResult {
        val message = "检查失败：$reason"
        saveError(context, accountId, checkedAt, message, previous)
        LogHelper.w("CookieHealth", message)
        return CheckResult(STATUS_ERROR, message, checkedAt)
    }

    private fun saveError(
        context: Context,
        accountId: String,
        checkedAt: Long,
        message: String,
        previous: Prefs.CookieHealthState?
    ) {
        Prefs.saveCookieHealthState(
            context,
            Prefs.CookieHealthState(
                accountId = accountId,
                status = STATUS_ERROR,
                message = message,
                lastCheckAt = checkedAt,
                lastRenewAt = previous?.lastRenewAt ?: 0L,
                invalidNotified = previous?.invalidNotified ?: false
            )
        )
    }

    private fun saveInvalid(
        context: Context,
        accountId: String,
        checkedAt: Long,
        message: String,
        notificationDetail: String,
        previous: Prefs.CookieHealthState?
    ): CheckResult {
        val monitoringEnabled = Prefs.cookieHealthCheckEnabled(context) ||
            Prefs.cookieAutoRenewEnabled(context)
        val shouldNotify = monitoringEnabled && previous?.invalidNotified != true
        if (shouldNotify) NotifyHelper.showCookieHealthNotification(context, notificationDetail)
        Prefs.saveCookieHealthState(
            context,
            Prefs.CookieHealthState(
                accountId = accountId,
                status = STATUS_INVALID,
                message = message,
                lastCheckAt = checkedAt,
                lastRenewAt = previous?.lastRenewAt ?: 0L,
                invalidNotified = previous?.invalidNotified == true || shouldNotify
            )
        )
        LogHelper.w("CookieHealth", message)
        return CheckResult(STATUS_INVALID, message, checkedAt)
    }

    private fun stateFor(context: Context, accountId: String): Prefs.CookieHealthState? =
        Prefs.cookieHealthState(context).takeIf { it.accountId == accountId }

    private fun isSameCurrentAccount(context: Context, accountId: String, rawCookie: String): Boolean =
        Prefs.activeAccountId(context) == accountId && Prefs.cookie(context) == rawCookie

    private fun isAuthInvalid(error: Throwable): Boolean =
        error is MicaException && error.retcode in authErrorCodes

    private fun isRiskControl(error: Throwable): Boolean =
        error is MicaException && error.retcode in riskControlCodes

    private fun errorMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun staleResult() = CheckResult(STATUS_UNKNOWN, "账号或 Cookie 已变化，本次结果未保存", 0L)
}
