package com.mh.starrailcheckin.ui.gacha

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.network.MicaException
import androidx.appcompat.app.AppCompatActivity
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 跃迁抽卡分析二级页：粘贴游戏内跃迁链接 → 拉全量记录 → 跳 GachaShowcaseActivity。
 * Compose + Miuix 重写；网络/历史/导入导出/缓存回退逻辑与旧 View 版一致。
 */
class GachaInputActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme() {
                    // Scaffold 内置 Miuix 弹窗宿主，否则 OverlayDialog 无处挂载、点击无响应
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        GachaInputScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun GachaInputScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var linkInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(GachaHistory.list(context)) }
    var showManage by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<GachaHistory.Entry?>(null) }

    fun refreshHistory() {
        history = GachaHistory.list(context)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@rememberLauncherForActivityResult
            val imported = GachaUigf.parse(text)
            if (imported.isEmpty()) {
                Toast.makeText(context, "未识别到 UIGF 抽卡记录", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            var count = 0
            imported.forEach {
                if (it.recordsJson.isNotBlank()) {
                    GachaHistory.saveMerged(context, it.uid, it.recordsJson)
                    count++
                }
            }
            Toast.makeText(context, "成功导入 $count 个 UID 的记录", Toast.LENGTH_SHORT).show()
            refreshHistory()
        } catch (e: Exception) {
            Toast.makeText(context, "导入失败: 文件格式不正确", Toast.LENGTH_SHORT).show()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val entry = pendingExport
        if (uri == null || entry == null) return@rememberLauncherForActivityResult
        try {
            EnkaNameMap.load(context)
            val text = GachaUigf.export(entry.records, entry.uid)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            Toast.makeText(context, "UIGF 导出成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            pendingExport = null
        }
    }

    fun showHelp() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://mp.weixin.qq.com/s/384vAnIFyTdLb6OX69LxoA")
        )
        context.startActivity(intent)
    }

    fun reportError(e: Exception) {
        val msg = if (e is MicaException) MicaApi.gachaRetcodeMessage(e.retcode)
        else (e.message ?: "抽卡记录拉取失败")
        LogHelper.e("GachaInput", "抽卡记录失败: $msg", e)
        status = msg
    }

    suspend fun openGachaShowcase(params: MicaApi.GachaLinkParams) {
        val all = withContext(Dispatchers.IO) {
            val seen = HashSet<String>()
            MicaApi.fetchAllGacha(context, params, 11, seen) +
                MicaApi.fetchAllGacha(context, params, 12, seen) +
                MicaApi.fetchAllGacha(context, params, 1, seen) +
                MicaApi.fetchAllGacha(context, params, 2, seen)
        }
        if (all.isEmpty()) {
            status = "未拉到记录，链接可能已过期"
            return
        }
        val arr = JSONArray()
        all.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("gachaType", r.gachaType)
                put("gachaId", r.gachaId)
                put("itemType", r.itemType)
                put("name", r.name)
                put("rank", r.rank)
                put("time", r.time)
            })
        }
        GachaHistory.save(context, params.uid.orEmpty(), arr.toString())
        status = "已直拉 ${all.size} 条，正在打开统计…"

        val intent = Intent(context, GachaShowcaseActivity::class.java)
        intent.putExtra("records_json", arr.toString())
        intent.putExtra("uid", params.uid.orEmpty())
        intent.putExtra("region", params.region.orEmpty())
        context.startActivity(intent)
    }

    fun pullGachaPaste() {
        val raw = linkInput.trim()
        if (raw.isBlank()) {
            Toast.makeText(context, "请先粘贴跃迁记录链接", Toast.LENGTH_SHORT).show()
            return
        }
        val params = MicaApi.linkParamsForGacha(raw)
        if (params == null) {
            Toast.makeText(context, "未在链接中解析到authkey，请复制完整跃迁记录链接", Toast.LENGTH_LONG).show()
            return
        }
        loading = true
        status = "正在拉取跃迁记录…"
        scope.launch {
            try {
                openGachaShowcase(params)
            } catch (e: Exception) {
                reportError(e)
            } finally {
                loading = false
            }
        }
    }

    fun exportGachaHistory() {
        val items = GachaHistory.list(context)
        if (items.isEmpty()) {
            Toast.makeText(context, "暂无抽卡历史可导出", Toast.LENGTH_SHORT).show()
            return
        }
        // 导出最近一次快照为 UIGF v4 文件；历史管理里可切换到其他快照后重新导出
        pendingExport = items.first()
        val uid = items.first().uid.ifBlank { "unknown" }
        exportLauncher.launch("uigf_${uid}_${System.currentTimeMillis()}.json")
    }

    fun importGachaHistory() {
        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
    }

    fun openHistoryEntry(entry: GachaHistory.Entry) {
        val intent = Intent(context, GachaShowcaseActivity::class.java)
        intent.putExtra("records_json", entry.records)
        intent.putExtra("uid", entry.uid)
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.Top
    ) {
        TopAppBar(
            title = "跃迁记录分析",
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

        Column(modifier = Modifier.padding(16.dp)) {
            AppCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = linkInput,
                        onValueChange = { linkInput = it },
                        label = "粘贴跃迁记录链接",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { pullGachaPaste() },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("粘贴链接拉取")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "使用帮助",
                            onClick = { showHelp() },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "历史管理",
                            onClick = { showManage = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "导出 UIGF",
                            onClick = { exportGachaHistory() },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "导入 UIGF",
                            onClick = { importGachaHistory() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(Modifier.height(16.dp))
            AppCard {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    if (history.isEmpty()) {
                        Text(
                            text = "暂无历史记录，成功拉取跃迁后会自动保存快照，可离线回看",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        history.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openHistoryEntry(entry) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "UID ${entry.uid.ifBlank { "未知UID" }}",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = entry.date,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 历史管理弹窗
    if (showManage) {
        OverlayDialog(
            show = true,
            title = "管理记录",
            onDismissRequest = { showManage = false },
            content = {
                Column {
                    history.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    GachaHistory.delete(context, entry.index)
                                    refreshHistory()
                                    if (history.isEmpty()) showManage = false
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "UID ${if (entry.uid.isBlank()) "未知" else entry.uid} · ${entry.date}",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "删除",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "清空全部",
                            onClick = { showClearConfirm = true },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "关闭",
                            onClick = { showManage = false },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }

    // 清空二次确认
    if (showClearConfirm) {
        OverlayDialog(
            show = true,
            title = "确认清空",
            onDismissRequest = { showClearConfirm = false },
            content = {
                Text(
                    text = "确定清空全部抽卡历史记录？",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { showClearConfirm = false },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "清空",
                        onClick = {
                            GachaHistory.clear(context)
                            refreshHistory()
                            showClearConfirm = false
                            showManage = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        )
    }
}
