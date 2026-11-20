package com.mh.starrailcheckin

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mh.starrailcheckin.network.MicaApi

/**
 * 网页登录获取 Cookie。
 *
 * 关键点：
 * 1. 使用米游社新版登录页 user.miyoushe.com（旧 user.mihoyo.com 已不完整）；
 * 2. 跨多个域名合并 Cookie，并刷新到 CookieManager；
 * 3. 网页登录往往只下发 login_ticket，需要由 MicaApi 兑换成 ltoken/cookie_token，
 *    否则旧逻辑会一直等待 stoken 而永远无法完成登录；
 * 4. 统一交给 MicaApi.completeWebLogin 完成校验、角色读取和落盘。
 */
class LoginWebViewActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var finished = false

    /** 需要合并 Cookie 的相关域名（通行证 / 社区 / 记录 / 网页静态）。 */
    private val cookieDomains = listOf(
        "https://user.miyoushe.com",
        "https://user.mihoyo.com",
        "https://passport-api.miyoushe.com",
        "https://passport-api.mihoyo.com",
        "https://bbs.miyoushe.com",
        "https://bbs-api.miyoushe.com",
        "https://bbs-api.mihoyo.com",
        "https://api-takumi.miyoushe.com",
        "https://api-takumi.mihoyo.com",
        "https://api-takumi-record.mihoyo.com",
        "https://www.miyoushe.com",
        "https://webstatic.mihoyo.com",
        "https://act.mihoyo.com",
        "https://account.mihoyo.com",
        "https://webapi.account.mihoyo.com"
    )

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (finished) return
            tryLogin()
            if (!finished) handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().setAcceptCookie(true)
        // 清掉 WebView 里可能残留的失效 Cookie，避免刚打开就被旧凭证触发登录校验
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    tryLogin()
                }
            }
            loadUrl("https://user.miyoushe.com/#/login")
        }
        webView = wv
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        setContentView(wv)

        handler.postDelayed(pollRunnable, 1500)
    }

    /** 跨域合并 Cookie，同 key 以最后一次（更接近当前登录态）为准。 */
    private fun collectMergedCookie(): String {
        val map = LinkedHashMap<String, String>()
        cookieDomains.forEach { domain ->
            val ck = CookieManager.getInstance().getCookie(domain) ?: return@forEach
            ck.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val idx = it.indexOf('=')
                val key = it.substring(0, idx).trim()
                val value = it.substring(idx + 1).trim()
                if (key.isNotBlank() && value.isNotBlank()) map[key] = value
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun tryLogin() {
        if (finished) return
        val cookie = collectMergedCookie()
        if (cookie.isBlank()) return
        if (!MicaApi.cookieHasCredentials(cookie)) return

        finished = true
        handler.removeCallbacks(pollRunnable)
        CookieManager.getInstance().flush()

        Thread {
            val result = MicaApi.completeWebLogin(this, cookie)
            runOnUiThread {
                Toast.makeText(this, result.first, Toast.LENGTH_LONG).show()
                if (result.second) finish()
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
