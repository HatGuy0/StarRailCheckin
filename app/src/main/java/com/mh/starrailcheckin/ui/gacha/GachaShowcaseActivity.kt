package com.mh.starrailcheckin.ui.gacha

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Color

/**
 * 跃迁抽卡统计页（D-3 Compose 化）。
 * 业务口径与旧 View 版一致：卡池分类 / 保底水位 / 歪率 / 指标 / 明细 / 保底详情 / 累计统计 / 长图分享。
 * 统计计算下沉到 [GachaStats]，列表项在 [GachaRows]，规划弹窗在 [GachaPlanDialog]，本类只做宿主与分享。
 * 配色走主题色板，跟随系统深浅。
 */
class GachaShowcaseActivity : AppCompatActivity() {

    private var headerUid = ""
    private var headerRegion = ""
    private var recordsJson = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raw = intent.getStringExtra("records_json").orEmpty()
        recordsJson = raw
        if (raw.isBlank()) {
            finish()
            return
        }
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "抽卡记录数据已损坏，无法查看", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val records = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { records.add(it) }
        headerUid = intent.getStringExtra("uid").orEmpty()
        headerRegion = intent.getStringExtra("region").orEmpty()

        EnkaNameMap.load(applicationContext)
        val stats = GachaStats(records)

        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme() {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        GachaShowcaseScreen(
                            stats = stats,
                            headerUid = headerUid,
                            headerRegion = headerRegion,
                            onBack = { finish() },
                            onShare = { shareLongImage() }
                        )
                    }
                }
            }
        }
    }

    /** 生成 1080 宽统计长图并调起系统分享（纯 Canvas 位图与 View 树解耦，口径同旧版）。 */
    private fun shareLongImage() {
        Toast.makeText(this, "正在生成长图…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val bmp = GachaShareImage.create(recordsJson, headerUid, headerRegion)
                val dir = File(getExternalFilesDir("exports"), "gacha").apply { mkdirs() }
                val file = File(dir, "gacha_share_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                runOnUiThread {
                    val uri = FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "分享抽卡统计长图"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "生成长图失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
