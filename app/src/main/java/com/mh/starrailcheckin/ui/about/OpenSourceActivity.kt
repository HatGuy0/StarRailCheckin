package com.mh.starrailcheckin.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class OssItem(
    val name: String,
    val author: String,
    val license: String,
    val url: String
)

/** 本应用使用到的开源组件清单（协议均以各仓库 LICENSE 为准）。 */
private val ossItems = listOf(
    OssItem("AndroidX", "Android Open Source Project, Google Inc.", "Apache-2.0",
        "https://developer.android.com/jetpack/androidx"),
    OssItem("Material Components for Android", "Google", "Apache-2.0",
        "https://github.com/material-components/material-components-android"),
    OssItem("Kyant0 Backdrop / Shapes", "Kyant0", "Apache-2.0",
        "https://github.com/Kyant0/AndroidLiquidGlass"),
    OssItem("Miuix KMP", "compose-miuix-ui / yukonga", "Apache-2.0",
        "https://github.com/compose-miuix-ui/miuix"),
    OssItem("OkHttp", "Square", "Apache-2.0",
        "https://github.com/square/okhttp"),
    OssItem("Kotlinx Coroutines", "JetBrains", "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.coroutines"),
    OssItem("Kotlin Standard Library", "JetBrains", "Apache-2.0",
        "https://github.com/JetBrains/kotlin"),
    OssItem("ZXing", "zxing", "Apache-2.0",
        "https://github.com/zxing/zxing"),
    OssItem("uCrop", "Yalantis", "Apache-2.0",
        "https://github.com/Yalantis/uCrop"),
    OssItem("Onboarding Screen", "ahmmedrejowan", "Apache-2.0",
        "https://github.com/ahmmedrejowan/OnboardingScreen-JetpackCompose")
)

/** 引用（开源协议）列表页：点击条目跳转对应项目网站。 */
class OpenSourceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme() {
                    Scaffold(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        OpenSourceScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenSourceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient))
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = "引用",
            color = Color.Transparent,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "星铁打卡小助手使用了以下项目的部分或全部内容，感谢这些项目的开发者提供的大力支持（排名顺序不分先后）。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "开源项目",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            ossItems.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(item.url) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${item.author} | ${item.license}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "›",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                if (index != ossItems.lastIndex) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .height(1.dp)
                            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    )
                }
            }
        }
        }
    }
}
