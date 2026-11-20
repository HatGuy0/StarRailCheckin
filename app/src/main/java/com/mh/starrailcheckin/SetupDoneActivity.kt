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
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SetupDoneActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            StarRailCheckinTheme {
                SetupDoneScreen(
                    onStart = {
                        // 标记引导完成（apply 异步落盘，不阻塞主线程）
                        Prefs.setGuideFinished(this@SetupDoneActivity, true)
                        // 跳转到主界面
                        startActivity(
                            Intent(this@SetupDoneActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun SetupDoneScreen(onStart: () -> Unit) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
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
            delay(800)
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
            // 自有角色 logo
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

            GuideTitleBlock(
                title = "设置完成！",
                subtitle = "现在你可以开始使用星铁打卡小助手了",
                modifier = Modifier.alpha(alpha.value)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 步骤指示器（第 4 步 / 共 4 步）
            GuideStepIndicator(
                total = 4,
                current = 3,
                modifier = Modifier.alpha(buttonAlpha.value)
            )

            Spacer(modifier = Modifier.height(24.dp))

            GuidePrimaryButton(
                text = "开始使用",
                onClick = onStart,
                modifier = Modifier.alpha(buttonAlpha.value)
            )
        }
    }
}
