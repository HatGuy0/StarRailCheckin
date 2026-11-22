package com.mh.starrailcheckin

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.ui.about.PolicyActivity
import com.mh.starrailcheckin.ui.guide.GuideBackground
import com.mh.starrailcheckin.ui.guide.GuidePrimaryButton
import com.mh.starrailcheckin.ui.guide.GuidePrimaryText
import com.mh.starrailcheckin.ui.guide.GuideStepIndicator
import com.mh.starrailcheckin.ui.guide.GuideTitleBlock
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme

class AgreementActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            StarRailCheckinTheme {
                AgreementScreen(
                    onNext = {
                        startActivity(Intent(this, SetupDoneActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
fun AgreementScreen(onNext: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun openPolicy(type: String) {
        context.startActivity(
            Intent(context, PolicyActivity::class.java)
                .putExtra(PolicyActivity.EXTRA_TYPE, type)
        )
    }

    GuideBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // 标题
            GuideTitleBlock(
                title = "用户协议与隐私声明",
                align = TextAlign.Start,
                centered = false
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 协议内容
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = "用户协议",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.clickable { openPolicy(PolicyActivity.TYPE_AGREEMENT) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "欢迎使用星铁打卡小助手！本应用旨在帮助崩坏：星穹铁道玩家更方便地进行每日打卡和游戏管理。\n\n" +
                            "1. 本应用为第三方辅助工具，与米哈游公司无关。\n\n" +
                            "2. 本应用不会上传你的游戏账号信息到任何服务器。\n\n" +
                            "3. 你需要自行承担使用本应用的风险。\n\n" +
                            "4. 本应用仅供学习交流使用，禁止用于商业用途。",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "隐私政策",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.clickable { openPolicy(PolicyActivity.TYPE_PRIVACY) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "1. 本应用不会收集任何个人隐私信息。\n\n" +
                            "2. 你的游戏 Cookie 仅保存在本地，不会上传。\n\n" +
                            "3. 我们尊重你的隐私，不会向第三方共享你的任何数据。",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f),
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 勾选同意
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = { agreed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color.White,
                        checkmarkColor = GuidePrimaryText,
                        uncheckedColor = Color.White.copy(alpha = 0.5f)
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                val agreeText = buildAnnotatedString {
                    append("我已阅读并同意")
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "policy",
                            linkInteractionListener = { openPolicy(PolicyActivity.TYPE_AGREEMENT) }
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        ) { append("《用户协议》") }
                    }
                    append("与")
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "policy",
                            linkInteractionListener = { openPolicy(PolicyActivity.TYPE_PRIVACY) }
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        ) { append("《隐私政策》") }
                    }
                }
                Text(
                    text = agreeText,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            GuideStepIndicator(total = 4, current = 2)

            Spacer(modifier = Modifier.height(16.dp))

            // 同意并继续按钮
            GuidePrimaryButton(
                text = "同意并继续",
                onClick = onNext,
                enabled = agreed
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
