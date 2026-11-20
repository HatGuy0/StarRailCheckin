package com.mh.starrailcheckin

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.ui.guide.GuideBackground
import com.mh.starrailcheckin.ui.guide.GuideIconCircle
import com.mh.starrailcheckin.ui.guide.GuidePrimaryButton
import com.mh.starrailcheckin.ui.guide.GuideStepIndicator
import com.mh.starrailcheckin.ui.guide.GuideTitleBlock
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        // 检查是否已经完成引导，如果完成了直接跳转到主界面
        if (com.mh.starrailcheckin.util.Prefs.isGuideFinished(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContent {
            StarRailCheckinTheme {
                WelcomeScreen(
                    onNext = {
                        startActivity(Intent(this, GuideActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
fun WelcomeScreen(onNext: () -> Unit) {
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val buttonAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            delay(200)
            scale.animateTo(1f, animationSpec = tween(600))
        }
        launch {
            delay(200)
            alpha.animateTo(1f, animationSpec = tween(600))
        }
        launch {
            delay(600)
            textAlpha.animateTo(1f, animationSpec = tween(400))
        }
        launch {
            delay(900)
            buttonAlpha.animateTo(1f, animationSpec = tween(300))
        }
    }

    GuideBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 应用图标（自有角色 logo，透明底）
            GuideIconCircle(
                modifier = Modifier
                    .scale(scale.value)
                    .alpha(alpha.value)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = "应用图标",
                    modifier = Modifier.size(88.dp),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 应用名称 + 副标题
            GuideTitleBlock(
                title = "星铁打卡小助手",
                subtitle = "每日打卡提醒，轻松游玩不遗漏",
                modifier = Modifier.alpha(textAlpha.value)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 步骤指示器（第 1 步 / 共 4 步）
            GuideStepIndicator(
                total = 4,
                current = 0,
                modifier = Modifier.alpha(buttonAlpha.value)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 开始按钮
            GuidePrimaryButton(
                text = "开始使用",
                onClick = onNext,
                modifier = Modifier.alpha(buttonAlpha.value)
            )
        }
    }
}
