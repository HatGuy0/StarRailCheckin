package com.mh.starrailcheckin.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader

/**
 * 全屏高清大图查看器：用于资讯详情（WebView）内图片点击放大查看。
 * 深色底、原图高清加载，左上角返回关闭。
 */
class ImageViewerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "img_url"

        fun start(ctx: Context, url: String) {
            if (url.isBlank()) return
            val intent = Intent(ctx, ImageViewerActivity::class.java).putExtra(EXTRA_URL, url)
            if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(image)

        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            val lp = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START)
            lp.leftMargin = dp(12)
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnClickListener { finish() }
        }
        root.addView(back)

        setContentView(root)
        EnkaImageLoader.loadHighRes(this, url, image)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
