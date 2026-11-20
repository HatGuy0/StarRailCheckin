package com.mh.starrailcheckin.ui.account

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mh.starrailcheckin.LoginWebViewActivity
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.LightBaseActivity
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.DialogButtonRow
import com.mh.starrailcheckin.ui.components.DialogSingleButton
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import com.mh.starrailcheckin.util.MultiAccountSigner
import com.mh.starrailcheckin.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 多账号管理：保存、切换、单独/批量签到。 */
class AccountManagerActivity : LightBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme(darkTheme = false) {
                    Scaffold(
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) {
                        AccountScreen(onBack = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var accounts by remember { mutableStateOf(emptyList<Prefs.Account>()) }
    var activeId by remember { mutableStateOf("") }
    var signingAll by remember { mutableStateOf(false) }
    var signingUid by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Prefs.Account?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        accounts = Prefs.accounts(context)
        activeId = Prefs.activeAccountId(context)
    }

    LaunchedEffect(reload) { refresh() }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh(); reload++ }

    fun signAll() {
        if (signingAll) return
        scope.launch {
            signingAll = true
            val list = accounts
            val results = withContext(Dispatchers.IO) { MultiAccountSigner.signAll(context, list) }
            signingAll = false
            resultText = if (results.isEmpty()) "没有可签到的账号"
            else results.joinToString("\n") {
                val name = it.account.nickname.ifBlank { it.account.uid.ifBlank { "未知账号" } }
                "$name：${if (it.success) "成功" else "失败"} · ${it.message}"
            }
            refresh()
        }
    }

    fun signOne(account: Prefs.Account) {
        if (signingUid != null) return
        scope.launch {
            signingUid = account.id
            val r = withContext(Dispatchers.IO) { MultiAccountSigner.signOne(context, account) }
            signingUid = null
            Toast.makeText(
                context,
                (if (r.success) "签到成功" else "签到失败") + "：" + r.message,
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = "多账号管理",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "已保存 ${accounts.size} 个账号",
                                style = MiuixTheme.textStyles.title3,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "切换后立即生效；批量签到会自动恢复当前账号",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                        if (signingAll) {
                            InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            TextButton(
                                text = "全部签到",
                                enabled = accounts.isNotEmpty(),
                                onClick = { signAll() }
                            )
                        }
                    }
                }
            }

            if (accounts.isEmpty()) {
                item {
                    AppCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("还没有保存的账号", style = MiuixTheme.textStyles.body2)
                            Text(
                                "点击下方按钮，用米游社网页登录添加账号",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }
            }

            items(accounts, key = { it.id }) { account ->
                val isActive = account.id == activeId
                val health = Prefs.cookieHealthStateFor(context, account.id)
                val (healthText, healthColor) = when (health?.status) {
                    "healthy" -> "Cookie 正常" to Color(0xFF2E7D32)
                    "invalid" -> "Cookie 可能失效" to Color(0xFFC62828)
                    "error" -> "检查失败" to Color(0xFFEF6C00)
                    else -> "未检查" to MiuixTheme.colorScheme.onSurfaceSecondary
                }
                val lastSign = Prefs.lastSignAt(context, account.id)
                val signText = if (lastSign > 0L) {
                    val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                    val hmFmt = java.text.SimpleDateFormat("HH:mm", Locale.CHINA)
                    val fullFmt = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    val sameDay = dayFmt.format(lastSign) == dayFmt.format(System.currentTimeMillis())
                    "最近签到 " + if (sameDay) hmFmt.format(lastSign) else fullFmt.format(lastSign)
                } else "暂无签到记录"
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isActive) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceVariant
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = (account.nickname.ifBlank { account.uid }).take(1)
                                        .ifBlank { "米" },
                                    color = if (isActive) Color.White
                                    else MiuixTheme.colorScheme.onSurfaceSecondary,
                                    fontSize = 18.sp,
                                    modifier = Modifier.padding(top = 9.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = account.nickname.ifBlank { "未命名账号" },
                                        style = MiuixTheme.textStyles.body1,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isActive) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "当前",
                                            fontSize = 10.sp,
                                            color = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MiuixTheme.colorScheme.primaryContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "UID ${account.uid.ifBlank { "—" }} · ${account.server.ifBlank { "未知区服" }}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = healthText,
                                        fontSize = 11.sp,
                                        color = healthColor
                                    )
                                    Text(
                                        text = " · ",
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                    Text(
                                        text = signText,
                                        fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = if (signingUid == account.id) "签到中…" else "单独签到",
                                enabled = signingUid == null,
                                onClick = { signOne(account) },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = if (isActive) "使用中" else "切换",
                                enabled = !isActive,
                                onClick = {
                                    if (Prefs.switchAccount(context, account.id)) {
                                        Toast.makeText(context, "已切换到 ${account.nickname}", Toast.LENGTH_SHORT).show()
                                        refresh()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                text = "删除",
                                onClick = { deleteTarget = account },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        loginLauncher.launch(
                            android.content.Intent(context, LoginWebViewActivity::class.java)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("添加账号（网页登录）")
                }
            }

            item {
                Text(
                    text = "提示：添加账号时请先在米游社网页完成登录，返回后会自动保存；" +
                        "删除当前账号会自动切换到下一个账号。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }

    deleteTarget?.let { target ->
        OverlayDialog(
            show = true,
            onDismissRequest = { deleteTarget = null },
            title = "删除账号",
            content = {
                Text(
                    text = "确定删除「${target.nickname.ifBlank { target.uid.ifBlank { "该账号" } }}」吗？" +
                        "删除后 Cookie 将从本机移除。",
                    style = MiuixTheme.textStyles.body2
                )
                DialogButtonRow(
                    confirmText = "删除",
                    dismissText = "取消",
                    onConfirm = {
                        Prefs.removeAccount(context, target.id)
                        deleteTarget = null
                        refresh()
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { deleteTarget = null }
                )
            }
        )
    }

    resultText?.let { text ->
        OverlayDialog(
            show = true,
            onDismissRequest = { resultText = null },
            title = "签到结果",
            content = {
                Text(text = text, style = MiuixTheme.textStyles.body2)
                DialogSingleButton(
                    text = "知道了",
                    onClick = { resultText = null }
                )
            }
        )
    }
}
