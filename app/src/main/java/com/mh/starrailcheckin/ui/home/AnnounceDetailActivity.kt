package com.mh.starrailcheckin.ui.home

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

/**
 * 米游社资讯详情页：内嵌 WebView 直接加载米游社原帖页面，
 * 完整保留官方公告的排版、颜色与配图，不做纯文本剥离。
 * 页面内图片点击通过 JS 桥回调全屏高清大图查看器。
 */
class AnnounceDetailActivity : LightBaseActivity() {

    private var webView: WebView? = null

    /** JS 桥：把 WebView 内点击的图片原图 URL 回传原生。 */
    private inner class ImageBridge {
        @JavascriptInterface
        fun openImage(url: String) {
            if (url.isBlank()) return
            runOnUiThread { ImageViewerActivity.start(this@AnnounceDetailActivity, url) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val postId = intent.getStringExtra("n_post_id").orEmpty()
        val title = intent.getStringExtra("n_title").orEmpty()
        val articleUrl = "${com.mh.starrailcheckin.network.ApiHosts.WWW_MIYOUSHE}/sr/article/$postId"

        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme(darkTheme = false, forceDark = true) {
                    DetailHost(title = title, articleUrl = articleUrl, onBack = { finish() })
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DetailHost(
        title: String,
        articleUrl: String,
        onBack: () -> Unit
    ) {
        DisposableEffect(articleUrl) {
            onDispose {
                webView?.destroy()
                webView = null
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = "返回",
                            tint = Color(0xFF24292F)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = title.ifBlank { "资讯详情" },
                        fontSize = 18.sp,
                        color = Color(0xFF24292F),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                }

                AndroidView(
                    factory = { createWebView(articleUrl) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadsImagesAutomatically = true
            settings.userAgentString = settings.userAgentString + " Mobile StarRailCheckin"

            addJavascriptInterface(ImageBridge(), "AppBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(IMG_CLICK_JS, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }
            webView = this
            loadUrl(url)
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private companion object {
        /** 给页面内所有 <img> 绑定点击，回传原图地址给原生查看器。 */
        const val IMG_CLICK_JS = """
            (function () {
                function bind() {
                    var imgs = document.querySelectorAll('img');
                    for (var i = 0; i < imgs.length; i++) {
                        var im = imgs[i];
                        if (im.dataset.bridgeBound === '1') continue;
                        im.dataset.bridgeBound = '1';
                        im.addEventListener('click', function () {
                            var src = this.currentSrc || this.src || '';
                            if (src && /^https?:/.test(src)) {
                                AppBridge.openImage(src);
                            }
                        });
                    }
                }
                bind();
                try {
                    new MutationObserver(bind).observe(document.body, { childList: true, subtree: true });
                } catch (e) {}
            })();
        """
    }
}
