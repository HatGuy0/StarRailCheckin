package com.mh.starrailcheckin.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 提醒音效 + 持续震动（弹窗Activity/悬浮窗/接收器/服务/Worker共用）
 *
 * 【关键】音效/震动由真正上屏的弹窗载体接管播放（Activity onCreate / 悬浮窗渲染成功后），
 * 不在广播层提前响，避免窗未上屏先响；载体全失败时由兜底通知的系统音兜底。
 *
 * 【持续震动（用户反馈修复）】震动为循环波形（600ms震-300ms停，无限重复），
 * 弹窗关闭时 stop() 停止；若用户始终未处理，120s后自动停止（避免无限耗电），
 * 由 15 分钟周期兜底任务在下一轮重新震动提醒。
 */
object RemindSound {
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibStopTask: Runnable? = null
    private var vibratorContext: Context? = null

    /** 设置提醒音效URI */
    suspend fun setSoundUri(context: Context, uri: String) {
        Prefs.setRemindSoundUri(context, uri)
    }

    fun play(context: Context) {
        stop()
        val vibrateOn = Prefs.remindVibrate(context)
        val soundOn = Prefs.soundEnabled(context)
        LogHelper.i("Sound", "play 触发 vibrateOn=$vibrateOn soundOn=$soundOn")

        // 震动受总开关控制，固定连续循环（600震/300停）
        if (vibrateOn) {
            vibratorContext = context.applicationContext
            vibrateLoop(context.applicationContext)
        } else {
            LogHelper.i("Sound", "震动开关关闭，仅播放声音")
        }

        // 声音受开关控制
        if (!soundOn) {
            LogHelper.i("Sound", "声音开关关闭，不播放声音")
            return
        }

        val uri = Prefs.getRemindSoundUriSync(context)
        if (uri.isNotBlank()) {
            val audioUri = Uri.parse(uri)
            val customOk = runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context.applicationContext, audioUri)
                    isLooping = false
                    prepare()
                    start()
                }
                LogHelper.i("Sound", "自定义提醒音效已播放")
            }.onFailure {
                LogHelper.e("Sound", "自定义音效播放失败，回退默认音", it)
                releasePlayer()
            }
            if (customOk.isSuccess) return
        }

        // 兜底：系统默认通知音
        runCatching {
            ringtone = RingtoneManager.getRingtone(
                context.applicationContext,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            ringtone?.play()
            LogHelper.i("Sound", "默认通知音已播放")
        }.onFailure { LogHelper.e("Sound", "默认提示音播放失败", it) }
    }

    /** 仅释放音频播放器，不取消震动（自定义音失败时避免打断震动循环） */
    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    /**
     * 连续循环震动：600ms震-300ms停，无限重复；弹窗关闭时 stop() 停止；
     * 若用户始终未处理，120s后自动停止（避免无限耗电）。
     */
    private fun vibrateLoop(context: Context) {
        val pattern = longArrayOf(0, 600, 300)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = context.getSystemService(VibratorManager::class.java)
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
            LogHelper.i("Sound", "连续震动已启动")
        }.onFailure { LogHelper.e("Sound", "震动启动失败", it) }

        vibStopTask?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        val task = Runnable {
            stopVibrate()
            LogHelper.w("Sound", "持续震动已达120s上限，自动停止")
        }
        vibStopTask = task
        Handler(Looper.getMainLooper()).postDelayed(task, 120_000L)
    }

    private fun stopVibrate() {
        val ctx = vibratorContext ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = ctx.getSystemService(VibratorManager::class.java)
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            }
        }.onFailure { LogHelper.e("Sound", "停止震动失败", it) }
    }

    fun stop() {
        // 注意：必须先用 vibratorContext 取消震动，再置空（否则 stopVibrate 直接 return，震动停不掉）
        stopVibrate()
        vibStopTask?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        vibStopTask = null
        vibratorContext = null
        // 释放音效播放器
        runCatching { player?.release() }
        player = null
        // 停止并释放默认通知音
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
