package com.mh.starrailcheckin.util

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地配置/缓存管理（SharedPreferences）
 * 所有关键状态均持久化：引导标记、提醒时间、打卡缓存（绑定日期）、登录信息、主题、样式
 */
object Prefs {
    private const val NAME = "starrail_checkin_prefs"

    fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---------- 引导 ----------
    // 引导完成标记必须持久化，否则每次打开 App 都弹引导
    private const val KEY_GUIDE_FINISHED = "is_guide_finish"
    fun isGuideFinished(c: Context): Boolean = sp(c).getBoolean(KEY_GUIDE_FINISHED, false)
    fun setGuideFinished(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GUIDE_FINISHED, v).apply()

    // ---------- 每日提醒时间 ----------
    private const val KEY_REMIND_HOUR = "remind_hour"
    private const val KEY_REMIND_MINUTE = "remind_minute"
    fun remindHour(c: Context): Int = sp(c).getInt(KEY_REMIND_HOUR, 21)
    fun remindMinute(c: Context): Int = sp(c).getInt(KEY_REMIND_MINUTE, 0)
    fun setRemindTime(c: Context, hour: Int, minute: Int) {
        sp(c).edit().putInt(KEY_REMIND_HOUR, hour).putInt(KEY_REMIND_MINUTE, minute).apply()
    }

    // ---------- 打卡提醒行为：震动开关 + 自定义文案（留空=使用系统默认文案） ----------
    private const val KEY_REMIND_VIBRATE = "remind_vibrate"
    private const val KEY_REMIND_TITLE = "remind_title_custom"
    private const val KEY_REMIND_MSG = "remind_message_custom"
    fun remindVibrate(c: Context): Boolean = sp(c).getBoolean(KEY_REMIND_VIBRATE, true)
    fun setRemindVibrate(c: Context, on: Boolean) =
        sp(c).edit().putBoolean(KEY_REMIND_VIBRATE, on).apply()
    fun remindTitle(c: Context): String? =
        sp(c).getString(KEY_REMIND_TITLE, null)?.takeIf { it.isNotBlank() }
    fun remindMessage(c: Context): String? =
        sp(c).getString(KEY_REMIND_MSG, null)?.takeIf { it.isNotBlank() }
    fun setRemindTitle(c: Context, text: String?) =
        sp(c).edit().putString(KEY_REMIND_TITLE, text?.trim().orEmpty()).apply()
    fun setRemindMessage(c: Context, text: String?) =
        sp(c).edit().putString(KEY_REMIND_MSG, text?.trim().orEmpty()).apply()

    // ---------- 多时段每日提醒（"HH:mm" 列表；为空=不提醒，不再兜底默认21:00） ----------
    private const val KEY_REMIND_TIMES = "remind_times_list"
    fun remindTimes(c: Context): List<String> {
        val sp = sp(c)
        // 一次性迁移：旧版单时段 remind_hour/minute 从未写入多时段列表时，回填一条，
        // 避免升级后列表为空导致闹钟不注册（空串=用户主动清空，不再回填）。
        if (sp.getString(KEY_REMIND_TIMES, null) == null) {
            val migrated = "%02d:%02d".format(remindHour(c), remindMinute(c))
            sp.edit().putString(KEY_REMIND_TIMES, migrated).apply()
            return listOf(migrated)
        }
        val parsed = sp.getString(KEY_REMIND_TIMES, null)?.split(",")
            ?.map { it.trim() }
            ?.filter { it.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")) }
            ?.distinct()?.sorted()
            .orEmpty()
        return parsed
    }
    fun setRemindTimes(c: Context, times: List<String>) =
        sp(c).edit().putString(KEY_REMIND_TIMES, times.joinToString(",")).apply()
    /** 提醒时段数量上限（设置页可增删的多时段列表） */
    const val MAX_REMIND_TIMES = 10
    fun upsertRemindTime(c: Context, hour: Int, minute: Int): Boolean {
        val t = "%02d:%02d".format(hour, minute)
        val cur = remindTimes(c).toMutableList()
        if (cur.contains(t)) return false
        if (cur.size >= MAX_REMIND_TIMES) return false
        cur.add(t); cur.sort(); setRemindTimes(c, cur); return true
    }
    fun removeRemindTime(c: Context, target: String) {
        setRemindTimes(c, remindTimes(c).filter { it != target })
    }

    // ---------- 打卡缓存（缓存绑定日期，跨零点自动失效） ----------
    private const val KEY_LAST_CHECK_DATE = "last_check_date"
    private const val KEY_CHECKED = "today_checked"
    /** 打卡日切点为每天 04:00（00:00~03:59 仍算前一天）。 */
    fun todayStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            .format(Date(System.currentTimeMillis() - 4 * 60 * 60 * 1000L))

    /** 自然日 00:00 口径的今天（yyyy-MM-dd），打卡历史键与日历 / 连续签到 / 月统计统一使用此口径。 */
    fun todayNaturalStr(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    fun isTodayChecked(c: Context): Boolean =
        sp(c).getBoolean(KEY_CHECKED, false) &&
            sp(c).getString(KEY_LAST_CHECK_DATE, "") == todayStr()

    fun setTodayChecked(c: Context, checked: Boolean) {
        sp(c).edit().putBoolean(KEY_CHECKED, checked)
            .putString(KEY_LAST_CHECK_DATE, todayStr()).apply()
    }

    // ---------- 今日已提醒标记（日期绑定，跨零点自动失效） ----------
    private const val KEY_REMINDED = "reminded_today"
    private const val KEY_LAST_REMINDED_DATE = "last_reminded_date"
    /** 真正展示成功的时刻（毫秒）。没有该时间戳的旧标记无法证明"真的弹出来过"，一律视为未提醒。 */
    private const val KEY_REMINDED_AT = "reminded_at_ms"

    /** 任意时间戳所属的打卡日（与 [todayStr] 同一口径：每天 04:00 换日）。 */
    fun checkinDayOf(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            .format(Date(ts - 4 * 60 * 60 * 1000L))

    fun isTodayReminded(c: Context): Boolean {
        val pref = sp(c)
        if (!pref.getBoolean(KEY_REMINDED, false)) return false
        if (pref.getString(KEY_LAST_REMINDED_DATE, "") != todayStr()) return false
        val at = pref.getLong(KEY_REMINDED_AT, 0L)
        // 旧版本/异常写入的标记没有展示时间戳 → 视为未提醒，避免把当天彻底封死不再补弹
        if (at <= 0L) return false
        return checkinDayOf(at) == todayStr()
    }

    fun setTodayReminded(c: Context, reminded: Boolean) {
        val e = sp(c).edit().putBoolean(KEY_REMINDED, reminded)
            .putString(KEY_LAST_REMINDED_DATE, todayStr())
        if (reminded) e.putLong(KEY_REMINDED_AT, System.currentTimeMillis())
        else e.remove(KEY_REMINDED_AT)
        e.apply()
    }

    /** 弹窗没真正展示出来 / 展示后立刻被回收 → 清掉标记，让后续闹钟与 Worker 还能补弹。 */
    fun clearTodayReminded(c: Context) =
        sp(c).edit().remove(KEY_REMINDED).remove(KEY_LAST_REMINDED_DATE).remove(KEY_REMINDED_AT).apply()

    // ---------- 首次进入首页标记（防止首进立刻弹上车提醒） ----------
    private const val KEY_FIRST_ENTER_HOME = "first_enter_home_done"
    fun isFirstEnterHome(c: Context): Boolean = !sp(c).getBoolean(KEY_FIRST_ENTER_HOME, false)
    fun setFirstEnterHomeDone(c: Context) = sp(c).edit().putBoolean(KEY_FIRST_ENTER_HOME, true).apply()

    // ---------- 提醒样式 ----------
    private const val KEY_REMIND_SOUND_URI = "remind_sound_uri" // null=默认提示音
    private const val KEY_REMIND_BG_URI = "remind_bg_uri"       // null=默认背景
    fun remindSoundUri(c: Context): String? = sp(c).getString(KEY_REMIND_SOUND_URI, null)
    fun setRemindSoundUri(c: Context, uri: String?) = sp(c).edit().putString(KEY_REMIND_SOUND_URI, uri).apply()
    fun remindBgUri(c: Context): String? = sp(c).getString(KEY_REMIND_BG_URI, null)
    fun setRemindBgUri(c: Context, uri: String?) = sp(c).edit().putString(KEY_REMIND_BG_URI, uri).apply()
    // 裁剪后保存到应用内部目录的卡片背景图绝对路径（优先于旧 content:// URI）
    private const val KEY_REMIND_BG_PATH = "remind_bg_path"
    fun remindBgPath(c: Context): String? = sp(c).getString(KEY_REMIND_BG_PATH, null)
    fun setRemindBgPath(c: Context, path: String?) = sp(c).edit().putString(KEY_REMIND_BG_PATH, path).apply()

    // ---------- 米游社登录信息 ----------
    private const val KEY_COOKIE = "mihoyo_cookie"
    private const val KEY_DEVICE_FP = "device_fp"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_NICKNAME = "mihoyo_nickname"
    private const val KEY_AVATAR_URL = "mihoyo_avatar_url"
    private const val KEY_UID = "hkrpg_uid"
    private const val KEY_SERVER = "hkrpg_server"
    // 用户手动绑定的抓包设备指纹（优先级高于自动生成）
    private const val KEY_BOUND_DEVICE_ID = "bound_device_id"
    private const val KEY_BOUND_DEVICE_FP = "bound_device_fp"

    fun cookie(c: Context): String = sp(c).getString(KEY_COOKIE, "") ?: ""
    fun setCookie(c: Context, v: String) = sp(c).edit().putString(KEY_COOKIE, v).apply()
    fun deviceFp(c: Context): String {
        // 优先返回用户手动绑定的抓包fp
        val bound = sp(c).getString(KEY_BOUND_DEVICE_FP, "") ?: ""
        if (bound.isNotBlank()) return bound
        return sp(c).getString(KEY_DEVICE_FP, "") ?: ""
    }
    /** 官方 getFp 自动生成并持久化的设备指纹（不含手动绑定值）。 */
    fun autoDeviceFp(c: Context): String = sp(c).getString(KEY_DEVICE_FP, "") ?: ""
    fun setDeviceFp(c: Context, v: String) = sp(c).edit().putString(KEY_DEVICE_FP, v).apply()
    fun deviceId(c: Context): String {
        // 优先返回用户手动绑定的抓包device_id
        val bound = sp(c).getString(KEY_BOUND_DEVICE_ID, "") ?: ""
        if (bound.isNotBlank()) return bound
        val old = sp(c).getString(KEY_DEVICE_ID, null)
        if (old != null) return old
        // 与官方 App 一致：由 ANDROID_ID 派生稳定 UUID，卸载重装后仍保持不变
        val androidId = runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            java.util.UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
        } else {
            java.util.UUID.randomUUID().toString()
        }
        sp(c).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
    /** 用户是否已手动绑定抓包设备 */
    fun hasBoundDevice(c: Context): Boolean =
        !(sp(c).getString(KEY_BOUND_DEVICE_ID, "") ?: "").isBlank()
    fun boundDeviceId(c: Context): String = sp(c).getString(KEY_BOUND_DEVICE_ID, "") ?: ""
    fun boundDeviceFp(c: Context): String = sp(c).getString(KEY_BOUND_DEVICE_FP, "") ?: ""
    fun setBoundDevice(c: Context, deviceId: String, deviceFp: String) {
        sp(c).edit().putString(KEY_BOUND_DEVICE_ID, deviceId).putString(KEY_BOUND_DEVICE_FP, deviceFp).apply()
    }
    fun clearBoundDevice(c: Context) {
        sp(c).edit().remove(KEY_BOUND_DEVICE_ID).remove(KEY_BOUND_DEVICE_FP).apply()
    }
    fun nickname(c: Context): String = sp(c).getString(KEY_NICKNAME, "") ?: ""
    fun setNickname(c: Context, v: String) = sp(c).edit().putString(KEY_NICKNAME, v).apply()
    fun avatarUrl(c: Context): String = sp(c).getString(KEY_AVATAR_URL, "") ?: ""
    fun setAvatarUrl(c: Context, v: String) = sp(c).edit().putString(KEY_AVATAR_URL, v).apply()
    fun uid(c: Context): String = sp(c).getString(KEY_UID, "") ?: ""
    fun setUid(c: Context, v: String) = sp(c).edit().putString(KEY_UID, v).apply()
    fun server(c: Context): String = sp(c).getString(KEY_SERVER, "") ?: ""
    fun setServer(c: Context, v: String) = sp(c).edit().putString(KEY_SERVER, v).apply()

    // ---------- 游玩天数缓存（按 UID 保存，接口失败时回退展示上次值） ----------
    fun playDays(c: Context, uid: String): Int =
        if (uid.isBlank()) 0 else sp(c).getInt("play_days_$uid", 0)
    fun setPlayDays(c: Context, uid: String, days: Int) {
        if (uid.isBlank() || days <= 0) return
        sp(c).edit().putInt("play_days_$uid", days).apply()
    }
    /** 游玩天数最近一次成功刷新时间（1 小时节流，进程重启后仍生效） */
    fun statsFetchedAt(c: Context, uid: String): Long =
        if (uid.isBlank()) 0L else sp(c).getLong("stats_fetched_at_$uid", 0L)
    fun setStatsFetchedAt(c: Context, uid: String, at: Long) {
        if (uid.isBlank()) return
        sp(c).edit().putLong("stats_fetched_at_$uid", at).apply()
    }
    /** 角色数 / 成就数随天数一并持久化，避免节流缓存时被清零。 */
    fun avatarNum(c: Context, uid: String): Int =
        if (uid.isBlank()) 0 else sp(c).getInt("avatar_num_$uid", 0)
    fun setAvatarNum(c: Context, uid: String, v: Int) {
        if (uid.isBlank()) return
        sp(c).edit().putInt("avatar_num_$uid", v).apply()
    }
    fun achievementNum(c: Context, uid: String): Int =
        if (uid.isBlank()) 0 else sp(c).getInt("achievement_num_$uid", 0)
    fun setAchievementNum(c: Context, uid: String, v: Int) {
        if (uid.isBlank()) return
        sp(c).edit().putInt("achievement_num_$uid", v).apply()
    }
    fun chestNum(c: Context, uid: String): Int =
        if (uid.isBlank()) 0 else sp(c).getInt("chest_num_$uid", 0)
    fun setChestNum(c: Context, uid: String, v: Int) {
        if (uid.isBlank()) return
        sp(c).edit().putInt("chest_num_$uid", v).apply()
    }

    // ---------- 打卡判定：游玩天数基线（每天 04:00 重置） ----------
    private const val KEY_TODAY_BASELINE_DAYS = "today_baseline_days"
    private const val KEY_TODAY_BASELINE_DAY = "today_baseline_day"
    private const val KEY_TODAY_BASELINE_UID = "today_baseline_uid"
    /** 上次观测到游戏「打开」的前台事件时间戳（毫秒），用来触发自动刷游玩天数 */
    private const val KEY_LAST_GAME_OPEN_AT = "last_game_open_at_ms"

    /**
     * 本打卡日（04:00 换日）的游玩天数基线；-1 表示今天尚未建立。
     * 只允许在「今天还没打开过游戏」时用同步到的天数建立/抬高基线，
     * 否则会把隔夜未同步的 +1 误算成今天新增。
     */
    fun todayBaseline(c: Context): Int {
        val pref = sp(c)
        if (pref.getString(KEY_TODAY_BASELINE_DAY, "") != todayStr()) return -1
        if (pref.getString(KEY_TODAY_BASELINE_UID, "") != uid(c)) return -1
        return pref.getInt(KEY_TODAY_BASELINE_DAYS, -1)
    }

    fun setTodayBaseline(c: Context, days: Int) {
        if (days <= 0) return
        sp(c).edit()
            .putString(KEY_TODAY_BASELINE_DAY, todayStr())
            .putString(KEY_TODAY_BASELINE_UID, uid(c))
            .putInt(KEY_TODAY_BASELINE_DAYS, days)
            .apply()
    }

    // ---------- 基准兜底：上一打卡日收官天数 / 基准刷新时刻 ----------
    private const val KEY_LAST_CLOSED_DAYS = "last_closed_days"
    private const val KEY_LAST_CLOSED_DAY = "last_closed_day"
    private const val KEY_BASELINE_FETCHED_AT = "baseline_fetched_at"
    /** 凌晨基准固化重试阶段：0 未开始，1 试过 3:50，2 试过 3:55，3 试过 3:59 */
    private const val KEY_BASELINE_STAGE = "baseline_retry_stage"

    /** 上一打卡日收官天数（跨日继承兜底用）；无记录返回 0。 */
    fun lastClosedDays(c: Context): Int = sp(c).getInt(KEY_LAST_CLOSED_DAYS, 0)
    fun lastClosedDay(c: Context): String = sp(c).getString(KEY_LAST_CLOSED_DAY, "") ?: ""

    /** 记录收官天数与所属打卡日。 */
    fun setLastClosedDay(c: Context, days: Int, day: String) {
        if (days <= 0 || day.isBlank()) return
        sp(c).edit()
            .putInt(KEY_LAST_CLOSED_DAYS, days)
            .putString(KEY_LAST_CLOSED_DAY, day)
            .apply()
    }

    /** 今日基准最近一次成功刷新时刻（毫秒）；0 表示尚未成功固化。 */
    fun baselineFetchedAt(c: Context): Long = sp(c).getLong(KEY_BASELINE_FETCHED_AT, 0L)
    fun setBaselineFetchedAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_BASELINE_FETCHED_AT, at).apply()

    fun baselineStage(c: Context): Int = sp(c).getInt(KEY_BASELINE_STAGE, 0)
    fun setBaselineStage(c: Context, stage: Int) =
        sp(c).edit().putInt(KEY_BASELINE_STAGE, stage).apply()

    /** 已排过凌晨基准固化闹钟的打卡日（避免同一天重复注册）。 */
    private const val KEY_BASELINE_ALARM_DAY = "baseline_alarm_day"
    fun baselineAlarmDay(c: Context): String = sp(c).getString(KEY_BASELINE_ALARM_DAY, "") ?: ""
    fun setBaselineAlarmDay(c: Context, day: String) =
        sp(c).edit().putString(KEY_BASELINE_ALARM_DAY, day).apply()

    /** 上次观测到游戏打开（moveToForeground）的时间戳。 */
    fun lastGameOpenAt(c: Context): Long = sp(c).getLong(KEY_LAST_GAME_OPEN_AT, 0L)
    fun setLastGameOpenAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_LAST_GAME_OPEN_AT, at).apply()

    // ---------- 打卡双方案 ----------
    private const val KEY_SCHEME2_CHECKED_DAY = "scheme2_checked_day"
    /** 方案2 打卡标记的数据版本：旧版本/旧逻辑写入的标记没有该版本号，一律视为无效 */
    private const val KEY_SCHEME2_CHECKED_VER = "scheme2_checked_ver"
    private const val SCHEME2_VER = 2
    private const val KEY_OPEN_BASELINE_DAYS = "game_open_baseline_days"
    private const val KEY_OPEN_CHECK_DUE_AT = "game_open_check_due_at"

    /**
     * 打卡方案（自动切换）：
     * - 方案1：未登录或尚未成功获取游玩天数 → 打开游戏即视为打卡成功
     * - 方案2：已登录且已成功获取游玩天数 → 打开游戏 20 分钟后刷新天数，天数 +1 才算打卡成功
     */
    fun checkinScheme(c: Context): Int =
        if (isLoggedIn(c) && playDays(c, uid(c)) > 0) 2 else 1

    /**
     * 方案2 今日已验证打卡（只有「打开游戏后 20 分钟校验到天数 +1」才会写入）。
     * 有效性只认数据版本号 + 打卡日；不再绑定 todayBaseline——基线跨 04:00 会失效，
     * 若把它作为标记生效条件，会导致已打卡标记被误判失效、进而触发重复校验误判。
     */
    fun isScheme2Checked(c: Context): Boolean {
        val pref = sp(c)
        if (pref.getInt(KEY_SCHEME2_CHECKED_VER, 0) != SCHEME2_VER) return false
        return pref.getString(KEY_SCHEME2_CHECKED_DAY, "") == todayStr()
    }

    fun setScheme2Checked(c: Context) =
        sp(c).edit()
            .putString(KEY_SCHEME2_CHECKED_DAY, todayStr())
            .putInt(KEY_SCHEME2_CHECKED_VER, SCHEME2_VER)
            .apply()

    /** 检测到游戏打开时记录的历史天数，作为 20 分钟后“是否 +1”的对比基准。 */
    fun gameOpenBaseline(c: Context): Int = sp(c).getInt(KEY_OPEN_BASELINE_DAYS, 0)

    fun setGameOpenBaseline(c: Context, days: Int) =
        sp(c).edit().putInt(KEY_OPEN_BASELINE_DAYS, days).apply()

    /** 方案2 待校验时刻（打开游戏时间 + 20 分钟），Worker 兜底用。 */
    fun gameOpenCheckDueAt(c: Context): Long = sp(c).getLong(KEY_OPEN_CHECK_DUE_AT, 0L)

    fun setGameOpenCheckDueAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_OPEN_CHECK_DUE_AT, at).apply()

    fun removeCheckHistory(c: Context, date: String) =
        sp(c).edit().remove("check_history_$date").apply()

    /**
     * 方案2 下清理今日「天数 +1 已验证」的旧标记。
     * 注意：只清方案2 验证标记，不再删除 check_history 打卡历史——
     * 历史以当日实际打开游戏（UsageStats）为准，避免把已打卡记录误清成 0。
     */
    fun sanitizeScheme2Today(c: Context) {
        if (isScheme2Checked(c)) return
        if (isTodayChecked(c)) setTodayChecked(c, false)
    }

    /** 当前天数是否已高于本打卡日基线（纯读，不写盘）。 */
    fun isPlayDaysAboveBaseline(c: Context, uid: String, currentDays: Int): Boolean {
        if (uid.isBlank() || currentDays <= 0) return false
        val baseline = todayBaseline(c)
        return baseline > 0 && currentDays > baseline
    }

    // ---------- 我的页数据每小时刷新（时间戳持久化，重进不刷新） ----------
    private const val KEY_PROFILE_REFRESH_AT = "profile_data_refresh_at"
    fun profileRefreshAt(c: Context): Long = sp(c).getLong(KEY_PROFILE_REFRESH_AT, 0L)
    fun setProfileRefreshAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_PROFILE_REFRESH_AT, at).apply()

    // ---------- 开拓力卡片缓存（重进先展示上次数据） ----------
    private const val KEY_STAMINA_CACHE = "stamina_cache_json"
    private const val KEY_STAMINA_CACHE_UID = "stamina_cache_uid"
    fun staminaCache(c: Context): String = sp(c).getString(KEY_STAMINA_CACHE, "") ?: ""
    fun staminaCacheUid(c: Context): String = sp(c).getString(KEY_STAMINA_CACHE_UID, "") ?: ""
    fun setStaminaCache(c: Context, json: String, uid: String) =
        sp(c).edit().putString(KEY_STAMINA_CACHE, json).putString(KEY_STAMINA_CACHE_UID, uid).apply()

    // ---------- 战绩磁盘缓存（1 小时有效，重进/切页不重新请求） ----------
    private const val KEY_CHALLENGE_CACHE = "challenge_cache_json"
    fun challengeCacheRaw(c: Context): String = sp(c).getString(KEY_CHALLENGE_CACHE, "") ?: ""
    fun setChallengeCacheRaw(c: Context, json: String) =
        sp(c).edit().putString(KEY_CHALLENGE_CACHE, json).apply()

    // ---------- 米游社头像图片缓存（Cookie 导入后不再重复下载） ----------
    private const val KEY_REMOTE_AVATAR_URL = "remote_avatar_cached_url"
    fun remoteAvatarCachedUrl(c: Context): String =
        sp(c).getString(KEY_REMOTE_AVATAR_URL, "") ?: ""
    fun setRemoteAvatarCachedUrl(c: Context, url: String) =
        sp(c).edit().putString(KEY_REMOTE_AVATAR_URL, url).apply()

    // ---------- 米游社签到状态缓存（1 小时有效，重进/切页不重新请求） ----------
    private const val KEY_SIGN_CACHE = "sign_info_cache_json"
    fun signCacheRaw(c: Context): String = sp(c).getString(KEY_SIGN_CACHE, "") ?: ""
    fun setSignCacheRaw(c: Context, json: String) =
        sp(c).edit().putString(KEY_SIGN_CACHE, json).apply()
    /** 已登录：必须同时存在 Cookie、星铁 UID 和服务器，避免“只有 Cookie 但功能全部失败”的假登录状态。 */
    fun isLoggedIn(c: Context): Boolean =
        cookie(c).isNotBlank() && uid(c).isNotBlank() && server(c).isNotBlank()

    /** 退出登录：清空本地Cookie、UID缓存（保留设备指纹与已保存的多账号列表） */
    fun clearLogin(c: Context) {
        sp(c).edit().remove(KEY_COOKIE).remove(KEY_NICKNAME)
            .remove(KEY_AVATAR_URL).remove(KEY_UID).remove(KEY_SERVER)
            .remove(KEY_ACTIVE_ACCOUNT)
            // 账号相关缓存一并清空，避免下个账号沿用旧数据/旧刷新时间
            .remove(KEY_PROFILE_REFRESH_AT)
            .remove(KEY_STAMINA_CACHE).remove(KEY_STAMINA_CACHE_UID)
            .remove(KEY_CHALLENGE_CACHE).remove(KEY_SIGN_CACHE)
            .remove(KEY_REMOTE_AVATAR_URL)
            .apply()
        clearCookieHealthState(c)
        LogHelper.i("Prefs", "已清空登录缓存")
    }

    // ---------- 多账号管理 ----------
    data class Account(
        val id: String,
        val cookie: String,
        val uid: String,
        val server: String,
        val nickname: String,
        val avatarUrl: String
    )

    /** 一次登录会话快照，用于「全部签到」等临时切换账号后恢复现场。 */
    data class Session(
        val cookie: String,
        val uid: String,
        val server: String,
        val nickname: String,
        val avatarUrl: String,
        val activeId: String
    )

    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_ACTIVE_ACCOUNT = "active_account_id"

    private fun accountId(uid: String, server: String, cookie: String): String =
        if (uid.isNotBlank()) "$uid@${server.ifBlank { "unknown" }}"
        else "ck_${cookie.hashCode().toUInt().toString(16)}"

    private fun currentAccount(c: Context): Account {
        val ck = cookie(c)
        val u = uid(c)
        val s = server(c)
        return Account(accountId(u, s, ck), ck, u, s, nickname(c), avatarUrl(c))
    }

    private fun readAccounts(c: Context): MutableList<Account> {
        val raw = sp(c).getString(KEY_ACCOUNTS, null) ?: return mutableListOf()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val list = mutableListOf<Account>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ck = o.optString("cookie", "")
            if (ck.isBlank()) continue
            val u = o.optString("uid", "")
            val s = o.optString("server", "")
            list.add(
                Account(
                    id = o.optString("id").ifBlank { accountId(u, s, ck) },
                    cookie = ck,
                    uid = u,
                    server = s,
                    nickname = o.optString("nickname", ""),
                    avatarUrl = o.optString("avatarUrl", "")
                )
            )
        }
        return list
    }

    private fun writeAccounts(c: Context, list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("cookie", a.cookie)
                put("uid", a.uid)
                put("server", a.server)
                put("nickname", a.nickname)
                put("avatarUrl", a.avatarUrl)
            })
        }
        sp(c).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun setActiveAccountId(c: Context, id: String) =
        sp(c).edit().putString(KEY_ACTIVE_ACCOUNT, id).apply()

    /** 已保存的所有账号；首次升级时自动把当前登录账号纳入列表。 */
    fun accounts(c: Context): List<Account> {
        val stored = readAccounts(c)
        if (stored.isEmpty() && cookie(c).isNotBlank()) {
            val cur = currentAccount(c)
            writeAccounts(c, listOf(cur))
            setActiveAccountId(c, cur.id)
            return listOf(cur)
        }
        return stored
    }

    fun activeAccountId(c: Context): String {
        val saved = sp(c).getString(KEY_ACTIVE_ACCOUNT, "") ?: ""
        if (saved.isNotBlank()) return saved
        return if (cookie(c).isNotBlank()) currentAccount(c).id else ""
    }

    // ---------- Cookie 健康监控（状态仅属于 accountId 对应的账号） ----------
    data class CookieHealthState(
        val accountId: String = "",
        val status: String = "unknown",
        val message: String = "尚未检查",
        val lastCheckAt: Long = 0L,
        val lastRenewAt: Long = 0L,
        val invalidNotified: Boolean = false
    )

    private const val KEY_COOKIE_HEALTH_CHECK = "cookie_health_check_enabled"
    private const val KEY_COOKIE_AUTO_RENEW = "cookie_auto_renew_enabled"
    private const val KEY_COOKIE_HEALTH_ACCOUNT = "cookie_health_account_id"
    private const val KEY_COOKIE_HEALTH_STATUS = "cookie_health_status"
    private const val KEY_COOKIE_HEALTH_MESSAGE = "cookie_health_message"
    private const val KEY_COOKIE_HEALTH_LAST_CHECK = "cookie_health_last_check_at"
    private const val KEY_COOKIE_HEALTH_LAST_RENEW = "cookie_health_last_renew_at"
    private const val KEY_COOKIE_HEALTH_NOTIFIED = "cookie_health_invalid_notified"

    fun cookieHealthCheckEnabled(c: Context): Boolean =
        sp(c).getBoolean(KEY_COOKIE_HEALTH_CHECK, false)
    fun setCookieHealthCheckEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_COOKIE_HEALTH_CHECK, v).apply()
    fun cookieAutoRenewEnabled(c: Context): Boolean =
        sp(c).getBoolean(KEY_COOKIE_AUTO_RENEW, false)
    fun setCookieAutoRenewEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_COOKIE_AUTO_RENEW, v).apply()

    fun cookieHealthState(c: Context): CookieHealthState {
        val prefs = sp(c)
        return CookieHealthState(
            accountId = prefs.getString(KEY_COOKIE_HEALTH_ACCOUNT, "").orEmpty(),
            status = prefs.getString(KEY_COOKIE_HEALTH_STATUS, "unknown").orEmpty(),
            message = prefs.getString(KEY_COOKIE_HEALTH_MESSAGE, "尚未检查").orEmpty(),
            lastCheckAt = prefs.getLong(KEY_COOKIE_HEALTH_LAST_CHECK, 0L),
            lastRenewAt = prefs.getLong(KEY_COOKIE_HEALTH_LAST_RENEW, 0L),
            invalidNotified = prefs.getBoolean(KEY_COOKIE_HEALTH_NOTIFIED, false)
        )
    }

    fun saveCookieHealthState(c: Context, state: CookieHealthState) {
        sp(c).edit()
            .putString(KEY_COOKIE_HEALTH_ACCOUNT, state.accountId)
            .putString(KEY_COOKIE_HEALTH_STATUS, state.status)
            .putString(KEY_COOKIE_HEALTH_MESSAGE, state.message)
            .putLong(KEY_COOKIE_HEALTH_LAST_CHECK, state.lastCheckAt)
            .putLong(KEY_COOKIE_HEALTH_LAST_RENEW, state.lastRenewAt)
            .putBoolean(KEY_COOKIE_HEALTH_NOTIFIED, state.invalidNotified)
            .apply()
        // 同步写入按账号隔离的健康 map，供多账号列表分别展示
        if (state.accountId.isNotBlank()) {
            val map = readHealthMap(c).toMutableMap()
            map[state.accountId] = state
            writeHealthMap(c, map)
        }
    }

    private const val KEY_COOKIE_HEALTH_MAP = "cookie_health_map"
    private const val KEY_LAST_SIGN_MAP = "last_sign_map"

    private fun readHealthMap(c: Context): Map<String, CookieHealthState> {
        val raw = sp(c).getString(KEY_COOKIE_HEALTH_MAP, null) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val res = mutableMapOf<String, CookieHealthState>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optJSONObject(k) ?: continue
            res[k] = CookieHealthState(
                accountId = k,
                status = v.optString("status", "unknown"),
                message = v.optString("message", "尚未检查"),
                lastCheckAt = v.optLong("lastCheckAt", 0L),
                lastRenewAt = v.optLong("lastRenewAt", 0L),
                invalidNotified = v.optBoolean("invalidNotified", false)
            )
        }
        return res
    }

    private fun writeHealthMap(c: Context, map: Map<String, CookieHealthState>) {
        val o = JSONObject()
        map.forEach { (k, s) ->
            o.put(k, JSONObject()
                .put("status", s.status)
                .put("message", s.message)
                .put("lastCheckAt", s.lastCheckAt)
                .put("lastRenewAt", s.lastRenewAt)
                .put("invalidNotified", s.invalidNotified))
        }
        sp(c).edit().putString(KEY_COOKIE_HEALTH_MAP, o.toString()).apply()
    }

    /** 取指定账号的健康状态；map 未命中时回落旧单份字段（仅当前账号匹配）。 */
    fun cookieHealthStateFor(c: Context, accountId: String): CookieHealthState? {
        if (accountId.isBlank()) return null
        readHealthMap(c)[accountId]?.let { return it }
        val single = cookieHealthState(c)
        return if (single.accountId == accountId) single else null
    }

    /** 最近一次成功签到时刻（按账号，毫秒时间戳；打卡历史本身仍为全局不隔离）。 */
    fun lastSignAt(c: Context, accountId: String): Long {
        if (accountId.isBlank()) return 0L
        val raw = sp(c).getString(KEY_LAST_SIGN_MAP, null) ?: return 0L
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return 0L
        return o.optLong(accountId, 0L)
    }

    fun markLastSign(c: Context, accountId: String, at: Long) {
        if (accountId.isBlank()) return
        val raw = sp(c).getString(KEY_LAST_SIGN_MAP, null)
        val o = runCatching { raw?.let { JSONObject(it) } }.getOrNull() ?: JSONObject()
        o.put(accountId, at)
        sp(c).edit().putString(KEY_LAST_SIGN_MAP, o.toString()).apply()
    }

    fun clearCookieHealthState(c: Context) {
        sp(c).edit()
            .remove(KEY_COOKIE_HEALTH_ACCOUNT)
            .remove(KEY_COOKIE_HEALTH_STATUS)
            .remove(KEY_COOKIE_HEALTH_MESSAGE)
            .remove(KEY_COOKIE_HEALTH_LAST_CHECK)
            .remove(KEY_COOKIE_HEALTH_LAST_RENEW)
            .remove(KEY_COOKIE_HEALTH_NOTIFIED)
            .apply()
    }

    /** 登录成功/角色刷新后，把当前登录态写回账号列表。 */
    fun saveCurrentAccount(c: Context): Account? {
        val ck = cookie(c).trim()
        if (ck.isBlank()) return null
        val acc = currentAccount(c)
        val list = readAccounts(c).toMutableList()
        val idx = list.indexOfFirst {
            it.id == acc.id || (acc.uid.isNotBlank() && it.uid == acc.uid && it.server == acc.server)
        }
        if (idx >= 0) list[idx] = acc else list.add(0, acc)
        while (list.size > 20) list.removeAt(list.lastIndex)
        writeAccounts(c, list)
        setActiveAccountId(c, acc.id)
        return acc
    }

    /** 切换账号：先保存当前账号，再把目标账号写入当前登录字段。 */
    fun switchAccount(c: Context, id: String): Boolean {
        val target = accounts(c).firstOrNull { it.id == id } ?: return false
        if (cookie(c).isNotBlank()) saveCurrentAccount(c)
        sp(c).edit()
            .putString(KEY_COOKIE, target.cookie)
            .putString(KEY_UID, target.uid)
            .putString(KEY_SERVER, target.server)
            .putString(KEY_NICKNAME, target.nickname)
            .putString(KEY_AVATAR_URL, target.avatarUrl)
            .apply()
        setActiveAccountId(c, target.id)
        LogHelper.i("Prefs", "已切换账号 uid=${target.uid} server=${target.server}")
        return true
    }

    /** 删除账号；若删除的是当前账号，则自动切到列表中的下一个。 */
    fun removeAccount(c: Context, id: String) {
        val list = readAccounts(c).toMutableList()
        val removed = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        writeAccounts(c, list)
        if (removed == null) return
        if (activeAccountId(c) != id && currentAccount(c).id != id) return
        val next = list.firstOrNull()
        if (next != null) {
            // 直接写入目标账号，避免 saveCurrentAccount 把刚删除的账号又写回列表
            sp(c).edit()
                .putString(KEY_COOKIE, next.cookie)
                .putString(KEY_UID, next.uid)
                .putString(KEY_SERVER, next.server)
                .putString(KEY_NICKNAME, next.nickname)
                .putString(KEY_AVATAR_URL, next.avatarUrl)
                .putString(KEY_ACTIVE_ACCOUNT, next.id)
                .apply()
        } else {
            clearLogin(c)
        }
    }

    fun session(c: Context): Session = Session(
        cookie = cookie(c),
        uid = uid(c),
        server = server(c),
        nickname = nickname(c),
        avatarUrl = avatarUrl(c),
        activeId = sp(c).getString(KEY_ACTIVE_ACCOUNT, "") ?: ""
    )

    fun restoreSession(c: Context, s: Session) {
        val ed = sp(c).edit()
            .putString(KEY_COOKIE, s.cookie)
            .putString(KEY_UID, s.uid)
            .putString(KEY_SERVER, s.server)
            .putString(KEY_NICKNAME, s.nickname)
            .putString(KEY_AVATAR_URL, s.avatarUrl)
        if (s.activeId.isBlank()) ed.remove(KEY_ACTIVE_ACCOUNT) else ed.putString(KEY_ACTIVE_ACCOUNT, s.activeId)
        ed.apply()
    }

    // ---------- 每日自动签到 ----------
    private const val KEY_AUTO_SIGN = "auto_sign_enabled"
    private const val KEY_AUTO_SIGN_HOUR = "auto_sign_hour"
    private const val KEY_AUTO_SIGN_ATTEMPT = "auto_sign_last_attempt"
    private const val KEY_LAST_SIGN_SUCCESS = "last_sign_success_date"

    fun autoSignEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_SIGN, false)
    fun setAutoSignEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_AUTO_SIGN, v).apply()
    fun autoSignHour(c: Context): Int = sp(c).getInt(KEY_AUTO_SIGN_HOUR, 8).coerceIn(0, 23)
    fun setAutoSignHour(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_AUTO_SIGN_HOUR, v.coerceIn(0, 23)).apply()

    /** 自动签到当天是否已尝试过（失败也只通知一次，避免每 15 分钟刷屏）。 */
    fun autoSignLastAttempt(c: Context): String =
        sp(c).getString(KEY_AUTO_SIGN_ATTEMPT, "") ?: ""
    fun setAutoSignLastAttempt(c: Context, date: String) =
        sp(c).edit().putString(KEY_AUTO_SIGN_ATTEMPT, date).apply()

    /** 最近一次签到成功日期（含“今天已经签到过了”）。 */
    fun lastSignSuccess(c: Context): String =
        sp(c).getString(KEY_LAST_SIGN_SUCCESS, "") ?: ""
    fun markSignSuccess(c: Context) =
        sp(c).edit().putString(KEY_LAST_SIGN_SUCCESS, todayStr()).apply()

    // ---------- 自动签到当日退避重试（网络/瞬时失败可当天重试；认证失效/风控不重试） ----------
    private const val KEY_AUTO_SIGN_RETRY_COUNT = "auto_sign_retry_count"
    private const val KEY_AUTO_SIGN_NEXT_RETRY_AT = "auto_sign_next_retry_at"
    private const val KEY_AUTO_SIGN_FINAL_DAY = "auto_sign_final_failed_day"

    /** 今日已退避重试次数。 */
    fun autoSignRetryCount(c: Context): Int = sp(c).getInt(KEY_AUTO_SIGN_RETRY_COUNT, 0)
    fun setAutoSignRetryCount(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_AUTO_SIGN_RETRY_COUNT, v.coerceAtLeast(0)).apply()

    /** 下一次允许重试的时刻（毫秒）；0=不受限。 */
    fun autoSignNextRetryAt(c: Context): Long = sp(c).getLong(KEY_AUTO_SIGN_NEXT_RETRY_AT, 0L)
    fun setAutoSignNextRetryAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_AUTO_SIGN_NEXT_RETRY_AT, at).apply()

    /** 今日已判定为最终失败（认证失效/风控/重试上限），当天不再重试。 */
    fun autoSignFinalFailedDay(c: Context): String =
        sp(c).getString(KEY_AUTO_SIGN_FINAL_DAY, "") ?: ""
    fun setAutoSignFinalFailedDay(c: Context, day: String) =
        sp(c).edit().putString(KEY_AUTO_SIGN_FINAL_DAY, day).apply()

    /** 签到成功或跨天后清空退避重试状态。 */
    fun resetAutoSignRetry(c: Context) {
        sp(c).edit()
            .remove(KEY_AUTO_SIGN_RETRY_COUNT)
            .remove(KEY_AUTO_SIGN_NEXT_RETRY_AT)
            .remove(KEY_AUTO_SIGN_FINAL_DAY)
            .apply()
    }

    // ---------- 主题（0跟随系统 1浅色 2深色） ----------
    private const val KEY_THEME = "theme_mode"
    private const val KEY_THEME_AMOLED = "theme_amoled_dark"
    fun themeMode(c: Context): Int = sp(c).getInt(KEY_THEME, 0)
    fun setThemeMode(c: Context, v: Int) {
        sp(c).edit().putInt(KEY_THEME, v).apply()
        themeModeFlow.value = v
    }
    /** AMOLED 纯黑深色（仅在深色主题下生效）。 */
    fun amoledDark(c: Context): Boolean = sp(c).getBoolean(KEY_THEME_AMOLED, false)
    fun setAmoledDark(c: Context, v: Boolean) {
        sp(c).edit().putBoolean(KEY_THEME_AMOLED, v).apply()
        amoledFlow.value = v
    }

    // 进程内可观察状态：写盘后同步更新，根部主题即时重组（替代一次性 flowOf 快照）。
    private val themeModeFlow = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    private val amoledFlow = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    fun themeModeObservable(c: Context): kotlinx.coroutines.flow.StateFlow<Int?> {
        if (themeModeFlow.value == null) themeModeFlow.value = themeMode(c)
        return themeModeFlow
    }
    fun amoledObservable(c: Context): kotlinx.coroutines.flow.StateFlow<Boolean?> {
        if (amoledFlow.value == null) amoledFlow.value = amoledDark(c)
        return amoledFlow
    }

    // ---------- 作者 B 站资料缓存（24h 有效，失败回退本地内置） ----------
    private const val KEY_AUTHOR_NAME = "author_bili_name"
    private const val KEY_AUTHOR_FACE = "author_bili_face"
    private const val KEY_AUTHOR_FETCH_AT = "author_bili_fetch_at"
    private const val AUTHOR_CACHE_TTL = 24 * 60 * 60_000L
    fun authorName(c: Context): String = sp(c).getString(KEY_AUTHOR_NAME, "") ?: ""
    fun authorFace(c: Context): String = sp(c).getString(KEY_AUTHOR_FACE, "") ?: ""
    fun authorFetchAt(c: Context): Long = sp(c).getLong(KEY_AUTHOR_FETCH_AT, 0L)
    fun authorCacheValid(c: Context): Boolean =
        System.currentTimeMillis() - authorFetchAt(c) < AUTHOR_CACHE_TTL
    fun saveAuthorProfile(c: Context, name: String, face: String) {
        sp(c).edit()
            .putString(KEY_AUTHOR_NAME, name)
            .putString(KEY_AUTHOR_FACE, face)
            .putLong(KEY_AUTHOR_FETCH_AT, System.currentTimeMillis())
            .apply()
    }

    // ---------- 保活/通知开关 ----------
    private const val KEY_KEEP_NOTIFY = "keep_notify_enabled"
    fun keepNotifyEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_KEEP_NOTIFY, true)
    fun setKeepNotify(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_KEEP_NOTIFY, v).apply()

    // ---------- 进入 App 自动检查更新 ----------
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    fun autoCheckUpdate(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_CHECK_UPDATE, true)
    fun setAutoCheckUpdate(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_CHECK_UPDATE, v).apply()

    // ---------- 后台存活心跳（用于留存状态自检：最近一次 Worker / 前台服务存活时刻） ----------
    private const val KEY_LAST_WORKER_HEARTBEAT = "last_worker_heartbeat_at"
    private const val KEY_LAST_FGS_HEARTBEAT = "last_fgs_heartbeat_at"
    fun lastWorkerHeartbeatAt(c: Context): Long = sp(c).getLong(KEY_LAST_WORKER_HEARTBEAT, 0L)
    fun setLastWorkerHeartbeatAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_LAST_WORKER_HEARTBEAT, at).apply()
    fun lastFgsHeartbeatAt(c: Context): Long = sp(c).getLong(KEY_LAST_FGS_HEARTBEAT, 0L)
    fun setLastFgsHeartbeatAt(c: Context, at: Long) =
        sp(c).edit().putLong(KEY_LAST_FGS_HEARTBEAT, at).apply()

    // ---------- 分任务心跳 / 最近一次成功联网时刻（后台状态自检分项展示） ----------
    const val TASK_REMIND = "remind"
    const val TASK_STAMINA = "stamina"
    const val TASK_SIGN = "sign"
    const val TASK_COOKIE = "cookie"

    private fun workerHeartbeatKey(task: String) = "worker_heartbeat_at_$task"
    private fun workerSuccessKey(task: String) = "worker_success_at_$task"

    fun workerHeartbeatAt(c: Context, task: String): Long =
        sp(c).getLong(workerHeartbeatKey(task), 0L)

    fun setWorkerHeartbeatAt(c: Context, task: String, at: Long) =
        sp(c).edit().putLong(workerHeartbeatKey(task), at).apply()

    fun workerSuccessAt(c: Context, task: String): Long =
        sp(c).getLong(workerSuccessKey(task), 0L)

    fun setWorkerSuccessAt(c: Context, task: String, at: Long) =
        sp(c).edit().putLong(workerSuccessKey(task), at).apply()

    // ---------- 提醒行为扩展：声音开关 / 贪睡间隔 / 目标时长 ----------
    private const val KEY_SOUND_ENABLED = "remind_sound_enabled"
    private const val KEY_SNOOZE_MIN = "snooze_interval_min"
    private const val KEY_PLAY_TARGET_MIN = "play_target_minutes"

    fun soundEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_SOUND_ENABLED, true)
    fun setSoundEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_SOUND_ENABLED, v).apply()
    fun snoozeIntervalMin(c: Context): Int = sp(c).getInt(KEY_SNOOZE_MIN, 10)
    fun setSnoozeIntervalMin(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_SNOOZE_MIN, v).apply()
    fun playTargetMinutes(c: Context): Int = sp(c).getInt(KEY_PLAY_TARGET_MIN, 60)
    fun setPlayTargetMinutes(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_PLAY_TARGET_MIN, v).apply()

    // ---------- 自定义头像（相册导入，本地文件路径，优先于米游社头像展示） ----------
    private const val KEY_CUSTOM_AVATAR = "custom_avatar_path"
    fun customAvatarPath(c: Context): String? = sp(c).getString(KEY_CUSTOM_AVATAR, null)
    fun setCustomAvatarPath(c: Context, path: String?) =
        sp(c).edit().putString(KEY_CUSTOM_AVATAR, path).apply()

    // ---------- 打卡历史（日期 -> 是否打卡） ----------
    fun saveCheckHistory(c: Context, date: String, checked: Boolean) {
        sp(c).edit().putInt("check_history_$date", if (checked) 1 else 0).apply()
    }
    fun getCheckHistory(c: Context, date: String): Boolean? {
        if (!sp(c).contains("check_history_$date")) return null
        return sp(c).getInt("check_history_$date", 0) == 1
    }
    /** 某日期是否打卡成功（无记录返回 false） */
    fun isDayChecked(c: Context, date: String): Boolean = getCheckHistory(c, date) == true

    // ---------- 提醒总开关（false=暂停所有闹钟，不删除已配置时段） ----------
    private const val KEY_REMINDER_ENABLED = "reminder_master_enabled"
    fun reminderEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_REMINDER_ENABLED, true)
    fun setReminderEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_REMINDER_ENABLED, v).apply()

    // ---------- 每周游玩时长目标（小时，本地） ----------
    private const val KEY_WEEKLY_GOAL_HOURS = "weekly_goal_hours"
    fun weeklyGoalHours(c: Context): Float = sp(c).getFloat(KEY_WEEKLY_GOAL_HOURS, 10f)
    fun setWeeklyGoalHours(c: Context, h: Float) =
        sp(c).edit().putFloat(KEY_WEEKLY_GOAL_HOURS, h).apply()

    // ---------- 打卡备注（日期 -> 备注文本） ----------
    fun checkinNote(c: Context, date: String): String =
        sp(c).getString("checkin_note_$date", "") ?: ""
    fun setCheckinNote(c: Context, date: String, note: String) =
        sp(c).edit().putString("checkin_note_$date", note.trim()).apply()

    // ---------- 云·星穹铁道开关 ----------
    private const val KEY_CLOUD_GAME = "cloud_game_mode"
    fun isCloudGame(c: Context): Boolean = sp(c).getBoolean(KEY_CLOUD_GAME, false)
    fun setCloudGame(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_CLOUD_GAME, v).apply()

    /** 当前游戏包名列表（根据云游戏开关返回不同包名） */
    fun gamePackages(c: Context): List<String> {
        return if (isCloudGame(c)) {
            listOf("com.miHoYo.cloudgames.hkrpg")
        } else {
            listOf("com.mihoyo.hkrpg", "com.miHoYo.hkrpg", "com.miHoYo.hkrpg.bilibili")
        }
    }

    /** 当前游戏显示名 */
    fun gameDisplayName(c: Context): String {
        return if (isCloudGame(c)) "云·崩坏：星穹铁道" else "崩坏：星穹铁道"
    }

    /** 扫描本地打卡历史，返回已打卡日期列表（yyyy-MM-dd，升序）。 */
    fun checkinDateList(c: Context): List<String> {
        val all = sp(c).all
        return all.keys
            .filter { it.startsWith("check_history_") }
            // 必须按全键取值判断是否为 1，再去掉前缀；不能用裸日期去 all 里查（会取到 null）
            .filter { all[it] as? Int == 1 }
            .map { it.removePrefix("check_history_") }
            .sorted()
    }

    // ---------- 体力与派遣提醒（节流/已提醒标记按 UID 隔离，避免多账号串状态） ----------
    private const val KEY_STAMINA_NOTIFY = "stamina_notify_enabled"
    private const val KEY_STAMINA_THRESHOLD = "stamina_notify_threshold"
    private const val KEY_STAMINA_FULL = "stamina_notify_full"
    private const val KEY_EXPEDITION_NOTIFY = "expedition_notify_enabled"
    private const val KEY_STAMINA_LAST_CURRENT = "stamina_last_current"
    private const val KEY_STAMINA_LAST_CHECK = "stamina_last_check_at"
    private const val KEY_EXPEDITION_NOTIFIED = "expedition_notified_date"
    // 回满前预告
    private const val KEY_STAMINA_PRE_FULL = "stamina_pre_full_notify"
    private const val KEY_STAMINA_PRE_FULL_MIN = "stamina_pre_full_min"
    private const val KEY_STAMINA_PRE_FULL_DONE = "stamina_pre_full_done_date"
    // 派遣即将完成预告
    private const val KEY_EXPEDITION_PRE_DONE = "expedition_pre_done_notify"
    private const val KEY_EXPEDITION_PRE_DONE_MIN = "expedition_pre_done_min"
    private const val KEY_EXPEDITION_PRE_DONE_SET = "expedition_pre_done_done_set"

    /** 按 UID 隔离的键：UID 为空时回退到全局键，兼容旧缓存。 */
    private fun staminaKey(c: Context, base: String): String {
        val u = uid(c)
        return if (u.isBlank()) base else "${base}_$u"
    }

    fun staminaNotifyEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_STAMINA_NOTIFY, false)
    fun setStaminaNotifyEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_STAMINA_NOTIFY, v).apply()
    fun staminaThreshold(c: Context): Int =
        sp(c).getInt(KEY_STAMINA_THRESHOLD, 200).coerceIn(20, 240)
    fun setStaminaThreshold(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_STAMINA_THRESHOLD, v.coerceIn(20, 240)).apply()
    fun staminaFullNotify(c: Context): Boolean = sp(c).getBoolean(KEY_STAMINA_FULL, true)
    fun setStaminaFullNotify(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_STAMINA_FULL, v).apply()
    fun expeditionNotifyEnabled(c: Context): Boolean = sp(c).getBoolean(KEY_EXPEDITION_NOTIFY, true)
    fun setExpeditionNotifyEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_EXPEDITION_NOTIFY, v).apply()

    // 回满前预告开关 / 提前分钟数 / 今日已预告标记
    fun staminaPreFullNotify(c: Context): Boolean = sp(c).getBoolean(KEY_STAMINA_PRE_FULL, true)
    fun setStaminaPreFullNotify(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_STAMINA_PRE_FULL, v).apply()
    fun staminaPreFullMin(c: Context): Int =
        sp(c).getInt(KEY_STAMINA_PRE_FULL_MIN, 10).coerceIn(3, 30)
    fun setStaminaPreFullMin(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_STAMINA_PRE_FULL_MIN, v.coerceIn(3, 30)).apply()
    fun staminaPreFullNotifiedToday(c: Context): Boolean =
        sp(c).getString(staminaKey(c, KEY_STAMINA_PRE_FULL_DONE), "") == todayStr()
    fun markStaminaPreFullNotified(c: Context) =
        sp(c).edit().putString(staminaKey(c, KEY_STAMINA_PRE_FULL_DONE), todayStr()).apply()

    // 派遣即将完成预告开关 / 提前分钟数 / 已预告名单（按日期 + 派遣名）
    fun expeditionPreDoneNotify(c: Context): Boolean = sp(c).getBoolean(KEY_EXPEDITION_PRE_DONE, true)
    fun setExpeditionPreDoneNotify(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_EXPEDITION_PRE_DONE, v).apply()
    fun expeditionPreDoneMin(c: Context): Int =
        sp(c).getInt(KEY_EXPEDITION_PRE_DONE_MIN, 10).coerceIn(3, 60)
    fun setExpeditionPreDoneMin(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_EXPEDITION_PRE_DONE_MIN, v.coerceIn(3, 60)).apply()
    fun expeditionPreDoneAlready(c: Context, name: String): Boolean {
        val raw = sp(c).getString(staminaKey(c, KEY_EXPEDITION_PRE_DONE_SET), "") ?: ""
        if (raw.isBlank()) return false
        val parts = raw.split("|")
        val day = parts.firstOrNull()
        if (day != todayStr()) return false
        return parts.drop(1).contains(name)
    }
    fun markExpeditionPreDone(c: Context, name: String) {
        val raw = sp(c).getString(staminaKey(c, KEY_EXPEDITION_PRE_DONE_SET), "") ?: ""
        val parts = raw.split("|")
        val list = if (parts.firstOrNull() == todayStr()) parts.drop(1).toMutableList() else mutableListOf()
        if (name.isNotBlank() && !list.contains(name)) list.add(name)
        sp(c).edit().putString(
            staminaKey(c, KEY_EXPEDITION_PRE_DONE_SET),
            (listOf(todayStr()) + list).joinToString("|")
        ).apply()
    }

    /** 上次看到的体力值（0=未知），用于判断「跨过阈值/回满」而非重复通知。 */
    fun staminaLastCurrent(c: Context): Int =
        sp(c).getInt(staminaKey(c, KEY_STAMINA_LAST_CURRENT), 0)
    fun setStaminaLastCurrent(c: Context, v: Int) =
        sp(c).edit().putInt(staminaKey(c, KEY_STAMINA_LAST_CURRENT), v).apply()
    fun staminaLastCheckAt(c: Context): Long =
        sp(c).getLong(staminaKey(c, KEY_STAMINA_LAST_CHECK), 0L)
    fun setStaminaLastCheckAt(c: Context, v: Long) =
        sp(c).edit().putLong(staminaKey(c, KEY_STAMINA_LAST_CHECK), v).apply()

    /** 派遣完成通知每日只发一次，避免 15 分钟周期重复打扰。 */
    fun expeditionNotifiedToday(c: Context): Boolean =
        sp(c).getString(staminaKey(c, KEY_EXPEDITION_NOTIFIED), "") == todayStr()
    fun markExpeditionNotified(c: Context) =
        sp(c).edit().putString(staminaKey(c, KEY_EXPEDITION_NOTIFIED), todayStr()).apply()

    /** 体力/派遣检查间隔（分钟），可调；默认 10 分钟。 */
    private const val KEY_STAMINA_INTERVAL_MIN = "stamina_interval_min"
    fun staminaIntervalMin(c: Context): Int =
        sp(c).getInt(KEY_STAMINA_INTERVAL_MIN, 10).coerceIn(5, 120)
    fun setStaminaIntervalMin(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_STAMINA_INTERVAL_MIN, v.coerceIn(5, 120)).apply()

    /** Cookie 健康检查间隔（分钟），可调；默认 60 分钟。 */
    private const val KEY_COOKIE_INTERVAL_MIN = "cookie_check_interval_min"
    fun cookieCheckIntervalMin(c: Context): Int =
        sp(c).getInt(KEY_COOKIE_INTERVAL_MIN, 60).coerceIn(15, 240)
    fun setCookieCheckIntervalMin(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_COOKIE_INTERVAL_MIN, v.coerceIn(15, 240)).apply()

    // ---------- 抽卡攒抽规划（手动填写；按 UID 隔离，旧全局键回落兼容） ----------
    private const val KEY_GACHA_JADE = "gacha_stored_jade"
    private const val KEY_GACHA_PASS = "gacha_stored_pass"
    private const val KEY_GACHA_STD = "gacha_stored_std"
    private fun gachaKey(base: String, c: Context): String {
        val u = uid(c)
        return if (u.isBlank()) base else "${base}_$u"
    }
    /** 读取：新按 UID 键未写入（-1）时回落旧全局键，兼容历史数据。 */
    private fun readGacha(c: Context, base: String): Int {
        val v = sp(c).getInt(gachaKey(base, c), -1)
        if (v >= 0) return v
        return sp(c).getInt(base, 0)
    }
    private fun writeGacha(c: Context, base: String, v: Int) {
        val val0 = v.coerceAtLeast(0)
        sp(c).edit().putInt(gachaKey(base, c), val0).remove(base).apply()
    }
    fun gachaJade(c: Context): Int = readGacha(c, KEY_GACHA_JADE)
    fun setGachaJade(c: Context, v: Int) = writeGacha(c, KEY_GACHA_JADE, v)
    fun gachaPass(c: Context): Int = readGacha(c, KEY_GACHA_PASS)
    fun setGachaPass(c: Context, v: Int) = writeGacha(c, KEY_GACHA_PASS, v)
    /** 普通通票（星轨通票，仅可抽常驻池，不计入 UP 角色规划）。 */
    fun gachaStd(c: Context): Int = sp(c).getInt(gachaKey(KEY_GACHA_STD, c), 0)
    fun setGachaStd(c: Context, v: Int) =
        sp(c).edit().putInt(gachaKey(KEY_GACHA_STD, c), v.coerceAtLeast(0)).apply()

    // ---------- Flow 包装方法（兼容 Compose collectAsState） ----------
    fun getRemindEnabled(context: Context) = kotlinx.coroutines.flow.flowOf(reminderEnabled(context))
    fun getRemindTime(context: Context) = kotlinx.coroutines.flow.flowOf(Pair(remindHour(context), remindMinute(context)))
    fun getThemeMode(context: Context) = themeModeObservable(context)
    fun getAmoledDark(context: Context) = amoledObservable(context)
    fun getGuideFinished(context: Context) = kotlinx.coroutines.flow.flowOf(isGuideFinished(context))
    fun getVibrate(context: Context) = kotlinx.coroutines.flow.flowOf(remindVibrate(context))
    fun getSound(context: Context) = kotlinx.coroutines.flow.flowOf(soundEnabled(context))
    fun getSnoozeInterval(context: Context) = kotlinx.coroutines.flow.flowOf(snoozeIntervalMin(context))
    fun getPlayTimeTarget(context: Context) = kotlinx.coroutines.flow.flowOf(playTargetMinutes(context))
    fun getCookie(context: Context) = kotlinx.coroutines.flow.flowOf(cookie(context))
    fun getUid(context: Context) = kotlinx.coroutines.flow.flowOf(uid(context))
    fun getRemindSoundUri(context: Context) = kotlinx.coroutines.flow.flowOf(remindSoundUri(context) ?: "")
    fun getRemindSoundUriSync(context: Context): String = remindSoundUri(context) ?: ""

    fun getCheckinDates(context: Context) = kotlinx.coroutines.flow.flowOf(checkinDateList(context))
    suspend fun addCheckinDate(context: Context, date: String) {
        saveCheckHistory(context, date, true)
    }
    suspend fun clearCheckinDates(context: Context) {
        val ed = sp(context).edit()
        (sp(context).all).keys
            .filter { it.startsWith("check_history_") || it.startsWith("checkin_note_") }
            .forEach { ed.remove(it) }
        ed.apply()
    }

    /**
     * 连续签到天数（纯本地日期列表算法）：
     * 今天未打卡则从昨天起算；连续命中累加，遇断档停止，上限 365 天。
     */
    fun getStreakDays(checkinDates: List<String>): Int {
        if (checkinDates.isEmpty()) return 0
        val set = checkinDates.toHashSet()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = java.util.Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!set.contains(fmt.format(cal.time))) cal.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        repeat(365) {
            if (set.contains(fmt.format(cal.time))) {
                streak++
                cal.add(Calendar.DAY_OF_YEAR, -1)
            } else return@repeat
        }
        return streak
    }

    // ---------- Set 方法（兼容 Compose） ----------
    suspend fun setRemindEnabled(context: Context, enabled: Boolean) = setReminderEnabled(context, enabled)
    suspend fun setVibrate(context: Context, enabled: Boolean) = setRemindVibrate(context, enabled)
    suspend fun setSound(context: Context, enabled: Boolean) = setSoundEnabled(context, enabled)
    suspend fun setPlayTimeTarget(context: Context, minutes: Int) = setPlayTargetMinutes(context, minutes)
    suspend fun setSnoozeInterval(context: Context, minutes: Int) = setSnoozeIntervalMin(context, minutes)

    fun isTodayCheckedIn(context: Context): Boolean = isDayChecked(context, todayNaturalStr())
}
