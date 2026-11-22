package com.mh.starrailcheckin.ui.more

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Color
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * 「更多」二级页：独立 Activity，与关于 / 月历 / 战绩等二级页一致。
 * 独立跳转可保留系统转场动画，且返回时不销毁设置页组合、保留其滚动位置。
 */
class MoreActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        MoreScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}
