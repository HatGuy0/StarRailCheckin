package com.mh.starrailcheckin.network

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 米游社 DS 签名算法（依据 UIGF mihoyo-api-collection / authentication）。
 *
 * DS2（client_type=5 网页端常用）：
 *   md5("salt={salt}&t={t}&r={r}&b={body}&q={query}")
 *   - r 为 100001~200000 的随机整数（100000 会与服务端约定冲突，直接跳过）
 *   - body 为 JSON（键按字典序）；query 为 URL 参数按键排序
 *
 * DS1（部分接口，如签到 luna 仍在使用）：
 *   md5("salt={salt}&t={t}&r={r}")
 *   - r 为 6 位大小写字母+数字
 */
object DSAlgorithm {
    /** 网页端稳定 salt（client_type=5） */
    const val SALT_4X = "xV8v4Qu54lUKrEYFZkJhB8cuOh9Asafs"

    /** 安卓 App 稳定 salt（client_type=2） */
    const val SALT_6X = "t0qEgfub6cvueAPgR5m9aQWWVciEer7v"

    /** 签到福利专用 salt（DS1，client_type=5，x-rpc-app_version=2.70.1） */
    const val SALT_CN_SIGNIN = "LyD1rXqMv2GJhnwdvCBjFOKGiKuLY3aO"

    /** 网页签到旧 salt（DS1，x-rpc-app_version=2.117.0，作为兜底） */
    const val SALT_WEB_SIGN = "4751cb5557bfad5e249e58462c39fb0a"

    /** 通行证 DS（DS2，r 为 6 位字母） */
    const val SALT_CN_PASSPORT = "JwYDpKvLj6MrMqqYU6jTKF17KNO2PXoS"

    private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val LETTERS_DIGITS = LETTERS + "0123456789"

    /** 兼容旧调用：默认生成 DS2。 */
    fun getDs(query: String = "", body: String = "", salt: String = SALT_4X): String =
        ds2(salt, query, body)

    /** DS2：t,r,md5(salt&t&r&b&q) */
    fun ds2(salt: String, query: String = "", body: String = ""): String {
        val t = System.currentTimeMillis() / 1000
        var r = Random.nextInt(100001, 200001)
        if (r == 100000) r = 642367
        val b = normalizeBody(body)
        val q = normalizeQuery(query)
        val main = "salt=$salt&t=$t&r=$r&b=$b&q=$q"
        return "$t,$r,${md5(main)}"
    }

    /** DS1：t,r,md5(salt&t&r)，r 为 6 位字母数字 */
    fun ds1(salt: String): String {
        val t = System.currentTimeMillis() / 1000
        val r = buildString { repeat(6) { append(LETTERS_DIGITS[Random.nextInt(LETTERS_DIGITS.length)]) } }
        return "$t,$r,${md5("salt=$salt&t=$t&r=$r")}"
    }

    /** 通行证 DS2：r 为 6 位字母 */
    fun passportDs(body: String): String {
        val t = System.currentTimeMillis() / 1000
        val r = buildString { repeat(6) { append(LETTERS[Random.nextInt(LETTERS.length)]) } }
        val b = normalizeBody(body)
        return "$t,$r,${md5("salt=$SALT_CN_PASSPORT&t=$t&r=$r&b=$b&q=")}"
    }

    private fun normalizeBody(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> sortObject(JSONObject(trimmed)).toString()
                trimmed.startsWith("[") -> JSONArray(trimmed).toString()
                else -> trimmed
            }
        }.getOrDefault(body)
    }

    /** 递归按 key 字典序重排 JSON 对象，保证与 UIGF 约定一致。 */
    private fun sortObject(obj: JSONObject): JSONObject {
        val out = JSONObject()
        obj.keys().asSequence().toList().sorted().forEach { k ->
            val v = obj.opt(k)
            out.put(
                k,
                when (v) {
                    is JSONObject -> sortObject(v)
                    else -> v
                }
            )
        }
        return out
    }

    /** 对 query 按参数名升序排序（同名参数再按值排序，等价于 Python sorted(items)）。 */
    private fun normalizeQuery(query: String): String {
        if (query.isBlank()) return ""
        return query.trim().trimStart('?').split("&")
            .filter { it.isNotBlank() }
            .map { it.substringBefore('=') to it }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { it.second }
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
