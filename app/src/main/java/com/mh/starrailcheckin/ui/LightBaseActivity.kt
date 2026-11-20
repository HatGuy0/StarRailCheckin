package com.mh.starrailcheckin.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * 强制日间模式的二级查询页基类。
 * 不跟随系统深色模式，资源统一取浅色（day）变体，避免在深色系统下背景/卡片变黑。
 */
abstract class LightBaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
            Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
