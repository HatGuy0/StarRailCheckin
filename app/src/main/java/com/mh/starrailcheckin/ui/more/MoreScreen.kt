package com.mh.starrailcheckin.ui.more

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mh.starrailcheckin.ui.components.DialogButtonRow
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mh.starrailcheckin.network.MicaApi
import com.mh.starrailcheckin.util.BackupHelper
import com.mh.starrailcheckin.util.LogHelper
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mh.starrailcheckin.ui.components.AppCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「更多」二级页：收纳低频/高级设置。
 * 入口由设置页进入；设备指纹绑定/清除、权限状态明细、手动 Cookie 高级导入、
 * 数据管理与关于协议类条目集中在此。
 */
@Composable
fun MoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var deviceInput by remember { mutableStateOf("") }
    var bound by remember { mutableStateOf(Prefs.hasBoundDevice(context)) }
    var autoFp by remember { mutableStateOf(Prefs.autoDeviceFp(context)) }
    var generatingFp by remember { mutableStateOf(false) }

    var showCookieDialog by remember { mutableStateOf(false) }
    var cookieInput by remember { mutableStateOf("") }
    var cookieImporting by remember { mutableStateOf(false) }

    var restoreMessage by remember { mutableStateOf<String?>(null) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { BackupHelper.exportTo(context, uri) }.getOrDefault(false)
            }
            android.widget.Toast.makeText(
                context,
                if (ok) "备份成功，已保存到所选位置" else "备份失败，请重试",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupHelper.importFrom(context, uri) }
            }
            result.onSuccess { restoreMessage = it.message }
                .onFailure {
                    android.widget.Toast.makeText(
                        context,
                        "恢复失败：${it.message ?: "备份文件无效"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 88.dp)
    ) {
        // 顶部返回栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                style = MiuixTheme.textStyles.title1,
                modifier = Modifier.clickable(onClick = onBack)
            )
            Spacer(modifier = Modifier.height(0.dp))
            Text(
                text = "更多",
                style = MiuixTheme.textStyles.title1,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        // ---------- 账号与设备 ----------
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                MoreItem("手动导入 Cookie", "粘贴完整 Cookie 高级导入") {
                    cookieInput = ""
                    showCookieDialog = true
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("设备指纹", style = MiuixTheme.textStyles.title3)
                        Text(
                            if (bound) "已绑定：" + Prefs.boundDeviceId(context).let {
                                if (it.length > 8) it.take(4) + "****" + it.takeLast(4) else it
                            } else "未绑定抓包设备",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("官方设备指纹", style = MiuixTheme.textStyles.title3)
                        Text(
                            if (autoFp.isBlank()) {
                                "未生成；查询战绩前会自动尝试生成"
                            } else {
                                "已生成：" + autoFp.take(6) + "…" + autoFp.takeLast(4) +
                                    if (bound) "\n已绑定抓包设备，绑定值优先" else ""
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                    TextButton(
                        text = when {
                            generatingFp -> "生成中…"
                            autoFp.isBlank() -> "生成"
                            else -> "刷新"
                        },
                        onClick = {
                            if (!generatingFp) {
                                generatingFp = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { MicaApi.generateDeviceFp(context, force = true) }
                                    }
                                    generatingFp = false
                                    result.onSuccess {
                                        autoFp = it
                                        android.widget.Toast.makeText(
                                            context, "设备指纹已生成并保存",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }.onFailure {
                                        android.widget.Toast.makeText(
                                            context,
                                            "生成失败：${it.message ?: "网络错误"}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                }
                TextField(
                    value = deviceInput,
                    onValueChange = { deviceInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    label = "格式：deviceId:deviceFp",
                    useLabelAsPlaceholder = true,
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(text = "保存", onClick = {
                        val parts = deviceInput.trim().split(":", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            Prefs.setBoundDevice(context, parts[0].trim(), parts[1].trim())
                            bound = true
                            android.widget.Toast.makeText(context, "设备指纹已保存", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, "格式应为 deviceId:deviceFp", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                    TextButton(text = "清除", onClick = {
                        Prefs.clearBoundDevice(context)
                        deviceInput = ""
                        bound = false
                        android.widget.Toast.makeText(context, "已清除绑定", android.widget.Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---------- 数据管理 ----------
        AppCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                MoreItem("清除缓存", "清理图片与临时缓存") {
                    com.mh.starrailcheckin.ui.profile.EnkaImageLoader.clearCache(context)
                    android.widget.Toast.makeText(context, "缓存已清除", android.widget.Toast.LENGTH_SHORT).show()
                }
                MoreItem("导出运行日志", "分享本地运行日志便于反馈排查") {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { LogHelper.collect(context) }
                        runCatching {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "导出运行日志"))
                        }.onFailure {
                            android.widget.Toast.makeText(context, "日志导出失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MoreItem("备份数据", "导出设置、Cookie、多账号与抽卡记录") {
                    backupExportLauncher.launch("starrail_backup_${System.currentTimeMillis()}.zip")
                }
                MoreItem("恢复数据", "从 zip 备份恢复设置与抽卡记录") {
                    backupImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            }
        }
    }

    // Cookie 导入对话框（Miuix OverlayDialog）
    if (showCookieDialog) {
        OverlayDialog(
            show = true,
            title = "手动导入 Cookie",
            onDismissRequest = { showCookieDialog = false },
            content = {
                TextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = "请粘贴 Cookie",
                    // 固定高度，避免 Cookie 过长把弹窗撑高、挤掉保存按钮
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                DialogButtonRow(
                    confirmText = if (cookieImporting) "导入中…" else "保存",
                    confirmEnabled = !cookieImporting,
                    onConfirm = {
                        scope.launch {
                            cookieImporting = true
                            val (msg, ok) = withContext(Dispatchers.IO) {
                                MicaApi.importRawCookie(context, cookieInput)
                            }
                            cookieImporting = false
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            if (ok) { cookieInput = ""; showCookieDialog = false }
                        }
                    },
                    onDismiss = { showCookieDialog = false }
                )
            }
        )
    }

    restoreMessage?.let { msg ->
        OverlayDialog(
            show = true,
            title = "恢复完成",
            onDismissRequest = { restoreMessage = null },
            content = {
                Text(
                    text = "$msg。\n\n设置与账号数据已更新，建议立即重启应用使其全部生效。",
                    style = MiuixTheme.textStyles.body2
                )
                DialogButtonRow(
                    confirmText = "立即重启",
                    dismissText = "稍后",
                    onConfirm = {
                        restoreMessage = null
                        val intent = Intent(context, com.mh.starrailcheckin.WelcomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        context.startActivity(intent)
                    },
                    onDismiss = { restoreMessage = null }
                )
            }
        )
    }
}

@Composable
private fun MoreItem(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MiuixTheme.textStyles.title3)
        Text(value, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
    }
}
