package com.mh.starrailcheckin.network

import android.content.Context
import android.os.Build
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 米游社用户信息 */
data class UserInfo(val nickname: String, val avatarUrl: String)

/** 星穹铁道游戏角色 */
data class GameRole(val uid: String, val region: String, val regionName: String, val nickname: String)

/** 签到奖励条目：名称、数量、高清图标直链。 */
data class SignAward(
    val name: String,
    val count: Int,
    val icon: String = ""
)

/** 米游社每日签到信息 */
data class SignInfo(
    val signed: Boolean,
    val totalSignDay: Int,
    val todayReward: String,
    val awards: List<SignAward>
)

/** 签到结果 */
data class SignResult(
    val success: Boolean,
    val message: String
)

/** 异相仲裁单期战绩 */
data class PeakRecord(
    val groupName: String,
    val bossName: String,
    val bossStars: Int,
    val mobStars: Int,
    val battleNum: Int,
    val buffName: String,
    val hardMode: Boolean,
    val hardModeName: String = "",
    val mobCount: Int = 3
) {
    val totalStars: Int get() = bossStars + mobStars
    val maxStars: Int get() = 3 + mobCount.coerceAtLeast(0) * 3
}

/** 异相仲裁战绩 */
data class PeakInfo(
    val scheduleName: String,
    val hasData: Boolean,
    val records: List<PeakRecord>,
    val periodText: String = "",
    /** 数据获取时间戳（缓存命中时沿用首次获取时间） */
    val fetchedAt: Long = 0L
)

/** 战绩单队上阵角色 */
data class ChallengeAvatar(
    val id: Int,
    val level: Int,
    val name: String,
    val rarity: Int
)

/** 战绩一个节点（上半 / 下半 / 第三路） */
data class ChallengeNode(
    val label: String,
    val roundNum: Int,
    val avatars: List<ChallengeAvatar>
)

/** 逐光捡金单关战绩 */
data class ChallengeStageRecord(
    val title: String,
    val subtitle: String,
    val stars: Int,
    val maxStars: Int,
    val finished: Boolean,
    val nodes: List<ChallengeNode> = emptyList(),
    val bossName: String = ""
)

/** 逐光捡金某玩法当前期战绩 */
data class ChallengeModeInfo(
    val scheduleName: String,
    val periodText: String,
    val totalStars: Int,
    val maxStars: Int,
    val records: List<ChallengeStageRecord>,
    val hasData: Boolean,
    /** 数据获取时间戳（缓存命中时沿用首次获取时间） */
    val fetchedAt: Long = 0L
)

/** 抽卡记录中的一条 */
data class GachaRecord(
    val id: String,
    val gachaType: Int,
    val gachaId: String,   // 所属卡池ID，用于按卡池自举判定歪/不歪
    val itemType: String,
    val name: String,
    val rank: Int,
    val time: String
)

/** 抽卡记录分页结果 */
data class GachaPage(
    val records: List<GachaRecord>,
    val hasMore: Boolean,
    val endId: String
)

/** 二维码登录状态 */
data class QrLoginInfo(
    val ticket: String,
    val qrUrl: String
)

class MicaException(val retcode: Int, message: String) : Exception(message)

/**
 * 米游社接口封装（登录 / 签到 / 角色 / 开拓力 / 跃迁记录）。
 *
 * 本次重写依据 UIGF mihoyo-api-collection 与 genshin.py、MihoyoBBSTools、
 * nonebot-plugin-mystool 等长期维护项目的实现：
 *
 * 1. 扫码登录改用网页端通行证 `passport-api.miyoushe.com` 的
 *    `web/createQRLogin` + `web/queryQRLoginStatus`，确认后直接下发
 *    `cookie_token_v2 / ltoken_v2 / account_id_v2 / ltmid_v2`；
 * 2. Cookie 严格遵循 UIGF 配对规则：短 token 配 `ltuid/account_id`，
 *    `v2_` token 配 `ltmid_v2/account_id_v2`，绝不把短 token 塞进 `_v2`；
 * 3. 签到改用星铁当前活动 `e202304121516551` + `/event/luna/hkrpg`，
 *    签名使用签到专用 salt 的 DS1（与 genshin.py 一致），并保留多套兜底；
 * 4. DS 按 UIGF 生成：DS2 需要 body/query 参与签名，DS1 为 6 位随机串。
 */
object MicaApi {
    // ==================== 常量 ====================

    /** 国服星穹铁道签到活动 ID（此前误用了国际服 e202303301540311） */
    const val ACT_ID_HKRPG = "e202304121516551"

    private const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 米游社 2.117.0 内置 WebView 的真实 App 版本，游戏记录接口按此版本上报 */
    private const val UA_APP_VERSION = "2.117.0"
    private const val H5_TOOL_VERSION = "v4.7.1"
    private const val H5_PAGE = "v4.7.1_#/rpg"
    private const val H5_CHROME_VERSION = "156.0.8078.47"

    private val UA_APP: String
        get() = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID}; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$H5_CHROME_VERSION " +
            "Mobile Safari/537.36 miHoYoBBS/$UA_APP_VERSION"

    private const val CLIENT_TYPE_WEB = "5"
    private const val CLIENT_TYPE_APP = "2"
    private const val CLIENT_TYPE_QR = "4"

    private const val VERSION_WEB_RECORD = UA_APP_VERSION
    /** 旧签到兜底接口使用的网页版本，与游戏记录 H5 的 2.117.0 无关 */
    private const val VERSION_WEB_SIGN_LEGACY = "2.11.1"
    private const val VERSION_WEB_SIGN = "2.70.1"

    private const val PASSENGER_APP_ID = "bll8iq97cem8"

    private const val TAKUMI_HOST = ApiHosts.TAKUMI
    /** 官方设备指纹签发接口（国服） */
    private const val GET_FP_URL = "${ApiHosts.PUBLIC_DATA}/device-fp/api/getFp"
    /** getFp 失败后的负缓存时长，避免每次请求都重试 */
    private const val FP_RETRY_INTERVAL_MS = 10 * 60 * 1000L
    private const val LUNA_BASE = "$TAKUMI_HOST/event/luna/hkrpg"
    private const val LEGACY_SIGN_BASE = "$TAKUMI_HOST/event/bbs_sign_reward"

    private val TAKUMI_HOSTS = listOf(
        ApiHosts.TAKUMI_MIYOUSHE,
        ApiHosts.TAKUMI
    )
    private val RECORD_HOSTS = listOf(
        ApiHosts.TAKUMI_RECORD
    )
    private val BBS_HOSTS = listOf(
        ApiHosts.BBS_API_MIYOUSHE,
        ApiHosts.BBS_API
    )
    private val PASSPORT_HOSTS = listOf(
        ApiHosts.PASSPORT_API_MIYOUSHE,
        ApiHosts.PASSPORT_API
    )

    private const val QR_CREATE_PATH = "/account/ma-cn-passport/web/createQRLogin"
    private const val QR_QUERY_PATH = "/account/ma-cn-passport/web/queryQRLoginStatus"

    private const val ORIGIN_WEB = ApiHosts.WEBSTATIC
    private const val REFERER_WEB = "${ApiHosts.WEBSTATIC}/"
    private const val ORIGIN_ACT = ApiHosts.ACT
    private const val REFERER_ACT = "${ApiHosts.ACT}/"
    private const val ORIGIN_APP = ApiHosts.APP
    private const val REFERER_APP = "${ApiHosts.APP}/"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = HttpClientProvider.default

    /** 上次成功查询开拓力的时间戳（用于30秒刷新冷却） */
    private var lastStaminaQueryTime: Long = 0L

    // ==================== 基础工具 ====================

    private data class ApiResponse(
        val httpCode: Int,
        val json: JSONObject?,
        val raw: String,
        val setCookies: List<String>
    )

    private fun execute(req: Request): ApiResponse {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            return ApiResponse(resp.code, json, text, resp.headers("Set-Cookie"))
        }
    }

    private fun httpGet(url: String, headers: Headers): ApiResponse =
        execute(Request.Builder().url(url).headers(headers).get().build())

    private fun httpPost(url: String, headers: Headers, body: String?): ApiResponse {
        val rb = if (body == null) "".toRequestBody(null) else body.toRequestBody(JSON_TYPE)
        return execute(Request.Builder().url(url).headers(headers).post(rb).build())
    }

    /** 解析 JSON；保留 HTTP 状态和非敏感响应摘要，避免只显示“返回格式错误”。 */
    private fun jsonOrThrow(resp: ApiResponse, what: String): JSONObject {
        resp.json?.let { return it }
        val summary = resp.raw.replace(Regex("\\s+"), " ").trim().take(180)
        val detail = if (summary.isBlank()) "空响应" else summary
        val hint = if (resp.raw.trimStart().startsWith("<")) "（疑似风控网页）" else ""
        throw MicaException(-1, "$what 返回格式错误$hint（HTTP ${resp.httpCode}）：$detail")
    }

    /** 解析 Cookie 字符串。同时兼容换行分隔。 */
    private fun parseCookie(raw: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        raw.split(';', '\n').map { it.trim() }.filter { it.contains('=') }.forEach {
            val i = it.indexOf('=')
            val k = it.substring(0, i).trim()
            val v = it.substring(i + 1).trim().removeSurrounding("\"")
            if (k.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun joinCookie(map: Map<String, String>): String =
        map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun pick(map: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> map[k]?.takeIf { it.isNotBlank() } }

    /**
     * 按 UIGF 规则规范化 Cookie。
     * - 短 ltoken / cookie_token 只能配 ltuid / account_id
     * - v2_ 开头的 token 必须配 ltmid_v2 / account_id_v2
     * - 不再把 stoken_v2 和 stoken 互抄（两者不通用）
     */
    fun normalizeCookie(raw: String, includeStoken: Boolean = true): String {
        val src = parseCookie(raw)
        val aid = pick(src, "account_id_v2", "account_id", "ltuid_v2", "ltuid", "stuid")
        val mid = pick(src, "ltmid_v2", "account_mid_v2", "mid")
        val lt = pick(src, "ltoken_v2", "ltoken")
        val ct = pick(src, "cookie_token_v2", "cookie_token")
        val st = pick(src, "stoken_v2", "stoken")

        val out = LinkedHashMap<String, String>()
        if (aid != null) {
            out["stuid"] = aid
            out["ltuid"] = aid
            out["account_id"] = aid
        }
        if (mid != null) out["mid"] = mid

        if (lt != null) {
            if (lt.startsWith("v2_")) {
                out["ltoken_v2"] = lt
                if (mid != null) out["ltmid_v2"] = mid
            } else {
                out["ltoken"] = lt
            }
        }
        if (ct != null) {
            if (ct.startsWith("v2_")) {
                out["cookie_token_v2"] = ct
                if (aid != null) out["account_id_v2"] = aid
                if (mid != null) out["account_mid_v2"] = mid
            } else {
                out["cookie_token"] = ct
            }
        }
        if (includeStoken && st != null) {
            if (st.startsWith("v2_")) out["stoken_v2"] = st else out["stoken"] = st
            if (mid != null) out["mid"] = mid
        }
        if (st == null) {
            pick(src, "login_ticket")?.let { out["login_ticket"] = it }
        }
        // 保留官方设备 Cookie（与请求头不要求一致，原样保留更接近真实会话）
        pick(src, "DEVICEFP")?.let { out["DEVICEFP"] = it }
        pick(src, "_MHYUUID", "mhy_uuid")?.let { out["_MHYUUID"] = it }
        return joinCookie(out)
    }

    /** 仅用于 web 接口的 Cookie（不带 stoken，避免 note/sign 被拒）。 */
    private fun webCookie(context: Context): String =
        normalizeCookie(Prefs.cookie(context), includeStoken = false)

    private fun fullCookie(context: Context): String =
        normalizeCookie(Prefs.cookie(context), includeStoken = true)

    /** Cookie 中是否已经有可用于接口的凭证。 */
    fun cookieHasCredentials(raw: String): Boolean {
        val m = parseCookie(raw)
        val hasId = pick(m, "account_id_v2", "account_id", "ltuid_v2", "ltuid", "stuid") != null
        val hasToken = pick(m, "ltoken_v2", "ltoken", "cookie_token_v2", "cookie_token", "stoken_v2", "stoken") != null
        val hasTicket = m["login_ticket"] != null
        return (hasId && hasToken) || (hasId && hasTicket)
    }

    /** 全局合法 device_id：脏数据回退到稳定 UUID 并写回 Prefs。 */
    private fun safeDeviceId(context: Context): String {
        val id = Prefs.deviceId(context).trim()
        val uuidRegex = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
        if (uuidRegex.matches(id)) return id
        val fresh = java.util.UUID.randomUUID().toString()
        runCatching { Prefs.sp(context).edit().putString("device_id", fresh).apply() }
        return fresh
    }

    /**
     * 当前可用的设备指纹：手动绑定的抓包值优先，其次是官方 getFp 签发并持久化的值。
     * 都不存在时返回空，由 [ensureDeviceFp] 在请求前按需生成。
     */
    private fun currentDeviceFp(context: Context): String {
        val fp = Prefs.deviceFp(context).trim()
        return if (fp.isNotBlank() && !fp.startsWith("{") && fp.length >= 8) fp else ""
    }

    private fun qrDeviceFp(context: Context): String =
        currentDeviceFp(context).ifBlank { DSAlgorithm.md5(safeDeviceId(context)).take(13) }

    /**
     * 调用官方设备指纹接口生成 device_fp，并持久化复用。
     *
     * 与 genshin.py 的 generate_fp 保持一致：platform=2、app_name=bbs_cn，
     * device_fp 先给 13 位十六进制随机种子，由服务端签发正式指纹。
     * 手动绑定过抓包设备时该值不会覆盖绑定优先级。
     */
    fun generateDeviceFp(context: Context, force: Boolean = false): String {
        val existing = currentDeviceFp(context)
        if (!force && existing.isNotBlank()) return existing
        if (!force && Prefs.hasBoundDevice(context)) return existing

        val chars = "0123456789abcdef"
        val random = java.security.SecureRandom()
        val seedFp = (1..13).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val payload = JSONObject().apply {
            put("device_id", safeDeviceId(context))
            put("device_fp", seedFp)
            put("seed_id", java.util.UUID.randomUUID().toString().lowercase(Locale.US))
            put("seed_time", System.currentTimeMillis().toString())
            put("platform", "2")
            put("app_name", "bbs_cn")
            put(
                "ext_fields",
                JSONObject().apply {
                    put("oaid", "")
                    put("board", Build.BOARD)
                }.toString()
            )
            put("bbs_device_id", java.util.UUID.randomUUID().toString().lowercase(Locale.US))
        }
        val headers = Headers.Builder()
            .add("User-Agent", UA_WEB)
            .add("Accept", "application/json")
            .add("Content-Type", JSON_TYPE.toString())
            .build()
        val resp = httpPost(GET_FP_URL, headers, payload.toString())
        val json = jsonOrThrow(resp, "设备指纹生成")
        val data = json.optJSONObject("data")
        val code = data?.optInt("code", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: json.optInt("retcode", -1)
        val fp = data?.optString("device_fp", "").orEmpty()
        if (fp.isBlank()) {
            val msg = data?.optString("msg", "").orEmpty()
                .ifBlank { json.optString("message", "") }
            throw MicaException(code, "设备指纹生成失败（code=$code）${if (msg.isBlank()) "" else "：$msg"}")
        }
        Prefs.setDeviceFp(context, fp)
        LogHelper.i("Mica", "设备指纹已生成并保存")
        return fp
    }

    @Volatile private var lastFpAttemptAt = 0L
    @Volatile private var lastFpAttemptFailed = false

    /**
     * 请求前确保有设备指纹；生成失败时静默返回空，并用 10 分钟负缓存
     * 避免每次请求都去打 getFp 接口。
     */
    fun ensureDeviceFp(context: Context, force: Boolean = false): String {
        val existing = currentDeviceFp(context)
        if (existing.isNotBlank()) return existing
        val now = System.currentTimeMillis()
        if (!force && lastFpAttemptFailed && now - lastFpAttemptAt < FP_RETRY_INTERVAL_MS) return ""
        lastFpAttemptAt = now
        return runCatching { generateDeviceFp(context, force = force) }
            .onSuccess { lastFpAttemptFailed = false }
            .onFailure {
                lastFpAttemptFailed = true
                LogHelper.w("Mica", "设备指纹生成失败：${it.message ?: it.javaClass.simpleName}")
            }
            .getOrDefault("")
    }

    fun regionName(region: String): String = when (region) {
        "prod_gf_cn" -> "官服"
        "prod_qd_cn" -> "B服"
        "prod_gf_us" -> "美服"
        "prod_gf_eu" -> "欧服"
        "prod_gf_jp" -> "日服"
        "prod_gf_kr" -> "韩服"
        "prod_gf_sg" -> "亚服"
        else -> region
    }

    /** 请求前的基础登录校验。 */
    private fun requireLogin(context: Context) {
        if (Prefs.cookie(context).isBlank()) {
            throw MicaException(10001, "尚未登录，请先扫码登录或粘贴 Cookie")
        }
    }

    private fun requireRole(context: Context): Pair<String, String> {
        requireLogin(context)
        val uid = Prefs.uid(context).trim()
        val region = Prefs.server(context).trim()
        if (uid.isBlank() || region.isBlank()) {
            throw MicaException(1008, "未绑定星穹铁道角色，请先刷新账号信息")
        }
        return uid to region
    }

    // ==================== 请求头 ====================

    /**
     * 设备 Cookie：官方 H5 请求的 Cookie 中会带 DEVICEFP、_MHYUUID，但实测其值与请求头
     * x-rpc-device_fp 并不要求一致（官方请求本身就不同），因此保留 Cookie 中已有值，
     * 仅在缺失时补齐，避免破坏官方会话特征。
     */
    private fun deviceCookieSuffix(context: Context, cookie: String): String {
        val lower = cookie.lowercase(Locale.ROOT)
        fun has(key: String) = lower.contains("$key=")
        val parts = mutableListOf<String>()
        val fp = currentDeviceFp(context)
        if (fp.isNotBlank() && !has("devicefp")) parts += "DEVICEFP=$fp"
        val deviceId = safeDeviceId(context)
        if (!has("_mhyuuid") && !has("mhy_uuid")) parts += "_MHYUUID=$deviceId"
        return if (parts.isEmpty()) "" else "; " + parts.joinToString("; ")
    }

    /** H5 请求头里的设备名需要 URL 编码（官方为 Xiaomi%2023116PN5BC 这种形式）。 */
    private fun h5DeviceName(): String =
        URLEncoder.encode("${Build.BRAND} ${Build.MODEL}", "UTF-8").replace("+", "%20")

    /**
     * 网页端请求头（client_type=5）。
     *
     * h5=true 时完全对齐米游社 2.117.0 内置 WebView 的真实游戏记录请求：
     * Android WebView UA、app_version=2.117.0、platform=5、x-rpc-page/tool_version、
     * URL 编码的 device_name，并去掉官方请求中不存在的 channel/language/model/brand。
     */
    private fun webHeaders(
        context: Context,
        query: String,
        body: String,
        cookie: String,
        version: String = VERSION_WEB_SIGN_LEGACY,
        salt: String = DSAlgorithm.SALT_4X,
        ds: String? = null,
        origin: String = ORIGIN_WEB,
        referer: String = REFERER_WEB,
        signGame: String? = null,
        appId: String? = null,
        h5: Boolean = false
    ): Headers {
        val b = Headers.Builder()
            .add("User-Agent", if (h5) UA_APP else UA_WEB)
            .add("Accept", "application/json, text/plain, */*")
            .add(
                "Accept-Language",
                if (h5) "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7" else "zh-CN,zh-Hans;q=0.9"
            )
            .add("x-rpc-app_version", version)
            .add("x-rpc-client_type", CLIENT_TYPE_WEB)
        if (!h5) b.add("x-rpc-language", "zh-cn")
        b.add("x-rpc-platform", if (h5) "5" else "android")
        if (!h5) b.add("x-rpc-channel", "miyousheluodi")
        b.add("x-rpc-sys_version", Build.VERSION.RELEASE)
        b.add("x-rpc-device_id", safeDeviceId(context))
        b.add("x-rpc-device_name", if (h5) h5DeviceName() else "${Build.BRAND} ${Build.MODEL}")
        if (!h5) {
            b.add("x-rpc-device_model", Build.MODEL)
            b.add("x-rpc-device_brand", Build.BRAND)
        }
        if (h5) {
            b.add("x-rpc-page", H5_PAGE)
            b.add("x-rpc-tool_version", H5_TOOL_VERSION)
        }
        b.add("X-Requested-With", "com.mihoyo.hyperion")
            .add("Origin", origin)
            .add("Referer", referer)
            .add("DS", ds ?: DSAlgorithm.ds2(salt, query, body))
        val fp = currentDeviceFp(context)
        if (fp.isNotBlank()) b.add("x-rpc-device_fp", fp)
        if (signGame != null) {
            // 签到接口对风控较敏感，补齐 genshin.py 中的可选设备头
            b.add("x-rpc-signgame", signGame)
        }
        if (appId != null) b.add("x-rpc-app_id", appId)
        val fullCookie = cookie + deviceCookieSuffix(context, cookie)
        if (fullCookie.isNotBlank()) b.add("Cookie", fullCookie.trimStart(';', ' '))
        return b.build()
    }

    /** App 端请求头（client_type=2）。 */
    private fun appHeaders(
        context: Context,
        query: String,
        body: String,
        cookie: String,
        salt: String = DSAlgorithm.SALT_6X,
        origin: String = ORIGIN_APP,
        referer: String = REFERER_APP
    ): Headers {
        val b = Headers.Builder()
            .add("User-Agent", UA_APP)
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "zh-CN,zh-Hans;q=0.9")
            .add("x-rpc-app_version", UA_APP_VERSION)
            .add("x-rpc-client_type", CLIENT_TYPE_APP)
            .add("x-rpc-channel", "miyousheluodi")
            .add("x-rpc-language", "zh-cn")
            .add("x-rpc-platform", "android")
            .add("x-rpc-sys_version", Build.VERSION.RELEASE)
            .add("x-rpc-device_id", safeDeviceId(context))
            .add("x-rpc-device_name", "${Build.BRAND} ${Build.MODEL}")
            .add("x-rpc-device_model", Build.MODEL)
            .add("x-rpc-device_brand", Build.BRAND)
            .add("X-Requested-With", "com.mihoyo.hyperion")
            .add("Origin", origin)
            .add("Referer", referer)
            .add("DS", DSAlgorithm.ds2(salt, query, body))
        val fp = currentDeviceFp(context)
        if (fp.isNotBlank()) b.add("x-rpc-device_fp", fp)
        val fullCookie = cookie + deviceCookieSuffix(context, cookie)
        if (fullCookie.isNotBlank()) b.add("Cookie", fullCookie.trimStart(';', ' '))
        return b.build()
    }

    // ==================== 扫码登录（网页端通行证） ====================

    @Volatile private var qrSessionDeviceId: String = ""
    @Volatile private var qrSessionCookie: String = ""

    private fun resetQrSession() {
        qrSessionDeviceId = ""
        qrSessionCookie = ""
    }

    private fun qrDeviceId(context: Context): String {
        if (qrSessionDeviceId.isNotBlank()) return qrSessionDeviceId
        val id = safeDeviceId(context)
        qrSessionDeviceId = id
        return id
    }

    /**
     * 网页通行证扫码请求头。
     * 对应 genshin.py 中 QRCODE_HEADERS：app_id=bll8iq97cem8、client_type=4、game_biz=bbs_cn。
     */
    private fun qrHeaders(context: Context): Headers {
        val b = Headers.Builder()
            .add("User-Agent", UA_WEB)
            .add("Accept", "application/json")
            .add("Content-Type", "application/json; charset=utf-8")
            .add("x-rpc-app_id", PASSENGER_APP_ID)
            .add("x-rpc-client_type", CLIENT_TYPE_QR)
            .add("x-rpc-game_biz", "bbs_cn")
            .add("x-rpc-device_id", qrDeviceId(context))
            .add("x-rpc-device_fp", qrDeviceFp(context))
            .add("x-rpc-sdk_version", "2.31.0")
            .add("x-rpc-source", "v2.webLogin")
            .add("x-rpc-device_model", "Firefox%20131.0")
            .add("x-rpc-device_name", "Firefox")
            .add("Origin", ApiHosts.USER_MIYOUSHE)
            .add("Referer", "${ApiHosts.USER_MIYOUSHE}/")
        if (qrSessionCookie.isNotBlank()) b.add("Cookie", qrSessionCookie)
        return b.build()
    }

    /** 把响应 Set-Cookie 合并进扫码会话。 */
    private fun mergeSetCookies(resp: ApiResponse): String {
        val map = parseCookie(qrSessionCookie)
        resp.setCookies.forEach { sc ->
            sc.split(';').forEach { part ->
                val p = part.trim()
                val idx = p.indexOf('=')
                if (idx > 0) {
                    val k = p.substring(0, idx).trim()
                    val v = p.substring(idx + 1).trim().removeSurrounding("\"")
                    if (k.isNotBlank() && v.isNotEmpty() && k !in setOf(
                            "Path", "Expires", "Max-Age", "Domain",
                            "SameSite", "HttpOnly", "Secure", "Priority"
                        )
                    ) {
                        map[k] = v
                    }
                }
            }
        }
        qrSessionCookie = joinCookie(map)
        return qrSessionCookie
    }

    /** 创建网页扫码登录二维码。 */
    fun createQrLogin(context: Context): QrLoginInfo? {
        return runCatching {
            resetQrSession()
            val resp = httpPost("${PASSPORT_HOSTS[0]}$QR_CREATE_PATH", qrHeaders(context), null)
            mergeSetCookies(resp)
            LogHelper.i("Mica", "createQRLogin HTTP ${resp.httpCode} body=${resp.raw.take(300)}")
            val json = jsonOrThrow(resp, "二维码创建")
            val code = json.optInt("retcode", -1)
            if (code != 0) {
                LogHelper.w("Mica", "createQRLogin retcode=$code msg=${json.optString("message")}")
                return@runCatching null
            }
            val d = json.optJSONObject("data") ?: return@runCatching null
            val ticket = d.optString("ticket", "")
            val url = d.optString("url", "")
            if (ticket.isBlank() || url.isBlank()) return@runCatching null
            QrLoginInfo(ticket, url)
        }.getOrElse {
            LogHelper.e("Mica", "createQRLogin 失败", it)
            null
        }
    }

    /**
     * 轮询扫码状态，Confirmed 时保存 Cookie。
     * 返回值：Created / Scanned / Confirmed / Expired / Cancelled / RiskControl / Error
     */
    fun pollQrLoginStatus(context: Context, ticket: String): String {
        return runCatching {
            val body = JSONObject().put("ticket", ticket).toString()
            val resp = httpPost("${PASSPORT_HOSTS[0]}$QR_QUERY_PATH", qrHeaders(context), body)
            mergeSetCookies(resp)
            LogHelper.i("Mica", "queryQRLogin HTTP ${resp.httpCode} body=${resp.raw.take(400)}")
            val json = resp.json ?: return@runCatching "Error"
            when (json.optInt("retcode", -1)) {
                -3501 -> return@runCatching "Expired"
                -3505 -> return@runCatching "Cancelled"
                -3503 -> return@runCatching "RiskControl"
            }
            if (json.optInt("retcode", -1) != 0) return@runCatching "Error"

            val data = json.optJSONObject("data") ?: return@runCatching "Error"
            val status = data.optString("status", data.optString("stat", ""))
            when (status) {
                "Created", "Init", "" -> "Created"
                "Scanned" -> "Scanned"
                "Confirmed" -> {
                    val saved = saveQrCookie(context, data)
                    if (saved) {
                        runCatching { enrichTokensByStoken(context) }
                            .onFailure { LogHelper.w("Mica", "扫码后补全 token 失败: ${it.message}") }
                        resetQrSession()
                        "Confirmed"
                    } else {
                        "Error"
                    }
                }
                "Expired" -> "Expired"
                "Cancelled" -> "Cancelled"
                else -> status.ifBlank { "Unknown" }
            }
        }.getOrElse {
            LogHelper.e("Mica", "queryQRLogin 异常", it)
            "Error"
        }
    }

    /** 从 Set-Cookie / data.tokens / data.user_info 组合出 Cookie。 */
    private fun saveQrCookie(context: Context, data: JSONObject): Boolean {
        val map = parseCookie(qrSessionCookie)

        val userInfo = data.optJSONObject("user_info")
        if (userInfo != null) {
            val aid = userInfo.optString("aid", userInfo.optString("account_id", ""))
            val mid = userInfo.optString("mid", "")
            if (aid.isNotBlank()) {
                map["stuid"] = aid
                map["ltuid"] = aid
                map["account_id"] = aid
            }
            if (mid.isNotBlank()) map["mid"] = mid
        }

        data.optJSONObject("token")?.let { t ->
            val value = t.optString("token", "")
            if (value.isNotBlank()) {
                if (t.optInt("token_type", 1) == 1) {
                    if (value.startsWith("v2_")) map["stoken_v2"] = value else map["stoken"] = value
                }
            }
        }

        data.optJSONArray("tokens")?.let { tokens ->
            for (i in 0 until tokens.length()) {
                val t = tokens.optJSONObject(i) ?: continue
                val value = t.optString("token", t.optString("value", ""))
                if (value.isBlank()) continue
                val type = t.optInt("token_type", -1)
                val name = t.optString("name", "")
                when {
                    type == 1 || name.contains("stoken", true) ->
                        if (value.startsWith("v2_")) map["stoken_v2"] = value else map["stoken"] = value
                    type == 2 || name.contains("ltoken", true) ->
                        if (value.startsWith("v2_")) map["ltoken_v2"] = value else map["ltoken"] = value
                    type == 4 || name.contains("cookie_token", true) ->
                        if (value.startsWith("v2_")) map["cookie_token_v2"] = value else map["cookie_token"] = value
                }
            }
        }

        map.remove("aliyungf_tc")
        val raw = joinCookie(map)
        if (!cookieHasCredentials(raw)) {
            LogHelper.w("Mica", "扫码确认但凭证不完整 keys=${map.keys}")
            return false
        }
        Prefs.setCookie(context, normalizeCookie(raw, includeStoken = true))
        LogHelper.i("Mica", "扫码登录成功")
        return true
    }

    // ==================== Token 兑换 / 补全 ====================

    /** 当前 Cookie 是否具备自动续期所需的 stoken 与通行证 UID。 */
    fun cookieSupportsAutoRenew(raw: String): Boolean {
        val map = parseCookie(raw)
        return pick(map, "stoken_v2", "stoken") != null &&
            pick(map, "account_id_v2", "account_id", "ltuid_v2", "ltuid", "stuid") != null
    }

    /**
     * 用 stoken 兑换新 cookie_token / ltoken，并通过角色绑定接口复验。
     * 任一步最终失败都会恢复原 Cookie；没有 stoken 时明确拒绝，不虚报续期成功。
     */
    @Synchronized
    fun renewCookie(context: Context): String {
        val original = Prefs.cookie(context)
        val map = parseCookie(original)
        var stoken = pick(map, "stoken_v2", "stoken")
            ?: throw MicaException(10001, "当前 Cookie 不含 stoken，无法自动续期，请重新扫码或网页登录")
        val uid = pick(map, "account_id_v2", "account_id", "ltuid_v2", "ltuid", "stuid")
            ?: throw MicaException(10001, "Cookie 缺少通行证 UID，无法自动续期")
        val mid = pick(map, "mid", "account_mid_v2", "ltmid_v2").orEmpty()
        var exchanged = false
        var lastError = "stoken 兑换未返回新凭证"

        fun stokenCookie() = buildString {
            val key = if (stoken.startsWith("v2_")) "stoken_v2" else "stoken"
            append("$key=$stoken; stuid=$uid")
            if (mid.isNotBlank()) append("; mid=$mid")
        }

        fun recordFailure(e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            LogHelper.w("Mica", "Cookie 续期步骤失败：$lastError")
        }

        // 短 stoken 先升级为 v2；升级失败仍继续使用原 stoken 尝试兑换。
        if (!stoken.startsWith("v2_")) {
            try {
                val url = "${PASSPORT_HOSTS[0]}/account/ma-cn-session/app/getTokenBySToken"
                val headers = Headers.Builder()
                    .add("User-Agent", UA_APP)
                    .add("Accept", "application/json")
                    .add("x-rpc-app_id", PASSENGER_APP_ID)
                    .add("x-rpc-client_type", CLIENT_TYPE_APP)
                    .add("x-rpc-device_id", safeDeviceId(context))
                    .add("x-rpc-device_fp", qrDeviceFp(context))
                    .add("Cookie", stokenCookie())
                    .build()
                val resp = execute(
                    Request.Builder().url(url).headers(headers).post("".toRequestBody(null)).build()
                )
                val json = jsonOrThrow(resp, "stoken 升级")
                val code = json.optInt("retcode", -1)
                if (code != 0) {
                    throw MicaException(code, json.optString("message").ifBlank { "stoken 升级失败（retcode=$code）" })
                }
                val v2 = json.optJSONObject("data")?.optJSONObject("token")
                    ?.optString("token", "").orEmpty()
                if (v2.isBlank()) throw MicaException(-1, "stoken 升级响应缺少 token")
                map.remove("stoken")
                map["stoken_v2"] = v2
                stoken = v2
            } catch (e: Throwable) {
                recordFailure(e)
            }
        }

        val cookieTokenHosts = listOf(
            "$TAKUMI_HOST/auth/api/getCookieAccountInfoBySToken",
            "${PASSPORT_HOSTS[0]}/account/auth/api/getCookieAccountInfoBySToken"
        )
        for (base in cookieTokenHosts) {
            try {
                val query = "stoken=${URLEncoder.encode(stoken, "UTF-8")}&uid=$uid"
                val headers = Headers.Builder()
                    .add("User-Agent", UA_WEB)
                    .add("Accept", "application/json")
                    .add("x-rpc-device_id", safeDeviceId(context))
                    .add("x-rpc-device_fp", qrDeviceFp(context))
                    .add("Cookie", stokenCookie())
                    .build()
                val resp = httpGet("$base?$query", headers)
                val json = jsonOrThrow(resp, "cookie_token 续期")
                val code = json.optInt("retcode", -1)
                if (code != 0) {
                    throw MicaException(code, json.optString("message").ifBlank { "cookie_token 兑换失败（retcode=$code）" })
                }
                val data = json.optJSONObject("data")
                    ?: throw MicaException(-1, "cookie_token 兑换响应缺少 data")
                val token = data.optString("cookie_token", "")
                if (token.isBlank()) throw MicaException(-1, "cookie_token 兑换响应缺少 token")
                if (token.startsWith("v2_")) {
                    map.remove("cookie_token")
                    map["cookie_token_v2"] = token
                } else {
                    map.remove("cookie_token_v2")
                    map["cookie_token"] = token
                }
                exchanged = true
                break
            } catch (e: Throwable) {
                recordFailure(e)
            }
        }

        try {
            val headers = Headers.Builder()
                .add("User-Agent", UA_WEB)
                .add("Accept", "application/json")
                .add("x-rpc-app_id", PASSENGER_APP_ID)
                .add("x-rpc-device_id", safeDeviceId(context))
                .add("x-rpc-device_fp", qrDeviceFp(context))
                .add("Cookie", stokenCookie())
                .build()
            val resp = httpGet("${PASSPORT_HOSTS[0]}/account/auth/api/getLTokenBySToken", headers)
            val json = jsonOrThrow(resp, "ltoken 续期")
            val code = json.optInt("retcode", -1)
            if (code != 0) {
                throw MicaException(code, json.optString("message").ifBlank { "ltoken 兑换失败（retcode=$code）" })
            }
            val token = json.optJSONObject("data")?.optString("ltoken", "").orEmpty()
            if (token.isBlank()) throw MicaException(-1, "ltoken 兑换响应缺少 token")
            if (token.startsWith("v2_")) {
                map.remove("ltoken")
                map["ltoken_v2"] = token
            } else {
                map.remove("ltoken_v2")
                map["ltoken"] = token
            }
            exchanged = true
        } catch (e: Throwable) {
            recordFailure(e)
        }

        if (!exchanged) throw MicaException(-1, "Cookie 自动续期失败：$lastError")

        val candidate = normalizeCookie(joinCookie(map), includeStoken = true)
        Prefs.setCookie(context, candidate)
        try {
            val role = validateAccount(context).firstOrNull { it.uid.isNotBlank() }
                ?: throw MicaException(1008, "续期后未读取到星穹铁道角色")
            Prefs.setUid(context, role.uid)
            Prefs.setServer(context, role.region)
            if (Prefs.nickname(context).isBlank()) Prefs.setNickname(context, role.nickname)
            Prefs.saveCurrentAccount(context)
            LogHelper.i("Mica", "Cookie 自动续期并复验成功")
            return "Cookie 自动续期成功，账号验证通过"
        } catch (e: Throwable) {
            Prefs.setCookie(context, original)
            LogHelper.w("Mica", "Cookie 续期后复验失败，已恢复原 Cookie：${e.message}")
            throw if (e is MicaException) e
                else MicaException(-1, "续期后账号验证失败，已恢复原 Cookie：${e.message}")
        }
    }

    /** 登录时尽力补全 token；失败不阻断原有凭证的账号校验。 */
    private fun enrichTokensByStoken(context: Context) {
        runCatching { renewCookie(context) }
            .onFailure { LogHelper.w("Mica", "登录后补全 token 失败：${it.message}") }
    }

    /**
     * 完成网页登录：把 login_ticket 换成 ltoken，补全 Cookie，并校验星铁角色。
     * @return Pair<提示信息, 是否成功>
     */
    fun completeWebLogin(context: Context, rawCookie: String): Pair<String, Boolean> {
        if (!rawCookie.contains("=")) return Pair("格式错误：请粘贴完整 Cookie 字符串", false)

        val map = parseCookie(rawCookie)
        val uid = pick(map, "account_id", "ltuid", "stuid", "account_id_v2", "ltuid_v2")

        // login_ticket -> ltoken（UIGF：token_types=3 目前只返回 ltoken）
        val loginTicket = map["login_ticket"]
        if (loginTicket != null && uid != null &&
            pick(map, "ltoken", "ltoken_v2", "cookie_token", "cookie_token_v2", "stoken", "stoken_v2") == null
        ) {
            runCatching {
                val url = "$TAKUMI_HOST/auth/api/getMultiTokenByLoginTicket" +
                    "?login_ticket=${URLEncoder.encode(loginTicket, "UTF-8")}&token_types=3&uid=$uid"
                val headers = Headers.Builder()
                    .add("User-Agent", UA_WEB)
                    .add("Accept", "application/json")
                    .add("Cookie", "login_ticket=$loginTicket; stuid=$uid")
                    .build()
                val j = execute(Request.Builder().url(url).headers(headers).get().build()).json ?: return@runCatching
                LogHelper.i("Mica", "getMultiTokenByLoginTicket retcode=${j.optInt("retcode")}")
                if (j.optInt("retcode") != 0) return@runCatching
                val list = j.optJSONObject("data")?.optJSONArray("list") ?: return@runCatching
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val name = item.optString("name", "")
                    val token = item.optString("token", "")
                    if (token.isBlank()) continue
                    when {
                        name.contains("stoken", true) ->
                            if (token.startsWith("v2_")) map["stoken_v2"] = token else map["stoken"] = token
                        name.contains("ltoken", true) ->
                            if (token.startsWith("v2_")) map["ltoken_v2"] = token else map["ltoken"] = token
                    }
                }
            }.onFailure { LogHelper.w("Mica", "login_ticket 兑换失败: ${it.message}") }
        }

        if (!cookieHasCredentials(joinCookie(map))) {
            return Pair("Cookie 不完整：需要 account_id 与 ltoken/cookie_token", false)
        }

        Prefs.setCookie(context, normalizeCookie(joinCookie(map), includeStoken = true))
        runCatching { enrichTokensByStoken(context) }
            .onFailure { LogHelper.w("Mica", "登录后补全 token 失败: ${it.message}") }

        return try {
            val roles = fetchRoles(context)
            val role = roles.firstOrNull { it.uid.isNotBlank() }
                ?: return Pair("Cookie 有效，但没有读取到星穹铁道角色，请确认账号已绑定", false)
            Prefs.setUid(context, role.uid)
            Prefs.setServer(context, role.region)
            fetchUserInfo(context)?.let {
                Prefs.setNickname(context, it.nickname)
                Prefs.setAvatarUrl(context, it.avatarUrl)
            }
            if (Prefs.nickname(context).isBlank()) Prefs.setNickname(context, role.nickname)
            Prefs.saveCurrentAccount(context)
            // 登录成功后主动拉一次游玩天数并落地缓存，使打卡方案自动切到方案2；
            // 失败不阻断登录，后续 Worker / 手动刷新会再补。
            runCatching { fetchPlayerStats(context, force = true) }
                .onFailure { LogHelper.w("Mica", "登录后拉取游玩天数失败: ${it.message}") }
            Pair("登录成功！UID=${role.uid}", true)
        } catch (e: Exception) {
            val msg = if (e is MicaException) retcodeMessage(e.retcode) else (e.message ?: "账号验证失败")
            Pair("登录失败：$msg", false)
        }
    }

    /**
     * 手动导入完整 Cookie（云崽式粘贴 CK）。
     * @return Pair<提示信息, 是否成功>
     */
    fun importRawCookie(context: Context, rawCookie: String): Pair<String, Boolean> {
        val trimmed = rawCookie.trim()
        if (!trimmed.contains("=")) return Pair("格式错误：请粘贴完整 Cookie 字符串", false)
        val result = completeWebLogin(context, trimmed)
        if (!result.second) Prefs.clearLogin(context)
        return result
    }

    // ==================== 用户 / 角色 ====================

    /** 米游社用户信息（昵称/头像）。 */
    fun fetchUserInfo(context: Context): UserInfo? {
        val uid = extractCookie(context, "account_id")
            ?: extractCookie(context, "ltuid")
            ?: extractCookie(context, "stuid")
            ?: return null
        if (Prefs.cookie(context).isBlank()) return null

        val query = "uid=$uid"
        for (host in BBS_HOSTS) {
            runCatching {
                val url = "$host/user/api/getUserFullInfo?$query"
                val json = httpGet(
                    url,
                    webHeaders(context, query, "", webCookie(context))
                ).json ?: return@runCatching null
                LogHelper.i("Mica", "getUserFullInfo(${host.substringAfter("://").substringBefore("/")}) retcode=${json.optInt("retcode")}")
                if (json.optInt("retcode") != 0) return@runCatching null
                val ui = json.optJSONObject("data")?.optJSONObject("user_info") ?: return@runCatching null
                UserInfo(
                    nickname = ui.optString("nickname", ""),
                    avatarUrl = ui.optString("avatar_url", "")
                )
            }.onSuccess { if (it != null) return it }
        }
        return null
    }

    /** 验证 Cookie + 星铁角色绑定是否可用。 */
    fun validateAccount(context: Context): List<GameRole> {
        requireLogin(context)
        val roles = fetchRoles(context)
        if (roles.isEmpty()) throw MicaException(1008, "Cookie 有效，但没有读取到星穹铁道角色")
        return roles
    }

    /** 角色绑定信息。 */
    fun fetchRoles(context: Context): List<GameRole> {
        if (Prefs.cookie(context).isBlank()) return emptyList()
        val query = "game_biz=hkrpg_cn"
        var last: Throwable? = null

        for (host in TAKUMI_HOSTS) {
            try {
                val url = "$host/binding/api/getUserGameRolesByCookie?$query"
                val resp = httpGet(url, webHeaders(context, query, "", webCookie(context)))
                val json = jsonOrThrow(resp, "角色绑定")
                val code = json.optInt("retcode", -1)
                LogHelper.i("Mica", "getUserGameRolesByCookie(${host.substringAfter("://").substringBefore("/")}) retcode=$code")
                if (code != 0) {
                    last = MicaException(code, json.optString("message").ifBlank { "角色绑定查询失败 (retcode=$code)" })
                    // 登录态问题换域名也没用，直接结束
                    if (code in AUTH_ERROR_CODES) break
                    continue
                }
                val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
                val roles = (0 until list.length()).mapNotNull { i ->
                    val r = list.optJSONObject(i) ?: return@mapNotNull null
                    val gameBiz = r.optString("game_biz", "")
                    if (gameBiz != "hkrpg_cn" && gameBiz != "hkrpg_qd_cn") return@mapNotNull null
                    val region = r.optString("region", "")
                    GameRole(
                        uid = r.optString("game_uid", ""),
                        region = region,
                        regionName = regionName(region),
                        nickname = r.optString("nickname", "")
                    )
                }
                return roles
            } catch (e: Throwable) {
                last = e
            }
        }
        throw (last as? MicaException
            ?: MicaException(-1, last?.message ?: "角色绑定查询失败"))
    }

    // ==================== 逐光捡金战绩 ====================

    /** 战绩缓存有效期：同一账号+玩法 1 小时内复用上次结果；重进/切页不刷新，手动刷新可强制拉取。 */
    private const val CHALLENGE_CACHE_MS = 60 * 60 * 1000L

    private data class ChallengePayload(val data: JSONObject, val fetchedAt: Long)
    private data class ChallengeCacheEntry(val payload: ChallengePayload, val storedAt: Long)

    private val challengeCache =
        java.util.concurrent.ConcurrentHashMap<String, ChallengeCacheEntry>()

    /** 从磁盘缓存读取未过期的战绩（进程重启后仍生效）。 */
    private fun loadPersistedChallenge(context: Context, key: String): ChallengeCacheEntry? {
        val raw = Prefs.challengeCacheRaw(context)
        if (raw.isBlank()) return null
        val obj = runCatching { JSONObject(raw).optJSONObject(key) }.getOrNull() ?: return null
        val ts = obj.optLong("ts", 0L)
        if (ts <= 0 || System.currentTimeMillis() - ts >= CHALLENGE_CACHE_MS) return null
        val data = obj.optJSONObject("data") ?: return null
        return ChallengeCacheEntry(ChallengePayload(data, ts), ts)
    }

    /** 写入磁盘缓存，顺带清理已过期条目，避免无限膨胀。 */
    private fun persistChallenge(context: Context, key: String, payload: ChallengePayload) {
        runCatching {
            val root = runCatching {
                JSONObject(Prefs.challengeCacheRaw(context).ifBlank { "{}" })
            }.getOrDefault(JSONObject())
            val out = JSONObject()
            val now = System.currentTimeMillis()
            root.keys().forEach { k ->
                val o = root.optJSONObject(k) ?: return@forEach
                if (now - o.optLong("ts", 0L) < CHALLENGE_CACHE_MS) out.put(k, o)
            }
            out.put(key, JSONObject().put("ts", payload.fetchedAt).put("data", payload.data))
            Prefs.setChallengeCacheRaw(context, out.toString())
        }
    }

    /** 异相仲裁：challenge_peak；schedule_type=1 为当前期，3 为最近三期。 */
    private fun fetchChallengePeak(context: Context, force: Boolean): PeakInfo {
        val payload = fetchChallengeData(
            context,
            listOf("challenge_peak"),
            "异相仲裁",
            scheduleType = "1",
            needAll = false,
            force = force
        )
        return parsePeak(payload.data, payload.fetchedAt)
    }

    private fun parsePeak(d: JSONObject, fetchedAt: Long): PeakInfo {
        var scheduleName = d.optString("schedule_name", "")
        var periodText = ""
        val records = mutableListOf<PeakRecord>()
        d.optJSONArray("challenge_peak_records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val group = o.optJSONObject("group")
                val groupName = group?.optString("name_mi18n", "").orEmpty()
                if (scheduleName.isBlank()) scheduleName = groupName
                if (periodText.isBlank()) periodText = challengePeriod(group)
                val bossInfo = o.optJSONObject("boss_info")
                val bossRecord = o.optJSONObject("boss_record")
                val mobCount = maxOf(
                    o.optJSONArray("mob_infos")?.length() ?: 0,
                    o.optJSONArray("mob_records")?.length() ?: 0
                )
                records.add(
                    PeakRecord(
                        groupName = groupName,
                        bossName = bossInfo?.optString("name_mi18n", "").orEmpty(),
                        bossStars = o.optInt("boss_stars", bossRecord?.optInt("star_num", 0) ?: 0),
                        mobStars = o.optInt("mob_stars", 0),
                        battleNum = o.optInt("battle_num", 0),
                        buffName = bossRecord?.optJSONObject("buff")
                            ?.optString("name_mi18n", "").orEmpty(),
                        hardMode = bossRecord?.optBoolean("hard_mode", false) ?: false,
                        hardModeName = bossInfo?.optString("hard_mode_name_mi18n", "").orEmpty(),
                        mobCount = mobCount
                    )
                )
            }
        }
        val hasData = records.any { it.bossStars > 0 || it.mobStars > 0 || it.battleNum > 0 }
        return PeakInfo(scheduleName, hasData, records, periodText, fetchedAt)
    }

    // ==================== 逐光捡金（异相仲裁 / 忘却之庭 / 虚构叙事） ====================

    /** 忘却之庭（混沌回忆）。 */
    fun fetchForgottenHall(context: Context, force: Boolean = false): ChallengeModeInfo {
        val payload = fetchChallengeData(context, listOf("challenge"), "忘却之庭", "Current", force = force)
        return parseChallengeMode(payload.data, "忘却之庭").copy(fetchedAt = payload.fetchedAt)
    }

    /** 虚构叙事。 */
    fun fetchPureFiction(context: Context, force: Boolean = false): ChallengeModeInfo {
        val payload = fetchChallengeData(context, listOf("challenge_story"), "虚构叙事", "Current", force = force)
        return parseChallengeMode(payload.data, "虚构叙事").copy(fetchedAt = payload.fetchedAt)
    }

    /** 异相仲裁。 */
    fun fetchAnomalyArbitration(context: Context, force: Boolean = false): ChallengeModeInfo {
        val peak = fetchChallengePeak(context, force)
        val records = peak.records.map { r ->
            ChallengeStageRecord(
                title = r.bossName.ifBlank { r.groupName.ifBlank { "异相仲裁" } },
                subtitle = listOfNotNull(
                    r.hardModeName.takeIf { it.isNotBlank() },
                    r.buffName.takeIf { it.isNotBlank() },
                    "${r.battleNum} 场".takeIf { r.battleNum > 0 },
                    "王棋 ★${r.bossStars}",
                    "小怪 ★${r.mobStars}"
                ).joinToString(" · "),
                stars = r.totalStars,
                maxStars = r.maxStars,
                finished = r.totalStars > 0 || r.battleNum > 0
            )
        }
        return ChallengeModeInfo(
            scheduleName = peak.scheduleName.ifBlank { "异相仲裁" },
            periodText = peak.periodText,
            totalStars = records.sumOf { it.stars },
            maxStars = records.sumOf { it.maxStars },
            records = records,
            hasData = peak.hasData,
            fetchedAt = peak.fetchedAt
        )
    }

    /** 末日幻影：challenge_boss，与其他关卡型玩法共用 all_floor_detail 解析。 */
    fun fetchPeakAsChallenge(context: Context, force: Boolean = false): ChallengeModeInfo {
        val payload = fetchChallengeData(context, listOf("challenge_boss"), "末日幻影", "1", force = force)
        return parseChallengeMode(payload.data, "末日幻影").copy(fetchedAt = payload.fetchedAt)
    }

    /**
     * 战绩接口：App 端请求头优先（更接近官方客户端），网页 Cookie 兜底。
     * 同一账号+玩法在 [CHALLENGE_CACHE_MS] 内直接复用缓存，force=true 时强制刷新。
     * 若收到 10041 风控码，强制重新生成设备指纹后重试一次。
     */
    private fun fetchChallengeData(
        context: Context,
        paths: List<String>,
        label: String,
        scheduleType: String,
        needAll: Boolean = true,
        force: Boolean = false
    ): ChallengePayload {
        val (uid, region) = requireRole(context)
        val query = buildString {
            append("role_id=$uid&server=$region&schedule_type=$scheduleType&lang=zh-cn")
            if (needAll) append("&need_all=true")
        }
        val cacheKey = "$uid|$region|${paths.first()}|$scheduleType|$needAll"
        val now = System.currentTimeMillis()
        challengeCache[cacheKey]?.let { entry ->
            if (!force && now - entry.storedAt < CHALLENGE_CACHE_MS) return entry.payload
        }
        if (!force) {
            loadPersistedChallenge(context, cacheKey)?.let {
                challengeCache[cacheKey] = it
                return it.payload
            }
        }

        fun cacheAndReturn(data: JSONObject): ChallengePayload {
            val payload = ChallengePayload(data, System.currentTimeMillis())
            challengeCache[cacheKey] = ChallengeCacheEntry(payload, payload.fetchedAt)
            persistChallenge(context, cacheKey, payload)
            return payload
        }

        fun failureMessage(code: Int, message: String): String = when {
            code == 10041 && message.isNotBlank() -> "${retcodeMessage(code)}；$message"
            message.isNotBlank() -> message
            else -> retcodeMessage(code)
        }

        /** 执行一轮（网页优先 → App 兜底）请求，返回成功的 data 或抛出最后异常。 */
        fun attemptOnce(): JSONObject {
            // 查询前确保有官方签发的设备指纹；生成失败不阻断查询
            runCatching { ensureDeviceFp(context) }

            var last: Throwable? = null
            var authFailed = false

            // 1) 网页端优先：与官方 App 内置 WebView 的真实请求一致
            for (host in RECORD_HOSTS) {
                if (authFailed) break
                for (path in paths) {
                    try {
                        val resp = httpGet(
                            "$host/game_record/app/hkrpg/api/$path?$query",
                            webHeaders(
                                context, query, "", webCookie(context),
                                version = VERSION_WEB_RECORD,
                                salt = DSAlgorithm.SALT_4X,
                                h5 = true
                            )
                        )
                        val json = jsonOrThrow(resp, label)
                        val code = json.optInt("retcode", -1)
                        val message = json.optString("message", "").trim()
                        LogHelper.i("Mica", "$path(web) http=${resp.httpCode} retcode=$code msg=$message")
                        if (code == 0) {
                            return json.optJSONObject("data")
                                ?: throw MicaException(-1, "$label 返回缺少 data 字段")
                        }
                        last = MicaException(
                            code,
                            "$label 查询失败（HTTP ${resp.httpCode}，retcode=$code）：${failureMessage(code, message)}"
                        )
                        if (code in AUTH_ERROR_CODES) {
                            authFailed = true
                            break
                        }
                    } catch (e: Throwable) {
                        last = e
                    }
                }
            }

            // 2) App 端兜底
            if (!authFailed) {
                for (host in RECORD_HOSTS) {
                    if (authFailed) break
                    for (path in paths) {
                        try {
                            val resp = httpGet(
                                "$host/game_record/app/hkrpg/api/$path?$query",
                                appHeaders(context, query, "", fullCookie(context))
                            )
                            val json = jsonOrThrow(resp, label)
                            val code = json.optInt("retcode", -1)
                            val message = json.optString("message", "").trim()
                            LogHelper.i("Mica", "$path(app) http=${resp.httpCode} retcode=$code msg=$message")
                            if (code == 0) {
                                return json.optJSONObject("data")
                                    ?: throw MicaException(-1, "$label 返回缺少 data 字段")
                            }
                            last = MicaException(
                                code,
                                "$label 查询失败（HTTP ${resp.httpCode}，retcode=$code）：${failureMessage(code, message)}"
                            )
                            if (code in AUTH_ERROR_CODES) {
                                authFailed = true
                                break
                            }
                        } catch (e: Throwable) {
                            last = e
                        }
                    }
                }
            }

            throw (last as? MicaException ?: MicaException(-1, last?.message ?: "$label 查询失败"))
        }

        return cacheAndReturn(attemptOnce())
    }

    // ==================== 全角色列表（Cookie 战绩 detail 接口） ====================

    /** 角色选择页摘要：等级/星魂/稀有度 + 装备光锥。 */
    data class AvatarSummary(
        val id: Int,
        val level: Int,
        val rank: Int,
        val rarity: Int,
        val lcId: Int,
        val lcLevel: Int,
        val lcRank: Int
    )

    private const val AVATAR_LIST_CACHE_MS = 60 * 60 * 1000L
    private val avatarListMemCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<AvatarSummary>>>()

    private fun parseAvatarList(data: JSONObject): List<AvatarSummary> {
        val arr = data.optJSONArray("avatar_list")
            ?: data.optJSONObject("detail_info")?.optJSONArray("avatar_list")
            ?: JSONArray()
        val list = mutableListOf<AvatarSummary>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            if (id <= 0) continue
            // avatar/basic 里装备字段可能叫 equip，也和战绩接口的 equipment 兼容
            val eq = o.optJSONObject("equipment") ?: o.optJSONObject("equip")
            list += AvatarSummary(
                id = id,
                level = o.optInt("level", 1),
                rank = o.optInt("rank", 0),
                rarity = o.optInt("rarity", 0),
                lcId = eq?.optInt("id", 0) ?: 0,
                lcLevel = eq?.optInt("level", 0) ?: 0,
                lcRank = eq?.optInt("rank", 0) ?: 0
            )
        }
        return list
    }

    /**
     * 通过米游社战绩 detail 接口获取账号全部已拥有角色（不需要游戏内展示柜）。
     * 与其他 Cookie 功能一致：1 小时缓存（内存+磁盘），重进/切页不刷新，force=true 才强制请求。
     */
    fun fetchAvatarList(context: Context, force: Boolean = false): List<AvatarSummary> {
        val (uid, region) = requireRole(context)
        val cacheKey = "avatar_list|$uid|$region"
        val now = System.currentTimeMillis()
        if (!force) {
            avatarListMemCache[cacheKey]?.let { (ts, list) ->
                if (now - ts < AVATAR_LIST_CACHE_MS) return list
            }
            loadPersistedChallenge(context, cacheKey)?.let { entry ->
                val list = parseAvatarList(entry.payload.data)
                avatarListMemCache[cacheKey] = entry.storedAt to list
                return list
            }
        }
        val query = "role_id=$uid&server=$region&lang=zh-cn"

        fun attemptOnce(): JSONObject {
            runCatching { ensureDeviceFp(context) }
            var last: Throwable? = null
            var authFailed = false
            // 1) 网页端优先（与官方 H5 战绩页一致）
            for (host in RECORD_HOSTS) {
                if (authFailed) break
                try {
                    val resp = httpGet(
                        "$host/game_record/app/hkrpg/api/avatar/basic?$query",
                        webHeaders(
                            context, query, "", webCookie(context),
                            version = VERSION_WEB_RECORD,
                            salt = DSAlgorithm.SALT_4X,
                            h5 = true
                        )
                    )
                    val json = jsonOrThrow(resp, "全角色列表")
                    val code = json.optInt("retcode", -1)
                    val message = json.optString("message", "")
                    LogHelper.i("Mica", "avatar_list(web) http=${resp.httpCode} retcode=$code msg=$message")
                    if (code == 0) {
                        return json.optJSONObject("data")
                            ?: throw MicaException(-1, "全角色列表返回缺少 data 字段")
                    }
                    last = MicaException(code, "全角色列表查询失败（retcode=$code）：$message")
                    if (code in AUTH_ERROR_CODES) { authFailed = true; break }
                } catch (e: Throwable) { last = e }
            }
            // 2) App 端兜底
            if (!authFailed) {
                for (host in RECORD_HOSTS) {
                    if (authFailed) break
                    try {
                        val resp = httpGet(
                            "$host/game_record/app/hkrpg/api/avatar/basic?$query",
                            appHeaders(context, query, "", fullCookie(context))
                        )
                        val json = jsonOrThrow(resp, "全角色列表")
                        val code = json.optInt("retcode", -1)
                        val message = json.optString("message", "")
                        LogHelper.i("Mica", "avatar_list(app) http=${resp.httpCode} retcode=$code msg=$message")
                        if (code == 0) {
                            return json.optJSONObject("data")
                                ?: throw MicaException(-1, "全角色列表返回缺少 data 字段")
                        }
                        last = MicaException(code, "全角色列表查询失败（retcode=$code）：$message")
                        if (code in AUTH_ERROR_CODES) { authFailed = true; break }
                    } catch (e: Throwable) { last = e }
                }
            }
            throw (last as? MicaException ?: MicaException(-1, last?.message ?: "全角色列表查询失败"))
        }

        val data = attemptOnce()
        val list = parseAvatarList(data)
        avatarListMemCache[cacheKey] = now to list
        persistChallenge(context, cacheKey, ChallengePayload(data, now))
        return list
    }

    /**
     * 单个角色详情（属性/光锥/遗器/行迹），点开才请求，1 小时缓存。
     * 返回 avatar_list 中的该角色节点；字段缺失时由调用方按缺省值降级。
     */
    fun fetchAvatarDetail(context: Context, avatarId: Int, force: Boolean = false): JSONObject {
        val (uid, region) = requireRole(context)
        val cacheKey = "avatar_detail|$uid|$region|$avatarId"
        val now = System.currentTimeMillis()
        if (!force) {
            avatarDetailMemCache[cacheKey]?.let { (ts, node) ->
                if (now - ts < AVATAR_LIST_CACHE_MS) return node
            }
            loadPersistedChallenge(context, cacheKey)?.let { entry ->
                val node = pickAvatarNode(entry.payload.data, avatarId)
                if (node != null) {
                    avatarDetailMemCache[cacheKey] = entry.storedAt to node
                    return node
                }
            }
        }
        val query = "role_id=$uid&server=$region&avatar_id=$avatarId&need_wiki=true&lang=zh-cn"

        fun attemptOnce(): JSONObject {
            runCatching { ensureDeviceFp(context) }
            var last: Throwable? = null
            var authFailed = false
            for (host in RECORD_HOSTS) {
                if (authFailed) break
                try {
                    val resp = httpGet(
                        "$host/game_record/app/hkrpg/api/avatar/info?$query",
                        webHeaders(
                            context, query, "", webCookie(context),
                            version = VERSION_WEB_RECORD,
                            salt = DSAlgorithm.SALT_4X,
                            h5 = true
                        )
                    )
                    val json = jsonOrThrow(resp, "角色详情")
                    val code = json.optInt("retcode", -1)
                    val message = json.optString("message", "")
                    LogHelper.i("Mica", "avatar_info(web) http=${resp.httpCode} retcode=$code msg=$message")
                    if (code == 0) {
                        return json.optJSONObject("data")
                            ?: throw MicaException(-1, "角色详情返回缺少 data 字段")
                    }
                    last = MicaException(code, "角色详情查询失败（retcode=$code）：$message")
                    if (code in AUTH_ERROR_CODES) { authFailed = true; break }
                } catch (e: Throwable) { last = e }
            }
            if (!authFailed) {
                for (host in RECORD_HOSTS) {
                    if (authFailed) break
                    try {
                        val resp = httpGet(
                            "$host/game_record/app/hkrpg/api/avatar/info?$query",
                            appHeaders(context, query, "", fullCookie(context))
                        )
                        val json = jsonOrThrow(resp, "角色详情")
                        val code = json.optInt("retcode", -1)
                        val message = json.optString("message", "")
                        LogHelper.i("Mica", "avatar_info(app) http=${resp.httpCode} retcode=$code msg=$message")
                        if (code == 0) {
                            return json.optJSONObject("data")
                                ?: throw MicaException(-1, "角色详情返回缺少 data 字段")
                        }
                        last = MicaException(code, "角色详情查询失败（retcode=$code）：$message")
                        if (code in AUTH_ERROR_CODES) { authFailed = true; break }
                    } catch (e: Throwable) { last = e }
                }
            }
            throw (last as? MicaException ?: MicaException(-1, last?.message ?: "角色详情查询失败"))
        }

        val data = attemptOnce()
        val node = pickAvatarNode(data, avatarId)
            ?: throw MicaException(-1, "角色详情没有返回该角色数据")
        val dataKeys = data.keys().asSequence().joinToString()
        val nodeKeys = node.keys().asSequence().joinToString()
        val arraySizes = listOf(
            "properties", "relics", "relic_list", "relicList",
            "skills", "skill_list", "skillTreeList", "skill_tree_list"
        ).mapNotNull { key ->
            node.optJSONArray(key)?.let { "$key=${it.length()}" }
        }.joinToString()
        LogHelper.i("Mica", "avatar_info data keys=$dataKeys")
        LogHelper.i("Mica", "avatar_info node keys=$nodeKeys; arrays=$arraySizes")
        avatarDetailMemCache[cacheKey] = now to node
        persistChallenge(context, cacheKey, ChallengePayload(data, now))
        return node
    }

    /**
     * 同一个角色可能同时出现在顶层 avatar_list 和 detail_info 的详情节点中。
     * 合并这些节点，避免只拿到基础信息而丢掉 properties/relics/skills。
     */
    private fun pickAvatarNode(data: JSONObject, avatarId: Int): JSONObject? {
        val candidates = mutableListOf<JSONObject>()
        collectAvatarNodes(data, avatarId, 0, candidates)
        if (candidates.isEmpty()) return null
        val merged = JSONObject(candidates.first().toString())
        for (i in 1 until candidates.size) mergeAvatarNode(merged, candidates[i])
        return merged
    }

    private fun collectAvatarNodes(
        value: Any?,
        avatarId: Int,
        depth: Int,
        out: MutableList<JSONObject>
    ) {
        if (depth > 4) return
        when (value) {
            is JSONObject -> {
                val id = value.optInt("id", value.optInt("avatar_id", 0))
                val characterShape = value.has("element") || value.has("rarity") ||
                    value.has("equip") || value.has("equipment") || value.has("properties") ||
                    value.has("relics") || value.has("relic_list")
                if (id == avatarId && characterShape) out.add(value)
                val keys = value.keys()
                while (keys.hasNext()) {
                    when (val key = keys.next()) {
                        "avatar_list", "detail_info", "avatar", "avatar_info", "detail" ->
                            collectAvatarNodes(value.opt(key), avatarId, depth + 1, out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectAvatarNodes(value.opt(i), avatarId, depth + 1, out)
                }
            }
        }
    }

    private fun mergeAvatarNode(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val incoming = source.opt(key) ?: continue
            val current = target.opt(key)
            val shouldReplace = current == null || current == JSONObject.NULL ||
                (current is JSONArray && incoming is JSONArray && incoming.length() > current.length()) ||
                (current is JSONObject && incoming is JSONObject && incoming.length() > current.length()) ||
                (current is String && current.isBlank() && incoming.toString().isNotBlank())
            if (shouldReplace) target.put(key, incoming)
        }
    }

    private val avatarDetailMemCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()

    /** 角色卡统计（游玩天数等）。 */
    data class PlayerStats(
        val activeDays: Int,
        val avatarNum: Int,
        val achievementNum: Int,
        val chestNum: Int,
        val fetchedAt: Long
    )

    /** 开拓月历：单条收支来源（日常实训、模拟宇宙、忘却之庭等）。 */
    data class LedgerEntry(
        val action: String,
        val num: Int,
        val percent: Int,
        val time: String
    )

    /** 开拓月历：某月星琼 / 星轨通票（含专票）收支与来源明细。 */
    data class LedgerMonth(
        val month: String,
        val optionalMonths: List<String>,
        val hcoin: Int,
        val hcoinPrev: Int,
        val railsPass: Int,
        val railsPassPrev: Int,
        val entries: List<LedgerEntry>
    )

    private const val STATS_CACHE_MS = 60 * 60 * 1000L
    private val statsCache =
        java.util.concurrent.ConcurrentHashMap<String, PlayerStats>()

    /** 切换账号时清空进程内按账号留存的内存缓存，避免上一账号面板/战绩/统计残留。 */
    fun clearAccountCache() {
        challengeCache.clear()
        avatarListMemCache.clear()
        avatarDetailMemCache.clear()
        statsCache.clear()
    }

    /**
     * 读取米游社角色卡统计（含游玩/活跃天数）。
     * 接口 /game_record/app/hkrpg/api/index，字段位于 data.stats.active_days。
     * 网页端（对齐官方 H5）优先、App 端兜底，成功结果缓存 1 小时并持久化。
     */
    fun fetchPlayerStats(context: Context, force: Boolean = false): PlayerStats {
        val (uid, region) = requireRole(context)
        statsCache[uid]?.let {
            if (!force && System.currentTimeMillis() - it.fetchedAt < STATS_CACHE_MS) return it
        }
        // 进程重启后内存缓存丢失：改用持久化时间戳继续按 1 小时节流，重进/切页不重复请求
        if (!force) {
            val cachedAt = Prefs.statsFetchedAt(context, uid)
            val days = Prefs.playDays(context, uid)
            if (cachedAt > 0 && days > 0 &&
                System.currentTimeMillis() - cachedAt < STATS_CACHE_MS
            ) {
                val cached = PlayerStats(
                    activeDays = days,
                    avatarNum = Prefs.avatarNum(context, uid),
                    achievementNum = Prefs.achievementNum(context, uid),
                    chestNum = Prefs.chestNum(context, uid),
                    fetchedAt = cachedAt
                )
                statsCache[uid] = cached
                return cached
            }
        }

        val query = "role_id=$uid&server=$region&lang=zh-cn"
        runCatching { ensureDeviceFp(context) }

        var last: Throwable? = null

        var best: PlayerStats? = null

        fun merge(src: PlayerStats) {
            val cur = best
            best = if (cur == null) src else PlayerStats(
                activeDays = maxOf(cur.activeDays, src.activeDays),
                avatarNum = maxOf(cur.avatarNum, src.avatarNum),
                achievementNum = maxOf(cur.achievementNum, src.achievementNum),
                chestNum = maxOf(cur.chestNum, src.chestNum),
                fetchedAt = System.currentTimeMillis()
            )
        }

        fun parse(json: JSONObject, source: String): PlayerStats? {
            val code = json.optInt("retcode", -1)
            val message = json.optString("message", "").trim()
            if (code != 0) {
                LogHelper.i("Mica", "index($source) retcode=$code msg=$message")
                last = MicaException(
                    code,
                    "游玩天数获取失败（retcode=$code）${if (message.isBlank()) "" else "：$message"}"
                )
                return null
            }
            val data = json.optJSONObject("data") ?: return null
            val stats = data.optJSONObject("stats") ?: return null
            val days = stats.optInt("active_days", -1)
            if (days < 0) return null
            // stats.avatar_num 缺失时，用 avatar_list 长度兜底角色数。
            val listAvatar = data.optJSONArray("avatar_list")?.length() ?: 0
            val result = PlayerStats(
                activeDays = days,
                avatarNum = stats.optInt("avatar_num", 0).let { if (it > 0) it else listAvatar },
                achievementNum = stats.optInt("achievement_num", 0),
                chestNum = stats.optInt("chest_num", 0),
                fetchedAt = System.currentTimeMillis()
            )
            LogHelper.i(
                "Mica",
                "stats($source) days=$days avatar=${result.avatarNum} ach=${result.achievementNum} chest=${result.chestNum} keys=${stats.keys().asSequence().toList()}"
            )
            return result
        }

        // 1) 网页端优先：与官方 App 内置 WebView 的真实请求一致
        for (host in RECORD_HOSTS) {
            try {
                val resp = httpGet(
                    "$host/game_record/app/hkrpg/api/index?$query",
                    webHeaders(
                        context, query, "", webCookie(context),
                        version = VERSION_WEB_RECORD,
                        salt = DSAlgorithm.SALT_4X,
                        h5 = true
                    )
                )
                parse(jsonOrThrow(resp, "游玩天数"), "web")?.let { merge(it) }
            } catch (e: Throwable) {
                last = e
            }
        }

        // 2) App 端兜底：与网页结果按字段取最大值，补齐精简响应里缺失的成就/角色/战利品数
        for (host in RECORD_HOSTS) {
            try {
                val resp = httpGet(
                    "$host/game_record/app/hkrpg/api/index?$query",
                    appHeaders(context, query, "", fullCookie(context))
                )
                parse(jsonOrThrow(resp, "游玩天数"), "app")?.let { merge(it) }
            } catch (e: Throwable) {
                last = e
            }
        }

        best?.let {
            statsCache[uid] = it
            Prefs.setPlayDays(context, uid, it.activeDays)
            Prefs.setAvatarNum(context, uid, it.avatarNum)
            Prefs.setAchievementNum(context, uid, it.achievementNum)
            Prefs.setChestNum(context, uid, it.chestNum)
            Prefs.setStatsFetchedAt(context, uid, it.fetchedAt)
            return it
        }

        throw (last as? MicaException
            ?: MicaException(-1, last?.message ?: "游玩天数获取失败"))
    }

    /**
     * 开拓月历（event/srledger/month_info）：本月星琼、星轨通票（含专票）收支与来源明细。
     * 端点位于 takumi 域（非 record 域），参数 uid/region/month；month 传 YYYYMM，留空查当月。
     * 网页 Cookie 优先、App stoken 兜底，字段对齐 UIGF 文档并做容错。
     */
    fun fetchLedgerMonth(context: Context, month: String = ""): LedgerMonth {
        val (uid, region) = requireRole(context)
        val monthParam = month.trim()
        val query = buildString {
            append("uid=").append(uid)
            append("&region=").append(region)
            if (monthParam.isNotBlank()) append("&month=").append(monthParam)
            append("&lang=zh-cn")
        }
        runCatching { ensureDeviceFp(context) }

        var last: Throwable? = null

        fun parse(json: JSONObject): LedgerMonth? {
            val code = json.optInt("retcode", -1)
            val message = json.optString("message", "").trim()
            if (code != 0) {
                LogHelper.i("Mica", "ledger retcode=$code msg=$message")
                last = MicaException(code, "开拓月历获取失败（retcode=$code）${if (message.isBlank()) "" else "：$message"}")
                return null
            }
            val d = json.optJSONObject("data") ?: return null
            // 历史月份接口回显的 month 可能仍是当月；显式请求月份时以请求的 monthParam 为准，
            // 当月（monthParam 为空）再回退 data_month / month。
            val monthStr = monthParam.ifBlank {
                d.optString("data_month", d.optString("month", ""))
            }.ifBlank { monthParam }
            val optional = mutableListOf<String>()
            d.optJSONArray("optional_month")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { optional += it }
            }

            val md = d.optJSONObject("month_data") ?: d
            val hcoin = md.optInt("current_hcoin", 0)
            val hcoinPrev = md.optInt("last_hcoin", 0)
            val railsPass = md.optInt("current_rails_pass", 0)
            val railsPassPrev = md.optInt("last_rails_pass", 0)

            val entries = mutableListOf<LedgerEntry>()
            md.optJSONArray("group_by")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    entries += LedgerEntry(
                        action = sequenceOf(e.optString("action_name"), e.optString("action"))
                            .firstOrNull { it.isNotBlank() }.orEmpty(),
                        num = e.optInt("num", 0),
                        percent = e.optInt("percent", 0),
                        time = ""
                    )
                }
            }
            return LedgerMonth(
                month = monthStr,
                optionalMonths = optional,
                hcoin = hcoin,
                hcoinPrev = hcoinPrev,
                railsPass = railsPass,
                railsPassPrev = railsPassPrev,
                entries = entries
            )
        }

        // 1) 网页 Cookie 优先（takumi 域）
        for (host in TAKUMI_HOSTS) {
            try {
                val resp = httpGet(
                    "$host/event/srledger/month_info?$query",
                    webHeaders(
                        context, query, "", webCookie(context),
                        version = VERSION_WEB_RECORD,
                        salt = DSAlgorithm.SALT_4X,
                        h5 = true
                    )
                )
                LogHelper.i("Mica", "ledger(web) month=$monthParam body=${resp.raw.take(180)}")
                parse(jsonOrThrow(resp, "开拓月历"))?.let { return it }
            } catch (e: Throwable) {
                last = e
            }
        }

        // 2) App stoken 兜底
        for (host in TAKUMI_HOSTS) {
            try {
                val resp = httpGet(
                    "$host/event/srledger/month_info?$query",
                    appHeaders(context, query, "", fullCookie(context))
                )
                LogHelper.i("Mica", "ledger(app) month=$monthParam body=${resp.raw.take(180)}")
                parse(jsonOrThrow(resp, "开拓月历"))?.let { return it }
            } catch (e: Throwable) {
                last = e
            }
        }

        throw (last as? MicaException
            ?: MicaException(-1, last?.message ?: "开拓月历获取失败"))
    }

    private fun parseChallengeMode(data: JSONObject, label: String): ChallengeModeInfo {
        val group = data.optJSONArray("groups")?.optJSONObject(0)
        val schedule = data.optJSONObject("schedule") ?: group
        val scheduleName = sequenceOf(
            schedule?.optString("name_mi18n").orEmpty(),
            group?.optString("name_mi18n").orEmpty(),
            data.optString("schedule_name")
        ).firstOrNull { it.isNotBlank() } ?: label
        val period = challengePeriod(schedule).ifBlank { challengePeriod(group) }

        // groups 是期数列表；真正的关卡记录位于 all_floor_detail。
        val array = firstArray(
            data,
            "all_floor_detail", "records", "challenge_records", "boss_records",
            "challenge_boss_records", "nodes", "stages"
        )
        val records = array?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseChallengeStage(it, i) }
            }
        }.orEmpty()

        val totalStars = firstInt(data, "star_num", "total_star_num", "boss_stars", "total_stars")
            ?.plus(data.optInt("extra_star_num", 0))
            ?: records.sumOf { it.stars }
        val maxStars = firstInt(data, "max_star_num", "max_stars", "total_max_star_num")
            ?: records.sumOf { it.maxStars }
        val hasData = data.optBoolean("has_data", false) ||
            records.any { it.finished || it.stars > 0 }
        return ChallengeModeInfo(scheduleName, period, totalStars, maxStars, records, hasData)
    }

    private fun parseChallengeStage(o: JSONObject, index: Int): ChallengeStageRecord {
        val group = o.optJSONObject("group")
        val bossInfo = o.optJSONObject("boss_info")
        val title = sequenceOf(
            o.optString("name_mi18n"),
            o.optString("name"),
            group?.optString("name_mi18n").orEmpty(),
            o.optString("desc")
        ).firstOrNull { it.isNotBlank() } ?: "第 ${index + 1} 关"

        val stars = (firstInt(o, "star_num", "stars", "boss_stars", "total_stars") ?: 0) +
            o.optInt("extra_star_num", 0)
        val maxStars = firstInt(o, "max_star_num", "max_stars")
            ?: (3 + if (o.optBoolean("is_tierce", false)) 1 else 0)

        val extras = mutableListOf<String>()
        if (o.optBoolean("hard_mode", false)) extras.add("高难")
        if (o.optBoolean("is_fast", false)) extras.add("快速通关")
        firstInt(o, "battle_num")
            ?.takeIf { it > 0 }
            ?.let { extras.add("$it 场") }
        val bossName = bossInfo?.optString("name_mi18n").orEmpty()
        bossName.takeIf { it.isNotBlank() }?.let { extras.add(it) }
        o.optJSONObject("buff")?.optString("name_mi18n")
            ?.takeIf { it.isNotBlank() }
            ?.let { extras.add(it) }

        // 上下半（或第三路）节点：上阵角色 + 回合数。字段名做容错兼容。
        val nodeLabels = listOf("上半", "下半", "第三路")
        val nodeObjects = listOf(
            o.optJSONObject("node_1"),
            o.optJSONObject("node_2"),
            o.optJSONObject("node_3")
        )
        val nodes = nodeObjects.mapIndexedNotNull { i, node ->
            node ?: return@mapIndexedNotNull null
            parseChallengeNode(node, nodeLabels.getOrElse(i) { "第${i + 1}路" })
        }

        return ChallengeStageRecord(
            title = title,
            subtitle = extras.joinToString(" · "),
            stars = stars,
            maxStars = maxStars,
            finished = stars > 0 ||
                o.optBoolean("is_finished", false) ||
                o.optBoolean("finished", false) ||
                o.optBoolean("is_fast", false) ||
                nodes.isNotEmpty(),
            nodes = nodes,
            bossName = bossName
        )
    }

    private fun parseChallengeNode(node: JSONObject, fallbackLabel: String): ChallengeNode? {
        val arr = node.optJSONArray("avatars")
            ?: node.optJSONArray("avatar_list")
            ?: return null
        val avatars = mutableListOf<ChallengeAvatar>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            // 部分封装把角色字段放在 avatar 子对象内，做一层兜底。
            val src = a.optJSONObject("avatar") ?: a
            val id = src.optInt("id", src.optInt("avatar_id", 0))
            if (id <= 0) continue
            avatars += ChallengeAvatar(
                id = id,
                level = src.optInt("level", 1),
                name = sequenceOf(src.optString("name"), src.optString("name_mi18n"))
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                rarity = src.optInt("rarity", 0)
            )
        }
        val roundNum = firstInt(node, "round_num", "battle_num") ?: 0
        if (avatars.isEmpty() && roundNum <= 0) return null
        val label = node.optString("name").ifBlank { fallbackLabel }
        return ChallengeNode(label, roundNum, avatars)
    }

    private fun challengePeriod(schedule: JSONObject?): String {
        if (schedule == null) return ""

        fun fmt(o: JSONObject?): String {
            if (o == null) return ""
            val y = o.optInt("year", 0)
            val m = o.optInt("month", 0)
            val d = o.optInt("day", 0)
            return if (y > 0) String.format(Locale.US, "%04d.%02d.%02d", y, m, d) else ""
        }

        fun raw(key: String): String =
            schedule.optString(key)
                .takeIf { it.length >= 10 && it[4] == '-' }
                ?.take(10)
                ?.replace('-', '.')
                .orEmpty()

        val begin = fmt(schedule.optJSONObject("begin_time")).ifBlank { raw("begin_time") }
        val end = fmt(schedule.optJSONObject("end_time")).ifBlank { raw("end_time") }
        return when {
            begin.isNotBlank() && end.isNotBlank() -> "$begin-$end"
            begin.isNotBlank() -> "$begin 起"
            end.isNotBlank() -> "至 $end"
            else -> ""
        }
    }

    private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key ->
            o.optJSONArray(key)?.takeIf { it.length() > 0 }
        }

    private fun firstInt(o: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (o.has(key) && !o.isNull(key)) return o.optInt(key, 0)
        }
        return null
    }

    // ==================== 每日签到 ====================

    /**
     * 签到状态缓存：
     * - 已签到（signed=true 且今天 success）：全天锁死，直到次日自动失效
     * - 未签到（signed=false 或今天未 success）：1 小时节流，零点后自动失效
     * - force=true（手动刷新/签到后）：绕过缓存直接请求
     */
    private const val SIGN_CACHE_MS = 60 * 60 * 1000L

    private fun loadSignCache(context: Context, uid: String): SignInfo? {
        val raw = Prefs.signCacheRaw(context)
        if (raw.isBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.optString("uid") != uid) return null
        val cachedDay = o.optString("day", "")
        val today = Prefs.todayStr()
        // 跨零点（或跨 04:00 日切）直接失效
        if (cachedDay.isNotBlank() && cachedDay != today) return null
        val signed = o.optBoolean("signed", false)
        if (signed && Prefs.lastSignSuccess(context) == today) {
            // 今天已成功签到 → 全天锁死，直到零点自然失效
        } else {
            // 未签到：1 小时节流
            if (System.currentTimeMillis() - o.optLong("ts", 0L) >= SIGN_CACHE_MS) return null
        }
        val arr = o.optJSONArray("awards")
        val awards = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            val name = a.optString(0, "")
            if (name.isBlank()) null else SignAward(name, a.optInt(1, 0), a.optString(2, ""))
        }
        return SignInfo(
            signed = signed,
            totalSignDay = o.optInt("total", 0),
            todayReward = o.optString("reward", ""),
            awards = awards
        )
    }

    private fun saveSignCache(context: Context, uid: String, info: SignInfo) {
        if (uid.isBlank()) return
        runCatching {
            val awards = JSONArray()
            info.awards.forEach { awards.put(JSONArray().put(it.name).put(it.count).put(it.icon)) }
            Prefs.setSignCacheRaw(
                context,
                JSONObject()
                    .put("uid", uid)
                    .put("day", Prefs.todayStr())
                    .put("ts", System.currentTimeMillis())
                    .put("signed", info.signed)
                    .put("total", info.totalSignDay)
                    .put("reward", info.todayReward)
                    .put("awards", awards)
                    .toString()
            )
        }
    }

    fun fetchSignInfo(context: Context, actId: String = ACT_ID_HKRPG, force: Boolean = false): SignInfo {
        val (uid, region) = requireRole(context)
        // 1 小时内复用缓存：重进/切页不刷新，手动刷新（force=true）才请求接口
        if (!force) loadSignCache(context, uid)?.let { return it }
        val query = "act_id=$actId&uid=$uid&region=$region"
        val ds = DSAlgorithm.ds1(DSAlgorithm.SALT_CN_SIGNIN)
        val headers = webHeaders(
            context, query, "", webCookie(context),
            version = VERSION_WEB_SIGN,
            salt = DSAlgorithm.SALT_CN_SIGNIN,
            ds = ds,
            origin = ORIGIN_ACT,
            referer = REFERER_ACT,
            signGame = "hkrpg"
        )

        // 1) luna info（is_sign / total_sign_day）
        var isSign = false
        var totalDay = 0
        var lunaOk = false
        var lastError: MicaException? = null
        try {
            val json = jsonOrThrow(httpGet("$LUNA_BASE/info?$query", headers), "签到信息")
            val code = json.optInt("retcode", -1)
            LogHelper.i("Mica", "luna/info retcode=$code msg=${json.optString("message")}")
            if (code != 0) {
                lastError = MicaException(code, json.optString("message").ifBlank { "签到信息获取失败 (retcode=$code)" })
            } else {
                val d = json.optJSONObject("data")
                if (d != null) {
                    isSign = d.optInt("is_sign", 0) == 1 || d.optBoolean("is_sign", false)
                    totalDay = d.optInt("total_sign_day", 0)
                    lunaOk = true
                }
            }
        } catch (e: MicaException) {
            lastError = e
        }

        // 2) luna home（奖励列表）
        var awards: List<SignAward> = emptyList()
        try {
            val homeQuery = "act_id=$actId"
            val homeHeaders = webHeaders(
                context, homeQuery, "", webCookie(context),
                version = VERSION_WEB_SIGN,
                salt = DSAlgorithm.SALT_CN_SIGNIN,
                ds = DSAlgorithm.ds1(DSAlgorithm.SALT_CN_SIGNIN),
                origin = ORIGIN_ACT,
                referer = REFERER_ACT,
                signGame = "hkrpg"
            )
            val json = httpGet("$LUNA_BASE/home?$homeQuery", homeHeaders).json
            if (json != null && json.optInt("retcode", -1) == 0) {
                val d = json.optJSONObject("data")
                if (d != null) {
                    awards = parseAwards(d.optJSONArray("awards"))
                    if (!lunaOk) {
                        isSign = d.optInt("is_sign", 0) == 1 || d.optBoolean("is_sign", false)
                        totalDay = d.optInt("total_sign_day", 0)
                    }
                }
            }
        } catch (e: MicaException) {
            if (lastError == null) lastError = e
        }

        // 3) 旧版签到接口兜底（某些账号活动被迁移时仍然可用）
        if (awards.isEmpty()) {
            try {
                val legacyQuery = "act_id=$actId&region=$region&uid=$uid"
                val json = jsonOrThrow(
                    httpGet(
                        "$LEGACY_SIGN_BASE/home?$legacyQuery",
                        webHeaders(
                            context, legacyQuery, "", webCookie(context),
                            version = VERSION_WEB_SIGN,
                            salt = DSAlgorithm.SALT_CN_SIGNIN,
                            ds = DSAlgorithm.ds1(DSAlgorithm.SALT_CN_SIGNIN),
                            origin = ORIGIN_ACT,
                            referer = REFERER_ACT,
                            signGame = "hkrpg"
                        )
                    ),
                    "签到奖励列表"
                )
                if (json.optInt("retcode", -1) == 0) {
                    val d = json.optJSONObject("data")
                    if (d != null) {
                        awards = parseAwards(d.optJSONArray("awards"))
                        if (!lunaOk) {
                            isSign = d.optInt("is_sign", 0) == 1 || d.optBoolean("is_sign", false)
                            totalDay = d.optInt("total_sign_day", 0)
                        }
                    }
                }
            } catch (e: MicaException) {
                if (lastError == null) lastError = e
            }
        }

        if (!lunaOk && awards.isEmpty()) {
            throw (lastError ?: MicaException(10001, "签到信息获取失败，请检查 Cookie 或稍后重试"))
        }

        val todayIdx = if (isSign) totalDay - 1 else totalDay
        val todayReward = if (awards.isEmpty()) "" else {
            val idx = todayIdx.coerceIn(0, awards.size - 1)
            "${awards[idx].name} x${awards[idx].count}"
        }
        val info = SignInfo(signed = isSign, totalSignDay = totalDay, todayReward = todayReward, awards = awards)
        saveSignCache(context, uid, info)
        return info
    }

    private fun parseAwards(arr: JSONArray?): List<SignAward> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name", "")
            if (name.isBlank()) return@mapNotNull null
            val rawIcon = a.optString("icon", "")
                .ifBlank { a.optString("image", "") }
                .ifBlank { a.optString("img", "") }
                .ifBlank { a.optString("icon_url", "") }
            val icon = when {
                rawIcon.startsWith("//") -> "https:$rawIcon"
                rawIcon.startsWith("http") -> rawIcon
                else -> rawIcon
            }
            SignAward(name, a.optInt("cnt", 0), icon)
        }
    }

    /** 执行签到。多套签名/接口组合依次尝试，成功后立即返回。 */
    fun doSign(context: Context, actId: String = ACT_ID_HKRPG): SignResult {
        val (uid, region) = requireRole(context)
        // 签到会改变签到状态：先失效 1 小时缓存，后续读取重新拉取
        Prefs.setSignCacheRaw(context, "")
        val query = "act_id=$actId&uid=$uid&region=$region"
        val body = JSONObject().apply {
            put("act_id", actId)
            put("region", region)
            put("uid", uid)
        }.toString()

        var lastMessage = "签到失败，请稍后重试"
        var authFailed = false

        fun attempt(block: () -> SignResult): SignResult? {
            return try {
                val r = block()
                if (r.success) return r
                lastMessage = r.message
                if (authFailed) return null
                null
            } catch (e: MicaException) {
                LogHelper.w("Mica", "签到尝试失败 retcode=${e.retcode} msg=${e.message}")
                lastMessage = e.message ?: lastMessage
                if (e.retcode in AUTH_ERROR_CODES) authFailed = true
                null
            } catch (e: Throwable) {
                LogHelper.w("Mica", "签到尝试异常: ${e.message}")
                lastMessage = e.message ?: lastMessage
                null
            }
        }

        // 方案 1：luna + DS1(签到专用 salt) + 空 body（genshin.py 现行实现）
        attempt {
            val headers = webHeaders(
                context, query, "", webCookie(context),
                version = VERSION_WEB_SIGN,
                salt = DSAlgorithm.SALT_CN_SIGNIN,
                ds = DSAlgorithm.ds1(DSAlgorithm.SALT_CN_SIGNIN),
                origin = ORIGIN_ACT, referer = REFERER_ACT, signGame = "hkrpg"
            )
            parseSignResponse(httpPost("$LUNA_BASE/sign?$query", headers, null))
        }?.let { return it }

        // 方案 2：luna + 网页 DS2(4X) + JSON body
        if (!authFailed) attempt {
            val headers = webHeaders(
                context, "", body, webCookie(context),
                version = VERSION_WEB_SIGN_LEGACY, salt = DSAlgorithm.SALT_4X,
                origin = ORIGIN_ACT, referer = REFERER_ACT, signGame = "hkrpg"
            )
            parseSignResponse(httpPost("$LUNA_BASE/sign", headers, body))
        }?.let { return it }

        // 方案 3：luna + App DS2(6X) + JSON body
        if (!authFailed) attempt {
            val headers = appHeaders(context, "", body, fullCookie(context))
            parseSignResponse(httpPost("$LUNA_BASE/sign", headers, body))
        }?.let { return it }

        // 方案 4：旧版 bbs_sign_reward（仍然兜底）
        if (!authFailed) attempt {
            val headers = webHeaders(
                context, "", body, webCookie(context),
                version = VERSION_WEB_SIGN_LEGACY, salt = DSAlgorithm.SALT_4X,
                origin = ORIGIN_ACT, referer = REFERER_ACT, signGame = "hkrpg"
            )
            parseSignResponse(httpPost("$LEGACY_SIGN_BASE/sign", headers, body))
        }?.let { return it }

        return SignResult(false, lastMessage)
    }

    /** 签到失败时自动再重试一次（网络抖动/风控瞬时失败很常见）。 */
    fun doSignWithRetry(context: Context, actId: String = ACT_ID_HKRPG): SignResult {
        val first = doSign(context, actId)
        if (first.success) return first
        LogHelper.w("Mica", "签到首次失败，1.2 秒后重试一次：${first.message}")
        runCatching { Thread.sleep(1200) }
        return doSign(context, actId)
    }

    private fun parseSignResponse(resp: ApiResponse): SignResult {
        if (resp.raw.trimStart().startsWith("<")) {
            return SignResult(false, "签到接口返回网页（疑似风控），请更换网络后重试")
        }
        val json = resp.json ?: return SignResult(false, "签到接口返回格式错误")
        val code = json.optInt("retcode", -1)
        val msg = json.optString("message", "")
        LogHelper.i("Mica", "sign retcode=$code msg=$msg")
        return when {
            code == 0 -> SignResult(true, "签到成功！")
            code == -5003 || msg.contains("已经") || msg.contains("已签") || msg.contains("上限") ->
                SignResult(true, "今天已经签到过了~")
            else -> throw MicaException(code, msg.ifBlank { "签到失败 (retcode=$code)" })
        }
    }

    /** 从 Cookie 中提取指定键值。 */
    fun extractCookie(context: Context, key: String): String? {
        val cookie = Prefs.cookie(context)
        return cookie.split(';', '\n').map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    // ==================== 跃迁记录（真实端点取自游戏 H5 bundle） ====================

    /** 真实跃迁记录 API（GET，authkey 鉴权，不带 Cookie/DS）。 */
    private const val GACHA_API_URL =
        "${ApiHosts.PUBLIC_OPERATION_HKRPG}/common/hkrpg_gacha_record/api/getGachaLog"

    /** 来源 H5 页面地址，仅作 Referer 请求头，绝不是请求目标 host。 */
    private const val GACHA_REFERER =
        "${ApiHosts.WEBSTATIC}/hkrpg/event/e20211215gacha-v2/index.html"
    private const val GACHA_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * 跃迁链接解析结果。
     * @param rawQuery 原始（已百分号编码）query 串，请求时原样透传，避免 authkey 被二次编码损坏；
     * @param authkey  解码后的 authkey，仅用于判空与日志展示。
     */
    data class GachaLinkParams(
        val authkey: String,
        val uid: String?,
        val region: String?,
        val rawQuery: String
    )

    /** 去掉输入尾部 `#/...` 哈希片段，只保留 `?` 后的原始编码 query 串（不解码、不重新拼接）。 */
    private fun extractRawQuery(raw: String): String {
        val noHash = raw.trim().substringBefore('#')
        return if (noHash.contains("?")) noHash.substringAfter("?") else noHash
    }

    /** 从原始编码 query 串中按名取值（返回值仍为编码态）。 */
    private fun queryValue(rawQuery: String, key: String): String? {
        rawQuery.split("&").forEach { seg ->
            val parts = seg.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == key) return parts[1]
        }
        return null
    }

    /**
     * 解析游戏内「跃迁记录」H5 分享链接。
     * 关键：只提取 query 参数，丢弃链接里 webstatic 的 host/path（那是前端页面，不是 API）。
     * 返回 null 表示缺少 authkey（链接无效）。
     */
    fun linkParamsForGacha(raw: String): GachaLinkParams? {
        val rawQuery = extractRawQuery(raw)
        val authkeyEncoded = queryValue(rawQuery, "authkey")?.takeIf { it.isNotBlank() } ?: return null
        val authkey = decodeUrl(authkeyEncoded)
        val uid = decodeUrl(queryValue(rawQuery, "uid").orEmpty()).takeIf { it.isNotBlank() }
        val region = queryValue(rawQuery, "region")
        return GachaLinkParams(authkey, uid, region, rawQuery)
    }

    /** URL 解码（仅用于判空/展示，不用于拼装请求 URL）。 */
    private fun decodeUrl(s: String): String {
        return try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
    }

    /**
     * 分页拉取跃迁记录（固定真实端点 GET，游标 end_id 翻页）。
     * 原始链接 query 原样透传，仅清洗并重写 page/end_id/gacha_type/size。
     */
    fun fetchGachaPage(context: Context, params: GachaLinkParams, gachaType: Int, page: Int, endId: String = ""): GachaPage {
        // 自动换取 authkey 时 rawQuery 为空，需要自行拼出基础参数（authkey 必须百分号编码）
        val baseQuery = if (params.rawQuery.isNotBlank()) {
            params.rawQuery
        } else {
            buildString {
                append("authkey=").append(URLEncoder.encode(params.authkey, "UTF-8"))
                append("&game_biz=hkrpg_cn&lang=zh-cn")
                params.uid?.let { append("&uid=").append(it) }
                params.region?.let { append("&region=").append(it) }
            }
        }
        val reserved = baseQuery.split("&").filter { seg ->
            val k = seg.substringBefore("=")
            k !in listOf("page", "end_id", "gacha_type", "size")
        }.joinToString("&")
        val query = buildString {
            if (reserved.isNotBlank()) append(reserved).append("&")
            append("size=20&page=$page&end_id=${endId.ifBlank { "0" }}&gacha_type=$gachaType")
        }
        val url = "$GACHA_API_URL?$query"
        return runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", GACHA_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", GACHA_REFERER)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                LogHelper.i("Mica", "getGachaLog type=$gachaType page=$page end_id=$endId HTTP ${resp.code} len=${text.length}")
                if (!text.trimStart().startsWith("{")) {
                    throw MicaException(-1, "抽卡接口返回非标准响应，请重新复制游戏内最新跃迁链接")
                }
                val json = try { JSONObject(text) } catch (e: Exception) {
                    throw MicaException(-1, "抽卡接口返回格式错误")
                }
                val retcode = json.optInt("retcode", -1)
                if (retcode != 0) {
                    throw MicaException(retcode, json.optString("message").ifBlank { "拉取抽卡记录失败 (retcode=$retcode)" })
                }
                val d = json.optJSONObject("data")
                val v2 = d?.optJSONArray("list_v2")
                val list = d?.optJSONArray("list")
                val arr = if (v2 != null && v2.length() > 0) v2 else (list ?: v2)
                val records = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                    val r = arr.optJSONObject(i) ?: return@mapNotNull null
                    GachaRecord(
                        id = r.optString("id", ""),
                        gachaType = r.optInt("gacha_type", gachaType),
                        gachaId = r.optString("gacha_id", ""),
                        itemType = r.optString("item_type", ""),
                        name = r.optString("name", ""),
                        rank = r.optInt("rank_type", 1),
                        time = r.optString("time", "")
                    )
                }
                val lastId = records.lastOrNull()?.id ?: endId
                GachaPage(records, hasMore = records.size >= 20, endId = lastId)
            }
        }.getOrElse { e ->
            LogHelper.e("Mica", "getGachaLog失败", e)
            if (e is MicaException) throw e
            throw MicaException(-1, e.message ?: "抽卡请求网络失败，请检查网络后重试")
        }
    }

    /** 拉取指定卡池全量记录（end_id 游标翻页；按记录 id 去重；每页节流防风控）。 */
    suspend fun fetchAllGacha(context: Context, params: GachaLinkParams, gachaType: Int, seenIds: MutableSet<String>): List<GachaRecord> {
        val all = mutableListOf<GachaRecord>()
        var page = 1
        var endId = ""
        while (true) {
            val p = fetchGachaPage(context, params, gachaType, page, endId)
            for (r in p.records) {
                if (r.id.isNotBlank() && seenIds.add(r.id)) all.add(r)
            }
            if (!p.hasMore || p.records.isEmpty()) break
            endId = p.endId
            page++
            if (page > 50) break
            kotlinx.coroutines.delay(450)
        }
        return all
    }

    /** 跃迁接口错误码 → 用户可读提示（对齐 HoYoGet/HoYo.Gacha）。 */
    fun gachaRetcodeMessage(retcode: Int): String = when (retcode) {
        0 -> "成功"
        -100, -101 -> "跃迁链接已失效，请回到游戏重新打开「跃迁记录」并复制最新链接"
        -110 -> "请求过于频繁被限流，请等待几分钟后再试"
        -111 -> "访问被风控拦截，请更换网络或稍后再试"
        -108 -> "暂不支持该语言区域"
        -502 -> "服务器繁忙，请稍后重试"
        else -> "抽卡接口异常 (retcode=$retcode)，请重新复制游戏内最新跃迁链接"
    }

    // ==================== 诊断 / 错误码 ====================

    data class ApiDiagnostic(
        val cookiePresent: Boolean,
        val lTokenPresent: Boolean,
        val uidPresent: Boolean,
        val serverPresent: Boolean,
        val roleCount: Int,
        val recordApiOk: Boolean,
        val message: String
    )

    /** 一键诊断登录、角色绑定和签到接口。不会把 Cookie 写入日志或返回值。 */
    fun diagnose(context: Context): ApiDiagnostic {
        val cookie = Prefs.cookie(context)
        val ltoken = extractCookie(context, "ltoken_v2") ?: extractCookie(context, "ltoken")
            ?: extractCookie(context, "cookie_token_v2") ?: extractCookie(context, "cookie_token")
        if (cookie.isBlank()) {
            return ApiDiagnostic(false, false, false, false, 0, false, "Cookie 为空")
        }
        return runCatching {
            val roles = fetchRoles(context)
            val role = roles.firstOrNull { it.uid.isNotBlank() }
                ?: return@runCatching ApiDiagnostic(true, !ltoken.isNullOrBlank(), false, false, 0, false, "Cookie 有效但没有星铁角色")
            val oldUid = Prefs.uid(context)
            val oldServer = Prefs.server(context)
            Prefs.setUid(context, role.uid)
            Prefs.setServer(context, role.region)
            val ok = runCatching { fetchSignInfo(context, force = true); true }.getOrDefault(false)
            Prefs.setUid(context, oldUid)
            Prefs.setServer(context, oldServer)
            ApiDiagnostic(
                true, !ltoken.isNullOrBlank(), true, role.region.isNotBlank(), roles.size, ok,
                if (ok) "账号、角色绑定、签到接口均正常" else "角色绑定正常，但签到接口请求失败"
            )
        }.getOrElse {
            ApiDiagnostic(
                true, !ltoken.isNullOrBlank(), false, false, 0, false,
                if (it is MicaException) retcodeMessage(it.retcode) else (it.message ?: "请求失败")
            )
        }
    }

    // 仅真正的登录态失效才终止后续方案；DS/请求头错误仍值得换一套参数重试
    private val AUTH_ERROR_CODES = setOf(-100, -101, 10001)

    /** retcode 转可读提示 */
    fun retcodeMessage(retcode: Int): String = when (retcode) {
        10035 -> "米游社风控限制（10035），请稍后再试或更换网络"
        -3503 -> "当前设备/网络触发风控（-3503），请更换网络或稍后重试"
        -5000001 -> "DS 签名校验失败（-5000001），请更新到最新版本"
        -5003 -> "今天已经签到过了"
        -100, -101 -> "Cookie 已失效，请重新登录"
        -10001 -> "请求头校验失败（-10001）"
        10001 -> "Cookie 未被接口接受，请重新登录"
        1008 -> "星铁 UID/服务器与 Cookie 不匹配"
        10041, 10045 -> "米游社接口触发风控或安全验证（$retcode），请稍后重试；这不等于 Cookie 已失效"
        1034 -> "请求触发验证码，请稍后再试"
        -199 -> "请求被拦截（-199），请确认设备信息"
        -1 -> "米游社接口错误（-1）"
        429 -> "刷新太快，请稍后再试"
        else -> "米游社接口错误 (retcode=$retcode)"
    }

    // ==================== 开拓力（体力）查询 ====================

    /** 开拓力数据 */
    data class StaminaInfo(
        val current: Int,
        val max: Int,
        val recoverTimeSec: Long,
        val expeditions: List<ExpeditionInfo>,
        val reserve: Int = 0,
        val reserveMax: Int = 2400,
        val trainScore: Int = 0,
        val trainMax: Int = 500,
        val cocoonCnt: Int = 0,
        val cocoonLimit: Int = 3,
        val rogueScore: Int = 0,
        val rogueMax: Int = 18000
    )

    data class ExpeditionInfo(
        val remainingTime: Long,
        val status: String,
        val name: String = ""
    )

    /**
     * 查询星穹铁道开拓力。
     * 优先网页 Cookie（按 UIGF 配对，不带 stoken），失败再尝试 App stoken。
     */
    fun fetchStamina(context: Context): StaminaInfo {
        val (uid, region) = requireRole(context)

        val now = System.currentTimeMillis()
        val elapsed = now - lastStaminaQueryTime
        if (elapsed < 30_000 && lastStaminaQueryTime > 0) {
            val remain = (30_000 - elapsed) / 1000
            throw MicaException(429, "刷新太快，请等${remain}秒后再试")
        }

        val query = "role_id=$uid&server=$region"
        var lastErr: Throwable? = null

        // 链路 1：网页 Cookie + client_type=5 + DS2(4X)
        for (host in RECORD_HOSTS) {
            if (webCookie(context).isBlank()) break
            runCatching { tryNoteWeb(context, host, query, webCookie(context)) }
                .onSuccess { return it }
                .onFailure { lastErr = it }
        }

        // 链路 2：App stoken + client_type=2 + DS2(6X)
        runCatching { tryNoteAppStoken(context, query) }
            .onSuccess { return it }
            .onFailure { lastErr = it }

        throw (lastErr as? MicaException
            ?: MicaException(-1, lastErr?.message ?: "开拓力查询失败"))
    }

    private fun tryNoteWeb(context: Context, host: String, query: String, cookie: String): StaminaInfo {
        if (cookie.isBlank()) throw MicaException(10041, "Cookie 为空")
        val url = "$host/game_record/app/hkrpg/api/note?$query"
        val headers = webHeaders(
            context, query, "", cookie,
            version = VERSION_WEB_RECORD,
            salt = DSAlgorithm.SALT_4X,
            h5 = true
        )
        val resp = httpGet(url, headers)
        LogHelper.i("Mica", "note(web) host=${host.substringAfter("://").substringBefore("/")} body=${resp.raw.take(180)}")
        return parseNoteResponse(resp)
    }

    private fun tryNoteAppStoken(context: Context, query: String): StaminaInfo {
        val stokenV2 = extractCookie(context, "stoken_v2")
        val stokenV1 = extractCookie(context, "stoken")
        if (stokenV2.isNullOrBlank() && stokenV1.isNullOrBlank()) {
            throw MicaException(10041, "无可用 stoken")
        }
        val mid = extractCookie(context, "mid") ?: extractCookie(context, "account_mid_v2") ?: ""
        val stuid = extractCookie(context, "account_id") ?: extractCookie(context, "stuid") ?: ""
        val cookie = buildString {
            if (!stokenV2.isNullOrBlank()) append("stoken_v2=$stokenV2") else append("stoken=$stokenV1")
            if (stuid.isNotBlank()) append("; stuid=$stuid")
            if (mid.isNotBlank()) append("; mid=$mid")
        }
        val url = "${ApiHosts.TAKUMI_RECORD}/game_record/app/hkrpg/api/note?$query"
        val headers = appHeaders(context, query, "", cookie)
        val resp = httpGet(url, headers)
        LogHelper.i("Mica", "note(app) body=${resp.raw.take(180)}")
        return parseNoteResponse(resp)
    }

    private fun parseNoteResponse(resp: ApiResponse): StaminaInfo {
        if (resp.raw.trimStart().startsWith("<")) throw MicaException(-1, "体力接口被风控")
        val json = resp.json ?: throw MicaException(-1, "体力接口返回格式错误")
        val retcode = json.optInt("retcode", -1)
        if (retcode != 0) {
            throw MicaException(retcode, json.optString("message").ifBlank { "note 失败 retcode=$retcode" })
        }
        return parseStaminaData(json)
    }

    private fun parseStaminaData(json: JSONObject): StaminaInfo {
        val d = json.optJSONObject("data") ?: throw MicaException(-1, "体力数据为空")
        val expeditionsArr = d.optJSONArray("expeditions")
        val expeditions = if (expeditionsArr == null) emptyList() else (0 until expeditionsArr.length()).mapNotNull { i ->
            val e = expeditionsArr.optJSONObject(i) ?: return@mapNotNull null
            ExpeditionInfo(
                remainingTime = e.optLong("remaining_time", 0),
                status = e.optString("status", ""),
                name = sequenceOf(e.optString("name"), e.optString("name_mi18n"))
                    .firstOrNull { it.isNotBlank() }.orEmpty()
            )
        }
        lastStaminaQueryTime = System.currentTimeMillis()
        return StaminaInfo(
            current = d.optInt("current_stamina", 0),
            max = d.optInt("max_stamina", 240),
            recoverTimeSec = d.optLong("stamina_recover_time", 0),
            expeditions = expeditions,
            reserve = d.optInt("current_reserve_stamina", d.optInt("reserve_stamina", 0)),
            reserveMax = d.optInt("max_reserve_stamina", 2400),
            trainScore = d.optInt("current_train_score", 0),
            trainMax = d.optInt("max_train_score", 500),
            cocoonCnt = d.optInt("weekly_cocoon_cnt", 0),
            cocoonLimit = d.optInt("weekly_cocoon_limit", 3),
            rogueScore = d.optInt("current_rogue_score", 0),
            rogueMax = d.optInt("max_rogue_score", 18000)
        )
    }
}
