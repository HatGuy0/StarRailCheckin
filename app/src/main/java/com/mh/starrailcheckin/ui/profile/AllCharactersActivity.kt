package com.mh.starrailcheckin.ui.profile

import android.app.Activity
import com.mh.starrailcheckin.util.BitmapSampler
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.ui.components.EmptyState
import com.mh.starrailcheckin.ui.components.ErrorState
import com.mh.starrailcheckin.ui.components.LoadingState
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import com.mh.starrailcheckin.util.UiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全角色面板查询（Cookie 战绩接口）：角色选择页。
 * 布局仿米游社「全部角色」：元素/命途双筛选 + 三列角色卡片。
 * 数据：MicaApi.fetchAvatarList（1 小时缓存，重进/切页不刷新）。
 */
class AllCharactersActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        AllCharactersScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

/** 元素筛选列表（与 assets/filter/element_*.png 同名） */
private val ELEMENTS = listOf("Physical", "Fire", "Ice", "Thunder", "Wind", "Quantum", "Imaginary")

/** 命途：charMeta 内部代号 → StarRailRes 图标文件名（assets/filter/path_*.png） */
private val PATHS = listOf(
    "Warrior" to "Destruction",
    "Rogue" to "Hunt",
    "Mage" to "Erudition",
    "Shaman" to "Harmony",
    "Warlock" to "Nihility",
    "Knight" to "Preservation",
    "Priest" to "Abundance",
    "Memory" to "Remembrance",
    "Elation" to "Elation"
)

@Composable
private fun AllCharactersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = LocalAppDarkTheme.current
    val view = LocalView.current

    // 与主页一致：浅色/深色状态栏图标跟随主题
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !dark
    }

    val loggedIn = remember { Prefs.isLoggedIn(context) }
    var avatars by remember { mutableStateOf<List<MicaApi.AvatarSummary>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }

    var selectedElement by remember { mutableStateOf<String?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        if (!loggedIn) { loading = false; return@LaunchedEffect }
        loading = true
        error = null
        val force = forceNext
        forceNext = false
        withContext(Dispatchers.IO) {
            EnkaNameMap.load(context)
            runCatching { MicaApi.fetchAvatarList(context, force = force) }
                .onSuccess { avatars = it }
                .onFailure {
                    LogHelper.e("AllChar", "全角色列表加载失败", it)
                    if (avatars == null) error = UiError.message(it, "加载失败")
                    else Toast.makeText(context, UiError.message(it, "刷新失败"), Toast.LENGTH_SHORT).show()
                }
        }
        loading = false
    }

    val filtered = remember(avatars, selectedElement, selectedPath) {
        avatars.orEmpty().filter { a ->
            (selectedElement == null || EnkaNameMap.charElement(a.id) == selectedElement) &&
                (selectedPath == null || EnkaNameMap.charPath(a.id) == selectedPath)
        }.sortedWith(compareByDescending<MicaApi.AvatarSummary> { it.rarity }.thenByDescending { it.level })
    }

    val gradient = remember(dark) {
        Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        // 顶栏：返回 + 标题 + 手动刷新
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                top.yukonga.miuix.kmp.basic.Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                "全部角色",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            TextButton(
                text = "刷新",
                onClick = {
                    if (loggedIn && !loading) { forceNext = true; reload++ }
                }
            )
        }

        if (!loggedIn) {
            EmptyState("需要登录且获取到 Cookie 后使用\n请在「我的」页登录米游社账号", modifier = Modifier.fillMaxSize())
            return@Column
        }

        // 元素筛选行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterCircle(selected = selectedElement == null, onClick = { selectedElement = null }) {
                AllGridIcon(selected = selectedElement == null)
            }
            ELEMENTS.forEach { e ->
                FilterCircle(selected = selectedElement == e, onClick = {
                    selectedElement = if (selectedElement == e) null else e
                }) {
                    AssetIcon("filter/element_$e.png", EnkaNameMap.elementNameOf(e), selectedElement == e)
                }
            }
        }

        // 命途筛选行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterCircle(selected = selectedPath == null, onClick = { selectedPath = null }) {
                AllGridIcon(selected = selectedPath == null)
            }
            PATHS.forEach { (code, icon) ->
                FilterCircle(selected = selectedPath == code, onClick = {
                    selectedPath = if (selectedPath == code) null else code
                }) {
                    AssetIcon(
                        assetPath = "filter/path_$icon.png",
                        fallback = EnkaNameMap.pathNameOf(code),
                        onPrimary = selectedPath == code,
                        tint = if (selectedPath == code) MiuixTheme.colorScheme.onPrimary
                        else MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            loading && avatars == null -> LoadingState(modifier = Modifier.fillMaxSize())
            error != null && avatars == null -> ErrorState(
                message = error ?: "加载失败",
                onRetry = { forceNext = true; reload++ },
                modifier = Modifier.fillMaxSize()
            )
            filtered.isEmpty() -> EmptyState("该筛选条件下暂无角色")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { a ->
                        CharacterCard(a)
                    }
                }
            }
        }
    }
}

/** 圆形筛选按钮：未选中用 surfaceVariant、选中用主色 */
@Composable
private fun FilterCircle(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 「全部」四格图标 */
@Composable
private fun AllGridIcon(selected: Boolean) {
    val c = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceSecondary
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(c))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(c))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(c))
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(1.5.dp)).background(c))
        }
    }
}

@Composable
private fun rememberAssetBitmap(path: String): ImageBitmap? {
    val context = LocalContext.current
    // 素材优先取同名单色 webp（包体积更小），缺失再回退原路径（png）。
    val webp = path.substringBeforeLast(".") + ".webp"
    val candidates = if (webp == path) listOf(path) else listOf(webp, path)
    return remember(path) {
        candidates.firstNotNullOfOrNull { p ->
            runCatching {
                context.assets.open(p).use { BitmapSampler.decodeStream(it, 256) }?.asImageBitmap()
            }.getOrNull()
        }
    }
}

@Composable
private fun AssetIcon(
    assetPath: String,
    fallback: String,
    onPrimary: Boolean = false,
    tint: Color? = null
) {
    val bmp = rememberAssetBitmap(assetPath)
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = fallback,
            modifier = Modifier.size(20.dp),
            colorFilter = tint?.let { ColorFilter.tint(it) }
        )
    } else {
        Text(
            fallback,
            fontSize = 9.sp,
            color = if (onPrimary) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSurfaceSecondary,
            maxLines = 1
        )
    }
}

/** Enka 图片加载器 → Compose 桥接（自带内存/磁盘缓存） */
@Composable
private fun EnkaImage(modifier: Modifier = Modifier, onLoad: (ImageView) -> Unit) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = onLoad,
        modifier = modifier
    )
}

/** 立绘：自己解码 Bitmap 后用 Compose Image 渲染，确保内容严格裁剪在父容器内。 */
@Composable
private fun PortraitImage(aid: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bmp by remember(aid) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(aid) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) {
                runCatching { EnkaImageLoader.portraitBitmap(context, aid) }.getOrNull()
            }?.asImageBitmap()
        }
    }
    val image = bmp ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
    )
}

/** 角色卡片：左立绘（稀有度渐变底 + 元素/命途小标 + 名字），右信息区（等级/星魂/光锥） */
@Composable
private fun CharacterCard(a: MicaApi.AvatarSummary) {
    val context = LocalContext.current
    val rarity = if (a.rarity > 0) a.rarity else EnkaNameMap.charRarity(a.id).ifZero { 4 }
    val gradient = if (rarity >= 5) {
        Brush.verticalGradient(listOf(Color(0xFFC9A06A), Color(0xFF8B6B3E)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFB9A3E3), Color(0xFF7A63A8)))
    }
    val name = EnkaNameMap.charName(a.id).let {
        if (it.isBlank() || it.contains("{NICKNAME}", ignoreCase = true)) "开拓者" else it
    }
    val element = EnkaNameMap.charElement(a.id)
    val pathIcon = PATHS.firstOrNull { it.first == EnkaNameMap.charPath(a.id) }?.second
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(146.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable {
                context.startActivity(
                    android.content.Intent(context, AvatarDetailActivity::class.java)
                        .putExtra(AvatarDetailActivity.EXTRA_AVATAR_ID, a.id)
                )
            }
    ) {
        // 左：立绘（严格裁剪在色块内，不允许溢出）
        Box(
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight()
                .clipToBounds()
                .background(gradient)
        ) {
            PortraitImage(aid = a.id, modifier = Modifier.fillMaxSize())
            // 左上角：元素 + 命途小圆标
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (element.isNotBlank()) {
                    SmallBadge { AssetIcon("filter/element_$element.png", EnkaNameMap.elementNameOf(element)) }
                }
                if (pathIcon != null) {
                    SmallBadge { AssetIcon("filter/path_$pathIcon.png", EnkaNameMap.pathNameOf(EnkaNameMap.charPath(a.id))) }
                }
            }
            // 底部名字
            Text(
                name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(vertical = 3.dp, horizontal = 2.dp)
            )
        }

        // 右：信息区
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Lv.${a.level}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.weight(1f))
                Text("✦${a.rank}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
            }
            if (a.lcId > 0) {
                Text(
                    roman(a.lcRank),
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                EnkaImage(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(42.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    onLoad = { iv -> EnkaImageLoader.loadLightCone(context, a.lcId, iv) }
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    repeat(EnkaNameMap.lcRarity(a.lcId).coerceIn(1, 5)) {
                        Text("★", fontSize = 9.sp, color = Color(0xFFF5B041))
                    }
                }
            } else {
                Text(
                    "无光锥",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/** 卡片左上角的小圆形徽章底 */
@Composable
private fun SmallBadge(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) { content() }
    }
}

private fun roman(n: Int): String = when (n) {
    1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"; 6 -> "VI"; else -> ""
}

private inline fun Int.ifZero(block: () -> Int): Int = if (this == 0) block() else this
