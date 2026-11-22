package com.mh.starrailcheckin.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.network.AppUpdateDownloader
import com.mh.starrailcheckin.network.RemoteRelease
import com.mh.starrailcheckin.network.UpdateDownloadResult
import com.mh.starrailcheckin.util.AppInstaller
import com.mh.starrailcheckin.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 更新弹窗下载/安装状态。 */
private sealed interface UpdateDlState {
    /** 待下载。 */
    object Idle : UpdateDlState
    /** 下载中，percent 0..100（未知 -1）。 */
    data class Downloading(val percent: Int) : UpdateDlState
    /** 下载/安装失败。 */
    data class Failed(val message: String) : UpdateDlState
    /** 待用户授予「安装未知应用」权限。 */
    data class NeedsPermission(val file: File) : UpdateDlState
}

/**
 * 已确认有新版本时的更新弹窗（自带弹窗壳）。
 *
 * 统一提供「关闭 / 浏览器下载 / 下载安装」三按钮，内置下载、安装权限申请与系统安装拉起；
 * 首页启动自动检查弹窗与关于页「检查更新」弹窗共用，交互保持一致。
 *
 * @param title 弹窗标题。
 * @param release 远端最新 Release 信息。
 * @param onDismiss 关闭弹窗回调。
 */
@Composable
fun UpdateReleaseDialog(
    title: String,
    release: RemoteRelease,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        content = { UpdateReleaseContent(release = release, onDismiss = onDismiss) }
    )
}

/**
 * 更新弹窗纯内容（不含弹窗壳）：供已自带 [OverlayDialog] 的页面内嵌复用。
 * 内置下载、安装权限申请与系统安装拉起，按钮与 [UpdateReleaseDialog] 一致。
 */
@Composable
fun UpdateReleaseContent(
    release: RemoteRelease,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dlState by remember { mutableStateOf<UpdateDlState>(UpdateDlState.Idle) }
    var pendingApk by remember { mutableStateOf<File?>(null) }

    // 从「安装未知应用」设置页返回后：已授权则直接拉起安装，否则停留在待授权态。
    val installSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingApk
        when {
            apk != null && PermissionHelper.canInstallPackages(context) ->
                AppInstaller.installApk(context, apk)
            apk != null -> dlState = UpdateDlState.NeedsPermission(apk)
        }
    }

    fun openRelease() {
        val url = release.apkUrl?.takeIf { it.isNotBlank() } ?: release.releaseUrl
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    fun startDownload() {
        val url = release.apkUrl
        if (url.isNullOrBlank()) {
            dlState = UpdateDlState.Failed("未找到可下载的安装包")
            return
        }
        scope.launch {
            dlState = UpdateDlState.Downloading(0)
            val result = withContext(Dispatchers.IO) {
                AppUpdateDownloader.download(context, url, release.apkSize) { percent, _, _ ->
                    scope.launch { dlState = UpdateDlState.Downloading(percent) }
                }
            }
            when (result) {
                is UpdateDownloadResult.Success -> {
                    pendingApk = result.file
                    if (PermissionHelper.canInstallPackages(context)) {
                        AppInstaller.installApk(context, result.file)
                    } else {
                        dlState = UpdateDlState.NeedsPermission(result.file)
                        runCatching {
                            installSettingsLauncher.launch(
                                PermissionHelper.installSourcesSettingsIntent(context)
                            )
                        }.onFailure {
                            Toast.makeText(context, "无法打开安装权限设置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                is UpdateDownloadResult.Failed ->
                    dlState = UpdateDlState.Failed(result.message)
            }
        }
    }

    when (val state = dlState) {
        is UpdateDlState.Idle -> {
            Text(
                text = "发现新版本 ${release.versionName}",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold
            )
            ReleaseNotesBlock(
                notes = release.releaseNotes,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (release.apkSize > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "安装包大小：${formatApkSize(release.apkSize)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "浏览器下载", onClick = { openRelease() })
                Spacer(Modifier.width(8.dp))
                TextButton(text = "下载安装", onClick = { startDownload() })
            }
        }

        is UpdateDlState.Downloading -> {
            Text(
                text = if (state.percent >= 0) "正在下载… ${state.percent}%" else "正在下载…",
                style = MiuixTheme.textStyles.body2
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (state.percent >= 0) state.percent / 100f else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        is UpdateDlState.Failed -> {
            Text(
                text = "下载失败：${state.message}",
                style = MiuixTheme.textStyles.body2
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "重试", onClick = { startDownload() })
            }
        }

        is UpdateDlState.NeedsPermission -> {
            Text(
                text = "安装包已下载完成。\n\n请在系统设置中允许「星铁打卡小助手」安装未知应用，返回后将自动开始安装。",
                style = MiuixTheme.textStyles.body2
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                TextButton(text = "去开启权限", onClick = {
                    runCatching {
                        installSettingsLauncher.launch(
                            PermissionHelper.installSourcesSettingsIntent(context)
                        )
                    }
                })
            }
        }
    }
}

/** 字节数格式化为可读的安装包大小。 */
private fun formatApkSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
