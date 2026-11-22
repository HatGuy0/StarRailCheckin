package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.SignResult
import java.util.Calendar

/**
 * 签到统一入口：手动签到失败自动重试一次；每日自动签到支持当日退避重试。
 *
 * 自动签到退避策略：
 * - 成功：清空重试状态；
 * - 网络/瞬时失败：按 30min → 60min → 120min 退避，当日最多 [MAX_RETRY] 次；
 * - 认证失效 / 风控 / 网页拦截：不重试，当天标记最终失败；
 * - 离线：本轮直接跳过，不写「已尝试/最终失败」标记，等下个周期再试。
 */
object SignHelper {

    private const val MAX_RETRY = 3

    /** 手动签到：失败自动重试一次，并推送签到结果通知。 */
    fun signAndNotify(context: Context, accountLabel: String = Prefs.nickname(context)): SignResult {
        val result = MicaApi.doSignWithRetry(context)
        if (result.success) Prefs.markSignSuccess(context)
        NotifyHelper.showSignResultNotification(
            context,
            result.success,
            accountLabel,
            result.message
        )
        LogHelper.i("Sign", "签到结果 success=${result.success} msg=${result.message}")
        return result
    }

    /**
     * 每日自动签到。由后台周期任务检查：
     * - 开关关闭 / 未登录 / 今天已成功 / 未到设定小时 -> 跳过
     * - 离线 -> 跳过本轮，不封死当天
     * - 失败按退避策略当日重试；达到上限或判定不可重试 -> 通知一次并封死当天
     */
    fun autoSignIfDue(context: Context) {
        if (!Prefs.autoSignEnabled(context)) {
            LogHelper.i("Sign", "自动签到开关关闭，跳过")
            return
        }
        if (!Prefs.isLoggedIn(context)) {
            LogHelper.i("Sign", "未登录，跳过自动签到")
            return
        }
        val today = Prefs.todayStr()
        if (Prefs.lastSignSuccess(context) == today) {
            LogHelper.i("Sign", "今日已签到成功，跳过")
            return
        }

        // 今日已最终失败（认证失效/风控/重试上限），不再打扰
        if (Prefs.autoSignFinalFailedDay(context) == today) {
            LogHelper.i("Sign", "今日签到已最终失败，不再重试")
            return
        }

        val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (nowHour < Prefs.autoSignHour(context)) {
            LogHelper.i("Sign", "未到签到时段（$nowHour < ${Prefs.autoSignHour(context)}），跳过")
            return
        }

        // 退避时刻未到
        val nextAt = Prefs.autoSignNextRetryAt(context)
        if (nextAt > System.currentTimeMillis()) {
            val remainMin = (nextAt - System.currentTimeMillis()) / 60_000L
            LogHelper.i("Sign", "签到退避中，约 ${remainMin} 分钟后再试")
            return
        }

        // 离线守卫：不写任何节流/失败标记
        if (!NetworkHelper.isOnline(context)) {
            LogHelper.w("Sign", "当前离线，跳过本轮自动签到")
            return
        }

        LogHelper.i("Sign", "开始每日自动签到 uid=${Prefs.uid(context)}")
        val result = runCatching { MicaApi.doSignWithRetry(context) }
            .onFailure { LogHelper.e("Sign", "自动签到请求异常", it) }
            .getOrElse { SignResult(false, UiError.message(it, "自动签到异常")) }

        if (result.success) {
            Prefs.markSignSuccess(context)
            Prefs.resetAutoSignRetry(context)
            Prefs.setWorkerSuccessAt(context, Prefs.TASK_SIGN, System.currentTimeMillis())
            NotifyHelper.showSignResultNotification(
                context, true, Prefs.nickname(context), "自动签到：${result.message}"
            )
            LogHelper.i("Sign", "自动签到成功 msg=${result.message}")
            return
        }

        val retryable = isRetryable(result.message)
        val count = Prefs.autoSignRetryCount(context)
        if (!retryable || count >= MAX_RETRY) {
            Prefs.setAutoSignFinalFailedDay(context, today)
            Prefs.setAutoSignNextRetryAt(context, 0L)
            NotifyHelper.showSignResultNotification(
                context, false, Prefs.nickname(context), "自动签到：${result.message}"
            )
            LogHelper.w("Sign", "自动签到最终失败（不可重试或已达上限 $MAX_RETRY）：${result.message}")
            return
        }

        val newCount = count + 1
        val delayMin = backoffMinutes(newCount)
        Prefs.setAutoSignRetryCount(context, newCount)
        Prefs.setAutoSignNextRetryAt(context, System.currentTimeMillis() + delayMin * 60_000L)
        LogHelper.w("Sign", "签到失败 $newCount/$MAX_RETRY，${delayMin} 分钟后退避重试：${result.message}")
    }

    /** 认证失效 / 风控 / 网页拦截类错误不做自动重试，避免重复提交触发风控。 */
    private fun isRetryable(message: String): Boolean {
        val blocked = listOf(
            "失效", "风控", "网页", "重新登录", "登录态", "stoken",
            "10041", "10045", "-100", "-101", "10001"
        )
        return blocked.none { message.contains(it, ignoreCase = true) }
    }

    private fun backoffMinutes(retry: Int): Long = when (retry) {
        1 -> 30L
        2 -> 60L
        else -> 120L
    }
}
