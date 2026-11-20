package com.mh.starrailcheckin.util

import android.content.Context
import com.mh.starrailcheckin.network.MicaApi

/**
 * 体力与派遣提醒：
 * - 由 RemindWorker（15 分钟周期）与设置页「立即检查」调用；
 * - 只在体力「跨过阈值」或「刚刚回满」时提醒，避免每周期重复通知；
 * - 派遣全部完成时每天最多提醒一次。
 */
object StaminaMonitor {

    /** 周期检查（间隔可调，默认 10 分钟，避免频繁请求体力接口触发风控）。 */
    fun check(context: Context) {
        if (!Prefs.staminaNotifyEnabled(context)) return
        if (!Prefs.isLoggedIn(context)) return
        val minIntervalMs = Prefs.staminaIntervalMin(context) * 60_000L
        val now = System.currentTimeMillis()
        if (now - Prefs.staminaLastCheckAt(context) < minIntervalMs) return
        // 离线守卫：不写检查时间戳，避免离线把节流标记占掉
        if (!NetworkHelper.isOnline(context)) {
            LogHelper.w("StaminaMonitor", "当前离线，跳过体力检查")
            return
        }
        // 先取数成功再占节流：取数抛异常 / 返回 null 时本轮不封死节流，下一轮立即补跑
        val info = runCatching { MicaApi.fetchStamina(context) }.getOrNull() ?: run {
            LogHelper.w("StaminaMonitor", "体力接口返回空或异常，本轮不占节流")
            return
        }
        Prefs.setStaminaLastCheckAt(context, now)
        Prefs.setWorkerSuccessAt(context, Prefs.TASK_STAMINA, now)
        evaluate(context, info)
    }

    /** 设置页手动检查：返回可直接展示的结果文案。 */
    fun checkNow(context: Context): String {
        if (!Prefs.isLoggedIn(context)) return "请先登录米游社并绑定星穹铁道角色"
        val info = runCatching { MicaApi.fetchStamina(context) }
            .getOrElse { return "体力查询失败：${it.message ?: "网络异常"}" }
        Prefs.setStaminaLastCheckAt(context, System.currentTimeMillis())
        val sent = evaluate(context, info)
        val tail = if (sent > 0) "已发送 $sent 条提醒" else "暂无需要提醒的项目"
        return "当前体力 ${info.current}/${info.max}，$tail"
    }

    private fun evaluate(context: Context, info: MicaApi.StaminaInfo): Int {
        val last = Prefs.staminaLastCurrent(context)
        val threshold = Prefs.staminaThreshold(context)
        var sent = 0

        // 回满前预告：还差有限时间回满时提醒一次（每日每账号一次），避免等到溢出才发现
        if (Prefs.staminaPreFullNotify(context) &&
            info.current in 1 until info.max &&
            info.recoverTimeSec > 0 &&
            !Prefs.staminaPreFullNotifiedToday(context)
        ) {
            val remainMin = (info.recoverTimeSec + 59) / 60
            if (remainMin <= Prefs.staminaPreFullMin(context)) {
                NotifyHelper.showStaminaNotification(
                    context,
                    "开拓力即将回满",
                    "当前 ${info.current}/${info.max}，约 ${remainMin} 分钟后回满，记得及时清体力。"
                )
                Prefs.markStaminaPreFullNotified(context)
                sent++
            }
        }

        // 回满提醒（仅在「上次未满、这次满了」时触发一次）
        if (Prefs.staminaFullNotify(context) && last in 1 until info.max && info.current >= info.max) {
            NotifyHelper.showStaminaNotification(
                context,
                "开拓力已回满",
                "当前 ${info.current}/${info.max}，快去消耗体力，别让体力溢出。"
            )
            sent++
        }

        // 阈值提醒（跨过设定值时触发一次）
        if (threshold in 1 until info.max && last in 1 until threshold && info.current >= threshold) {
            NotifyHelper.showStaminaNotification(
                context,
                "开拓力已达 $threshold",
                "当前 ${info.current}/${info.max}，可以开始清体力了。"
            )
            sent++
        }

        // 派遣即将完成预告：单项剩余时间落在预告窗口内，按派遣名去重，避免周期重复打扰
        if (Prefs.expeditionPreDoneNotify(context) && info.expeditions.isNotEmpty()) {
            val preWindowSec = Prefs.expeditionPreDoneMin(context) * 60L
            val dueSoon = info.expeditions.filter {
                it.remainingTime in 1L..preWindowSec &&
                    !Prefs.expeditionPreDoneAlready(context, it.name.ifBlank { "派遣" })
            }
            if (dueSoon.isNotEmpty()) {
                val names = dueSoon.joinToString("、") { it.name.ifBlank { "派遣" } }
                NotifyHelper.showStaminaNotification(
                    context,
                    "派遣即将完成",
                    "$names 约在 ${Prefs.expeditionPreDoneMin(context)} 分钟内完成，记得上线领取奖励。"
                )
                dueSoon.forEach { Prefs.markExpeditionPreDone(context, it.name.ifBlank { "派遣" }) }
                sent++
            }
        }

        // 派遣完成提醒（全部完成，每天最多一次）
        if (Prefs.expeditionNotifyEnabled(context) &&
            info.expeditions.isNotEmpty() &&
            info.expeditions.all { it.remainingTime <= 0 } &&
            !Prefs.expeditionNotifiedToday(context)
        ) {
            NotifyHelper.showStaminaNotification(
                context,
                "派遣任务已完成",
                "共 ${info.expeditions.size} 项派遣已结束，记得上线领取奖励。"
            )
            Prefs.markExpeditionNotified(context)
            sent++
        }

        Prefs.setStaminaLastCurrent(context, info.current)
        LogHelper.i("StaminaMonitor", "check current=${info.current}/${info.max} threshold=$threshold sent=$sent")
        return sent
    }
}
