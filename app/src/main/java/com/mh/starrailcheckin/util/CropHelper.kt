package com.mh.starrailcheckin.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import com.mh.starrailcheckin.CropActivity
import com.yalantis.ucrop.UCrop
import java.io.File

/**
 * 统一图片裁剪封装（uCrop）：
 * - 头像：固定 1:1
 * - 打卡弹窗卡片背景：固定扁比例（宽 > 高）
 * 输出落在应用内部 cache 目录，避免外部存储权限问题。
 */
object CropHelper {

    const val RATIO_AVATAR_X = 1f
    const val RATIO_AVATAR_Y = 1f
    // 扁卡片宽高比，接近打卡弹窗卡片观感
    const val RATIO_CARD_X = 16f
    const val RATIO_CARD_Y = 10f

    fun destinationFile(ctx: Context, tag: String): File =
        File(ctx.cacheDir, "ucrop_${tag}_${System.currentTimeMillis()}.jpg")

    fun buildIntent(
        ctx: Context, src: Uri, dest: File, ratioX: Float, ratioY: Float
    ): Intent {
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setToolbarTitle("裁剪图片")
            setToolbarColor(0xFF1A1A38.toInt())
            setStatusBarColor(0xFF14142E.toInt())
            setRootViewBackgroundColor(0xFF14142E.toInt())
            setToolbarWidgetColor(0xFFF0E68C.toInt())
            setActiveControlsWidgetColor(0xFFC9A84C.toInt())
            setCircleDimmedLayer(false)
            setShowCropGrid(false)
            setFreeStyleCropEnabled(false)
        }
        val intent = UCrop.of(src, Uri.fromFile(dest))
            .withAspectRatio(ratioX, ratioY)
            .withMaxResultSize(1280, 1280)
            .withOptions(options)
            .getIntent(ctx)
        // 改用带状态栏避让的子类，避免工具栏标题/按钮被系统状态栏压住。
        intent.setClass(ctx, CropActivity::class.java)
        return intent
    }

    /** 从裁剪结果 Intent 取出输出文件 Uri；取消/失败返回 null */
    fun outputUri(data: Intent?): Uri? = data?.let { UCrop.getOutput(it) }
}
