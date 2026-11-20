package com.mh.starrailcheckin.ui.about

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.appcompat.app.AppCompatActivity
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.BilibiliApi
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 作者 B 站主页与 QQ 群号。 */
object AboutLinks {
    const val AUTHOR_SPACE = BilibiliApi.SPACE_URL
    const val QQ_GROUP_NUMBER = "981703967"
    const val DEFAULT_AUTHOR_NAME = "mh梦虎"
}

/** 关于页：应用信息 + 作者卡 + 协议/反馈/更新入口。 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme() {
                    Scaffold(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        AboutScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current
    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "2.1"

    var authorName by remember { mutableStateOf(AboutLinks.DEFAULT_AUTHOR_NAME) }
    var authorFace by remember { mutableStateOf("") }
    var showUpdate by remember { mutableStateOf(false) }

    // 读取本地缓存；缓存有效则直接用，否则 IO 线程拉取 B 站公开资料，失败回退本地。
    LaunchedEffect(Unit) {
        val cachedName = Prefs.authorName(context)
        val cachedFace = Prefs.authorFace(context)
        if (Prefs.authorCacheValid(context) && cachedName.isNotBlank()) {
            authorName = cachedName
            authorFace = cachedFace
            return@LaunchedEffect
        }
        val profile = withContext(Dispatchers.IO) { BilibiliApi.fetchProfile() }
        if (profile != null) {
            Prefs.saveAuthorProfile(context, profile.name, profile.face)
            authorName = profile.name.ifBlank { AboutLinks.DEFAULT_AUTHOR_NAME }
            authorFace = profile.face
        } else if (cachedName.isNotBlank()) {
            authorName = cachedName
            authorFace = cachedFace
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun copyGroupNumber() {
        val clipboard =
            context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("QQ群号", AboutLinks.QQ_GROUP_NUMBER))
        android.widget.Toast.makeText(context, "群号 ${AboutLinks.QQ_GROUP_NUMBER} 已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        TopAppBar(
            title = "关于",
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

        Spacer(modifier = Modifier.height(16.dp))

        // 头部：应用图标 + 名称 + 版本号（仿图 1）
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "应用图标",
                modifier = Modifier.size(88.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = context.getString(R.string.app_name),
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Version: $versionName",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 作者卡（最顶部，仿图 5）
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openUrl(AboutLinks.AUTHOR_SPACE) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuthorAvatar(faceUrl = authorFace, modifier = Modifier.size(52.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = authorName,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${AboutLinks.DEFAULT_AUTHOR_NAME}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
                Text(
                    text = "›",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 功能入口列表
        AppCard(modifier = Modifier.fillMaxWidth()) {
            AboutItem("引用", "查看开源组件") {
                context.startActivity(Intent(context, OpenSourceActivity::class.java))
            }
            AboutItem("帮助与反馈", "QQ 群 ${AboutLinks.QQ_GROUP_NUMBER}，点击复制群号") { copyGroupNumber() }
            AboutItem("隐私政策", "查看隐私政策") {
                context.startActivity(
                    Intent(context, PolicyActivity::class.java)
                        .putExtra(PolicyActivity.EXTRA_TYPE, PolicyActivity.TYPE_PRIVACY)
                )
            }
            AboutItem("用户协议", "查看用户协议") {
                context.startActivity(
                    Intent(context, PolicyActivity::class.java)
                        .putExtra(PolicyActivity.EXTRA_TYPE, PolicyActivity.TYPE_AGREEMENT)
                )
            }
            AboutItem("检查更新", "检查新版本") { showUpdate = true }
        }
    }

    if (showUpdate) {
        OverlayDialog(
            show = true,
            title = "检查更新",
            onDismissRequest = { showUpdate = false },
            content = {
                Text(
                    text = "当前版本：$versionName\n\n已是最新版本，后续将接入在线更新检查。",
                    style = MiuixTheme.textStyles.body2
                )
                Spacer(Modifier.height(12.dp))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "知道了",
                    onClick = { showUpdate = false }
                )
            }
        )
    }
}

@Composable
private fun AboutItem(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MiuixTheme.textStyles.title3)
            Text(
                summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        Text(
            text = "›",
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}

/** 作者头像：优先联网头像，失败回退本地内置头像。 */
@Composable
private fun AuthorAvatar(faceUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(faceUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(faceUrl) {
        if (faceUrl.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                EnkaImageLoader.bitmapForUrl(context, faceUrl, 128)
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "作者头像",
            modifier = modifier.clip(CircleShape)
        )
    } else {
        Image(
            painter = painterResource(R.drawable.author_avatar),
            contentDescription = "作者头像",
            modifier = modifier.clip(CircleShape)
        )
    }
}
