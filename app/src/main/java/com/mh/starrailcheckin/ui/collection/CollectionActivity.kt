package com.mh.starrailcheckin.ui.collection

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.Prefs
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

/** 成就 / 图鉴收集度：接口统计 + 角色收集进度 + UIAF 成就本地导入导出。 */
class CollectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        CollectionScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = LocalAppDarkTheme.current

    var stats by remember { mutableStateOf<MicaApi.PlayerStats?>(null) }
    var localAch by remember { mutableIntStateOf(0) }
    var totalChars by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            EnkaNameMap.load(context)
            totalChars = EnkaNameMap.allCharNames().size
            localAch = UiafStore.list(context).size
            val result = withContext(Dispatchers.IO) {
                runCatching { MicaApi.fetchPlayerStats(context) }
            }
            result.onSuccess { stats = it }
                .onFailure { e ->
                    error = when (e) {
                        is MicaException -> e.message
                        else -> e.message ?: "收集度获取失败"
                    }
                }
            loading = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@rememberLauncherForActivityResult
            val imported = UiafStore.parse(text)
            if (imported.isEmpty()) {
                Toast.makeText(context, "未识别到 UIAF 成就记录", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            val total = UiafStore.saveMerged(context, imported)
            localAch = total
            Toast.makeText(context, "成功导入 ${imported.size} 条成就（本地共 $total 条）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导入失败: 文件格式不正确", Toast.LENGTH_SHORT).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val uid = Prefs.uid(context).ifBlank { "unknown" }
            val text = UiafStore.export(context, uid)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            Toast.makeText(context, "UIAF 成就导出成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val gradient = remember(dark) {
        Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            title = "成就 · 图鉴收集",
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                loading && stats == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                ) {
                    InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("正在加载收集度…", color = MiuixTheme.colorScheme.onSurfaceSecondary)
                }

                !error.isNullOrBlank() -> AppCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(error ?: "", color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(10.dp))
                        TextButton(text = "重试", onClick = { reload() })
                    }
                }

                else -> {
                    OverviewCard(stats, localAch)
                    CollectionCard(
                        owned = stats?.avatarNum ?: 0,
                        total = totalChars
                    )
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("UIAF 成就备份", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                            Text(
                                "游戏内成就明细不通过 Cookie 下发；可从其他工具导出 UIAF v1.1 文件导入本地留存，也可把本地记录导出备份。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextButton(text = "导入 UIAF", onClick = {
                                    importLauncher.launch(
                                        arrayOf("application/json", "text/plain", "*/*")
                                    )
                                })
                                TextButton(text = "导出本地成就", onClick = {
                                    exportLauncher.launch("uiaf_${Prefs.uid(context)}.json")
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(stats: MicaApi.PlayerStats?, localAch: Int) {
    AppCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("活跃天数", stats?.activeDays?.toString() ?: "—", Modifier.weight(1f))
            StatCell("成就(接口)", stats?.achievementNum?.toString() ?: "—", Modifier.weight(1f))
            StatCell("本地留存", localAch.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}

@Composable
private fun CollectionCard(owned: Int, total: Int) {
    val primary = MiuixTheme.colorScheme.primary
    val frac = if (total <= 0) 0f else (owned.toFloat() / total).coerceIn(0f, 1f)
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色收集", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$owned / $total", fontWeight = FontWeight.Bold, color = primary)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(primary)
                )
            }
            Text(
                if (total > 0) "收集率 ${(frac * 100).toInt()}%" else "图鉴字典未加载",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
    }
}
