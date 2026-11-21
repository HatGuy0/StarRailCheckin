package com.mh.starrailcheckin.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 调起系统安装器安装本地 APK（应用内更新）。 */
object AppInstaller {

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
