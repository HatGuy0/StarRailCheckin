package com.mh.starrailcheckin

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.ui.guide.GuideBackground
import com.mh.starrailcheckin.ui.guide.GuidePrimaryButton
import com.mh.starrailcheckin.ui.guide.GuideStepIndicator
import com.mh.starrailcheckin.ui.guide.GuideTitleBlock
import com.mh.starrailcheckin.ui.guide.GuidePrimaryText
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme

class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            StarRailCheckinTheme {
                GuideScreen(
                    onNext = {
                        startActivity(Intent(this, AgreementActivity::class.java))
                    }
                )
            }
        }
    }
}

@Composable
fun GuideScreen(onNext: () -> Unit) {
    val context = LocalContext.current
    var usageOn by remember { mutableStateOf(false) }
    var accessibilityOn by remember { mutableStateOf(false) }
    var autostartOn by remember { mutableStateOf(false) }
    var notifyOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var batteryOn by remember { mutableStateOf(false) }

    // 检查权限状态的函数
    fun checkPermissions() {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        usageOn = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
        notifyOn = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        overlayOn = android.provider.Settings.canDrawOverlays(context)
        batteryOn = context.getSystemService(android.os.PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        // 无障碍权限：读取系统已启用服务列表判断本应用服务是否在列
        accessibilityOn = com.mh.starrailcheckin.util.PermissionHelper.isAccessibilityEnabled(
            context, com.mh.starrailcheckin.service.KeepAliveAccessibilityService::class.java
        )
        // 自启动无API检测，默认false
        autostartOn = false
    }

    // 初始化时检查一次
    LaunchedEffect(Unit) {
        checkPermissions()
    }

    // 从其他页面回来时重新检查（监听生命周期）
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        checkPermissions()
    }

    GuideBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // 标题
            GuideTitleBlock(
                title = "权限设置",
                subtitle = "为了正常使用打卡提醒功能，请开启以下权限",
                align = TextAlign.Start,
                centered = false
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 权限列表
            PermissionItem(
                iconRes = R.drawable.ic_perm_usage,
                title = "应用使用记录",
                description = "用于判断今日是否已打卡",
                enabled = usageOn,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                iconRes = R.drawable.ic_perm_notify,
                title = "通知权限",
                description = "用于发送打卡提醒通知",
                enabled = notifyOn,
                onClick = {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        (context as? android.app.Activity) ?: return@PermissionItem,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        100
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                iconRes = R.drawable.ic_perm_overlay,
                title = "悬浮窗权限",
                description = "用于在其他应用上方显示提醒弹窗",
                enabled = overlayOn,
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                iconRes = R.drawable.ic_perm_battery,
                title = "电池优化",
                description = "关闭电池优化，避免后台被杀死",
                enabled = batteryOn,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                iconRes = R.drawable.ic_perm_accessibility,
                title = "无障碍权限",
                description = "用于辅助保活和后台运行",
                enabled = accessibilityOn,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionItem(
                iconRes = R.drawable.ic_perm_autostart,
                title = "自启动权限",
                description = "允许应用自启动，避免被系统杀死",
                enabled = autostartOn,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${context.packageName}"))
                    )
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            GuideStepIndicator(total = 4, current = 1)

            Spacer(modifier = Modifier.height(16.dp))

            // 继续按钮
            GuidePrimaryButton(
                text = "继续",
                onClick = onNext,
                enabled = usageOn
            )

            if (!usageOn) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请先开启应用使用记录权限",
                    fontSize = 12.sp,
                    color = Color(0xFFFF6B6B),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PermissionItem(
    iconRes: Int,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color.White.copy(alpha = 0.15f) else Color.White,
                    contentColor = if (enabled) Color.White.copy(alpha = 0.7f) else GuidePrimaryText
                ),
                shape = RoundedCornerShape(20.dp),
                enabled = !enabled
            ) {
                Text(if (enabled) "已开启" else "去开启", fontSize = 12.sp)
            }
        }
    }
}
