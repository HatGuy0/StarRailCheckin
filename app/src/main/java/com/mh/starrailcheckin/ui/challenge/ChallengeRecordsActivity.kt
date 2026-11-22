package com.mh.starrailcheckin.ui.challenge

import android.os.Bundle
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.ChallengeAvatar
import com.mh.starrailcheckin.network.ChallengeModeInfo
import com.mh.starrailcheckin.network.ChallengeNode
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import com.mh.starrailcheckin.util.UiError
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 逐光捡金：异相仲裁 / 忘却之庭 / 虚构叙事 / 末日幻影战绩。 */
class ChallengeRecordsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                // 跟随系统：主页根 Activity 会写应用主题，这里直接读。
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        ChallengeRecordsScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

private enum class ChallengeMode(val tab: String, val reviewTitle: String) {
    ARBITRATION("异相仲裁", "异相仲裁挑战回顾"),
    FORGOTTEN_HALL("忘却之庭", "混沌回忆挑战回顾"),
    PURE_FICTION("虚构叙事", "虚构叙事挑战回顾"),
    PEAK("末日幻影", "末日幻影挑战回顾")
}

private fun loadMode(context: android.content.Context, mode: ChallengeMode, force: Boolean): ChallengeModeInfo =
    when (mode) {
        ChallengeMode.ARBITRATION -> MicaApi.fetchAnomalyArbitration(context, force)
        ChallengeMode.FORGOTTEN_HALL -> MicaApi.fetchForgottenHall(context, force)
        ChallengeMode.PURE_FICTION -> MicaApi.fetchPureFiction(context, force)
        ChallengeMode.PEAK -> MicaApi.fetchPeakAsChallenge(context, force)
    }

@Composable
private fun ChallengeRecordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modes = remember { ChallengeMode.entries.toList() }
    val cache = remember { mutableStateMapOf<Int, ChallengeModeInfo>() }
    val dark = LocalAppDarkTheme.current

    var selected by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<ChallengeModeInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean = false) {
        val index = selected
        val mode = modes[index]
        scope.launch {
            loading = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching { loadMode(context, mode, force) }
            }
            result.onSuccess {
                cache[index] = it
                if (selected == index) info = it
            }.onFailure { e ->
                if (selected == index) {
                    error = UiError.message(e, "${mode.tab} 查询失败")
                }
            }
            if (selected == index) loading = false
        }
    }

    LaunchedEffect(selected) {
        val cached = cache[selected]
        if (cached != null) {
            info = cached
            error = null
            loading = false
        } else {
            info = null
            load()
        }
    }

    val gradient = remember(dark) {
        Brush.verticalGradient(
            if (dark) DarkBackgroundGradient else LightBackgroundGradient
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = "逐光捡金-战绩查询",
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

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        Spacer(modifier = Modifier.height(8.dp))

        ModeSegmented(
            labels = modes.map { it.tab },
            selected = selected,
            onSelect = { selected = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            loading -> AppCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "正在查询战绩…",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            }

            error != null -> AppCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = error ?: "",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(text = "重新查询", onClick = { load(force = true) })
                }
            }

            info != null -> info?.let { data ->
                ChallengeCard(
                    title = modes[selected].reviewTitle,
                    info = data,
                    onRefresh = { load(force = true) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** Miuix 风格胶囊分段选择器。 */
@Composable
private fun ModeSegmented(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(4.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceSecondary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatFetchedAt(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(ts))

@Composable
private fun ChallengeCard(
    title: String,
    info: ChallengeModeInfo,
    onRefresh: () -> Unit
) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "刷新",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = info.scheduleName.ifBlank { "本期" },
                    style = MiuixTheme.textStyles.title3,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "★ ${info.totalStars}/${info.maxStars}",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
            }

            if (info.periodText.isNotBlank()) {
                Text(
                    text = "统计周期：${info.periodText}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            if (info.fetchedAt > 0L) {
                Text(
                    text = "更新于 ${formatFetchedAt(info.fetchedAt)} · 60 秒内复用缓存",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!info.hasData || info.records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无挑战数据",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            } else {
                info.records.forEachIndexed { index, record ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.dividerLine)
                        )
                    }
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(MiuixTheme.colorScheme.dividerLine)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = record.title,
                                style = MiuixTheme.textStyles.title3
                            )
                            if (record.subtitle.isNotBlank()) {
                                Text(
                                    text = record.subtitle,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            record.nodes.forEach { node ->
                                NodeLine(node)
                            }
                        }
                        Text(
                            text = "★ ${record.stars}/${record.maxStars}",
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Bold,
                            color = if (record.finished) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceSecondary
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 单个队伍节点：上半/下半标签 + 回合数 + 上阵角色头像。 */
@Composable
private fun NodeLine(node: ChallengeNode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = node.label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
        if (node.roundNum > 0) {
            Text(
                text = "${node.roundNum} 轮",
                fontSize = 9.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        node.avatars.forEach { AvatarChip(it) }
    }
}

/** 上阵角色头像小卡：图标 + 等级角标 + 名称。 */
@Composable
private fun AvatarChip(a: ChallengeAvatar) {
    val context = LocalContext.current
    val fallback = EnkaNameMap.charName(a.id)
    val name = a.name.ifBlank {
        if (fallback.isBlank() || fallback.contains("{NICKNAME}", ignoreCase = true)) "开拓者" else fallback
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(46.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
        ) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                },
                update = { iv -> EnkaImageLoader.loadAvatar(context, a.id, iv) },
                modifier = Modifier.fillMaxSize()
            )
            if (a.level > 0) {
                Text(
                    text = "Lv.${a.level}",
                    fontSize = 8.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 2.dp)
                )
            }
        }
        Text(
            text = name,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceSecondary
        )
    }
}
