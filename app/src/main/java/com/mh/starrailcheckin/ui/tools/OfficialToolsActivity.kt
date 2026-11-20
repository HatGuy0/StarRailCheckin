package com.mh.starrailcheckin.ui.tools

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.util.Prefs

/**
 * 官方「工具集合页」内嵌页。
 *
 * 关键点：
 * 1. 横屏承载官方为平板/PC 设计的横向布局；
 * 2. 把 App 已登录的米游社 Cookie 预置到 WebView，打开即为登录态；
 * 3. 支持页面内登录跳转与 window.open 弹窗（登录/工具详情常走新窗口）；
 * 4. 非 http(s) 链接交给系统（米游社 App / 微信 / 支付宝等），失败回落浏览器；
 * 5. 固定宽度的部分自动缩放到横屏宽度，响应式布局保持原样。
 */
class OfficialToolsActivity : AppCompatActivity() {

    companion object {
        private const val TOOL_URL =
            "https://act.mihoyo.com/sr/event/cultivation-tool/index.html" +
                "?lang=zh-cn&mhy_presentation_style=fullscreen&mhy_landscape=true&plat_type=pc"
        private const val REFERER = "https://www.mihoyo.com/"
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val LOGIN_COOKIE_NAMES = setOf(
            "ltoken", "ltoken_v2", "cookie_token", "cookie_token_v2"
        )
        private val AUTH_COOKIE_NAMES = LOGIN_COOKIE_NAMES + setOf(
            "ltuid", "ltuid_v2", "ltmid_v2", "account_id", "account_id_v2",
            "account_mid_v2", "stoken", "stuid", "login_ticket", "devmode", "mid"
        )
        private val COOKIE_PARENTS = listOf(
            "https://act.miyoushe.com" to ".miyoushe.com",
            "https://act.mihoyo.com" to ".mihoyo.com"
        )
        // 后面的通行证域名优先，避免页面域名里的旧 host-only Cookie 覆盖新登录凭证。
        private val AUTH_COOKIE_ORIGINS = listOf(
            "https://act.miyoushe.com",
            "https://www.miyoushe.com",
            "https://bbs.miyoushe.com",
            "https://api-takumi.miyoushe.com",
            "https://user.miyoushe.com",
            "https://passport.miyoushe.com",
            "https://passport-api.miyoushe.com",
            "https://act.mihoyo.com",
            "https://www.mihoyo.com",
            "https://api-takumi.mihoyo.com",
            "https://act-api-takumi.mihoyo.com",
            "https://user.mihoyo.com",
            "https://passport.mihoyo.com",
            "https://passport-api.mihoyo.com"
        )
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var backChip: TextView

    private data class Popup(
        val dialog: Dialog,
        val webView: WebView,
        var lastUrl: String? = null
    )
    private val popups = mutableListOf<Popup>()
    private var destroying = false
    private var loginSeen = false
    private var loginReloaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        hideSystemUi()

        CookieManager.getInstance().setAcceptCookie(true)

        setContentView(R.layout.activity_official_tools)
        val root = findViewById<View>(R.id.root)
        backChip = findViewById(R.id.backChip)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // 状态栏/导航栏已隐藏，只避开刘海区域，避免按钮被裁切
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            backChip.translationX = cutout.left.toFloat()
            backChip.translationY = -cutout.bottom.toFloat()
            insets
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)

        applySettings(webView)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleUri(request.url)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (view !== webView) return
                errorPanel.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                if (url != null && isLoginUrl(url)) {
                    loginSeen = true
                    loginReloaded = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val wv = view ?: return
                if (wv !== webView) return
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                syncAuthCookies {
                    // 等 Cookie 桥接完成后再刷新，否则页面仍会带着旧会话请求
                    if (loginSeen && !loginReloaded && url != null && !isLoginUrl(url)) {
                        loginReloaded = true
                        wv.reload()
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (view !== webView || request?.isForMainFrame != true) return
                val code = error?.errorCode ?: 0
                if (code == -3) return // ERR_ABORTED：重定向/中断，不当作失败
                progressBar.visibility = View.GONE
                errorText.text = "页面加载失败（${error?.description ?: "网络异常"}），请检查网络后重试"
                errorPanel.visibility = View.VISIBLE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view === webView) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = resultMsg != null && openPopup(resultMsg)

            override fun onCloseWindow(window: WebView?) {
                if (window === null) return
                if (window === webView) {
                    finish()
                } else {
                    dismissPopup(window)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@OfficialToolsActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@OfficialToolsActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            openExternal(Uri.parse(url))
        }

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            errorPanel.visibility = View.GONE
            loadHome()
        }
        findViewById<Button>(R.id.browserButton).setOnClickListener {
            openExternal(Uri.parse(TOOL_URL))
        }
        findViewById<TextView>(R.id.backChip).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popups.isNotEmpty()) {
                    popups.last().dialog.dismiss()
                    return
                }
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // setCookie 是异步操作；必须等预置完成再发页面请求，避免首屏拿到空 Cookie。
        seedCookiesFromPrefs {
            if (!destroying && !isFinishing) {
                if (savedInstanceState != null) {
                    webView.restoreState(savedInstanceState)
                } else {
                    loadHome()
                }
            }
        }
    }

    private fun loadHome() {
        errorPanel.visibility = View.GONE
        webView.loadUrl(TOOL_URL, mapOf("Referer" to REFERER))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(wv: WebView) {
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = UA
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        wv.isHorizontalScrollBarEnabled = false
        wv.isVerticalScrollBarEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(Color.BLACK)
    }

    /** 把 App 已登录 Cookie 预置到 .miyoushe.com / .mihoyo.com，进页面即登录态。 */
    private fun seedCookiesFromPrefs(onComplete: () -> Unit) {
        val cm = CookieManager.getInstance()
        // 保留网页内的新账号；若凭证只在某一个父域，先桥接后再加载工具页。
        val existing = readAuthCookies(cm)
        if (existing.keys.any { it in LOGIN_COOKIE_NAMES }) {
            writeCookiesToParents(existing, onComplete)
            return
        }

        val raw = Prefs.cookie(this).trim()
        if (raw.isBlank()) {
            onComplete()
            return
        }
        val pairs = raw.split(";")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
            .filter {
                it.first.isNotBlank() && it.second.isNotBlank() &&
                    !it.first.startsWith("stoken") && it.first != "mid" && it.first != "login_ticket"
            }
        if (pairs.none { it.first in LOGIN_COOKIE_NAMES }) {
            onComplete()
            return
        }
        writeCookiesToParents(pairs.toMap(LinkedHashMap()), onComplete)
    }

    /**
     * 通行证可能把 Cookie 写在 passport/user 等子域上（host-only）。
     * 桥接到两个父域，供账号验证、角色查询和养成接口共同使用。
     */
    private fun syncAuthCookies(onComplete: () -> Unit = {}) {
        writeCookiesToParents(readAuthCookies(CookieManager.getInstance()), onComplete)
    }

    private fun readAuthCookies(cm: CookieManager): LinkedHashMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for (origin in AUTH_COOKIE_ORIGINS) {
            val cookie = runCatching { cm.getCookie(origin) }.getOrNull() ?: continue
            for (part in cookie.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name in AUTH_COOKIE_NAMES && value.isNotEmpty()) values[name] = value
            }
        }
        return values
    }

    /** setCookie 是异步操作，全部回调结束后才能刷新页面或恢复加载。 */
    private fun writeCookiesToParents(
        values: Map<String, String>,
        onComplete: () -> Unit
    ) {
        val cm = CookieManager.getInstance()
        if (values.isEmpty()) {
            cm.flush()
            onComplete()
            return
        }

        var remaining = COOKIE_PARENTS.size * values.size
        var completed = false
        fun finishOne() {
            if (completed) return
            remaining -= 1
            if (remaining == 0) {
                completed = true
                cm.flush()
                onComplete()
            }
        }

        COOKIE_PARENTS.forEach { (origin, domain) ->
            values.forEach { (name, value) ->
                val cookie = "$name=$value; Domain=$domain; Path=/; Secure; " +
                    "SameSite=None; Max-Age=2592000"
                try {
                    cm.setCookie(origin, cookie) { finishOne() }
                } catch (_: Exception) {
                    finishOne()
                }
            }
        }
    }

    private fun hideSystemUi() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** http(s) 留在 WebView；其它 scheme 交给系统应用处理。 */
    private fun handleUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") return false
        return openExternal(uri)
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            val fallback = if (uri.scheme == "intent") {
                runCatching {
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        .getStringExtra("browser_fallback_url")
                }.getOrNull()
            } else null
            if (!fallback.isNullOrBlank()) {
                webView.loadUrl(fallback)
            } else {
                Toast.makeText(this, "未找到可打开该链接的应用", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun openPopup(resultMsg: Message): Boolean {
        return runCatching {
            val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            val wv = WebView(this)
            applySettings(wv)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            var sawLoginInPopup = false

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = handleUri(request.url)

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    if (url != null && isLoginUrl(url)) {
                        sawLoginInPopup = true
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    syncAuthCookies()
                    val wv = view ?: return
                    popups.firstOrNull { it.webView === wv }?.let { it.lastUrl = url }
                }
            }
            wv.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    msg: Message?
                ): Boolean = msg != null && openPopup(msg)

                override fun onCloseWindow(window: WebView?) {
                    if (window != null) dismissPopup(window)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@OfficialToolsActivity)
                        .setMessage(message)
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setCancelable(false)
                        .show()
                    return true
                }
            }

            val container = FrameLayout(this)
            container.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val close = TextView(this).apply {
                text = "×"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_web_chip)
                setOnClickListener { dialog.dismiss() }
            }
            container.addView(
                close,
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(12), dp(12), dp(12), 0)
                }
            )

            dialog.setContentView(container)
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            dialog.setOnDismissListener {
                val popup = popups.firstOrNull { it.webView === wv }
                val closedLogin = sawLoginInPopup ||
                    popup?.lastUrl?.let { isLoginUrl(it) } == true
                wv.destroy()
                popups.removeAll { it.webView === wv }
                syncAuthCookies {
                    // 登录/授权窗口关闭后，等新 Cookie 落盘再刷新主页面
                    if (closedLogin && !destroying && !isFinishing && !isDestroyed) {
                        webView.reload()
                    }
                }
            }
            dialog.show()

            popups.add(Popup(dialog, wv))
            (resultMsg.obj as WebView.WebViewTransport).webView = wv
            resultMsg.sendToTarget()
            true
        }.getOrDefault(false)
    }

    private fun dismissPopup(window: WebView) {
        popups.firstOrNull { it.webView === window }?.dialog?.dismiss()
    }

    private fun isLoginUrl(url: String): Boolean =
        url.contains("login", ignoreCase = true) ||
            url.contains("passport", ignoreCase = true) ||
            url.contains("user.miyoushe.com") ||
            url.contains("user.mihoyo.com")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        webView.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        destroying = true
        popups.toList().forEach { it.dialog.dismiss() }
        popups.clear()
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
