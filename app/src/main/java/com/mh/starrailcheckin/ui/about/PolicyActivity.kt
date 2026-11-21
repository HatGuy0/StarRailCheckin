package com.mh.starrailcheckin.ui.about

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mh.starrailcheckin.R
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.components.NavEventOwnerHost
import com.mh.starrailcheckin.ui.theme.DarkBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LightBackgroundGradient
import com.mh.starrailcheckin.ui.theme.LocalAppDarkTheme
import com.mh.starrailcheckin.ui.theme.StarRailCheckinTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 隐私政策 / 用户协议通用二级页。 */
class PolicyActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TYPE = "policy_type"
        const val TYPE_PRIVACY = "privacy"
        const val TYPE_AGREEMENT = "agreement"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_PRIVACY
        setContent {
            NavEventOwnerHost {
                StarRailCheckinTheme() {
                    Scaffold(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
                    ) {
                        PolicyScreen(type = type, onBack = { finish() })
                    }
                }
            }
        }
    }
}

private data class PolicySection(val title: String, val paragraphs: List<String>)

private val privacySections = listOf(
    PolicySection(
        "引言",
        listOf(
            "星铁打卡小助手（以下简称“本应用”）是一款《崩坏：星穹铁道》第三方辅助工具。开发者尊重并重视你的个人隐私，本政策说明本应用在打卡提醒、战绩查询等过程中对信息的收集、存储与使用方式。",
            "本政策适用于本应用的全部功能。你在使用本应用前，请仔细阅读并理解本政策全部内容；开始使用即视为你已同意本政策。"
        )
    ),
    PolicySection(
        "一、本应用涉及的信息",
        listOf(
            "1. 登录凭证（Cookie）：用于向米游社官方接口请求战绩、开拓力、派遣、签到等数据，仅在你登录并绑定角色后保存。",
            "2. 角色信息：UID、区服、昵称、头像，用于在本地展示你的账号与角色面板。",
            "3. 抽卡记录：由你手动导入或在本应用内查看时生成，仅保存在本机，用于保底与花费统计。",
            "4. 使用情况与设备信息：应用使用时长、今日是否打开游戏、设备指纹等，用于打卡状态判定与接口请求校验。",
            "5. 本地设置：提醒时段、主题、备份数据、多账号列表等应用配置。"
        )
    ),
    PolicySection(
        "二、信息存储与传输",
        listOf(
            "1. 上述信息默认仅存储在你的手机本地（应用私有目录与本地配置），不会上传到开发者搭建的服务器。",
            "2. 本应用在获取游戏数据时，会直接与米哈游 / 米游社官方接口通信；该过程遵循官方接口的相关规则，本应用不中转、不留存你的请求内容到第三方服务器。",
            "3. 备份与恢复功能由你主动触发，导出的压缩包保存在你选择的本地位置或系统分享目录，由你自行保管。"
        )
    ),
    PolicySection(
        "三、系统权限及用途",
        listOf(
            "1. 通知权限：用于在设定的提醒时段发送打卡与体力、派遣通知。",
            "2. 悬浮窗权限：用于在其他应用上方显示提醒弹窗。",
            "3. 应用使用记录权限：用于判断今日是否已打开游戏、辅助打卡状态判定。",
            "4. 电池优化、自启动、无障碍权限：用于改善后台保活与提醒送达，避免应用被系统过早结束。",
            "5. 存储 / 文件权限：用于图片缓存、日志导出与备份恢复。"
        )
    ),
    PolicySection(
        "四、Cookie 与数据安全",
        listOf(
            "1. 你的 Cookie 仅用于访问官方接口，不会在应用内明文展示给第三方，也不会用于本应用以外的用途。",
            "2. 你可以在「更多」页手动清除缓存、导出或清除本地数据；卸载本应用后，本地保存的账号与缓存数据将随之删除。",
            "3. 请不要在公共设备上长期保留登录凭证，退出或卸载后及时清理。"
        )
    ),
    PolicySection(
        "五、第三方开源组件",
        listOf(
            "本应用使用了若干开源组件，这些组件遵循各自的开源许可证。完整清单与项目链接可在「关于 → 引用」页面查看。"
        )
    ),
    PolicySection(
        "六、联系我们",
        listOf(
            "如对本政策或个人信息处理有疑问、建议或投诉，可加入作者 QQ 群反馈：981703967。"
        )
    ),
    PolicySection(
        "七、生效与修订",
        listOf(
            "本政策自 2026 年 12 月 5 日起生效。应用版本更新后，本政策可能随之修订，修订内容将在本页面公布；若你继续使用更新后的应用，即视为接受修订后的政策。"
        )
    )
)

private val agreementSections = listOf(
    PolicySection(
        "一、协议接受",
        listOf(
            "欢迎使用星铁打卡小助手。你下载、安装或使用本应用，即表示你已阅读、理解并同意本《用户协议》及《隐私政策》的全部内容；如不同意本协议任何条款，请勿使用本应用。"
        )
    ),
    PolicySection(
        "二、应用性质",
        listOf(
            "1. 本应用为第三方非官方辅助工具，与上海米哈游网络科技股份有限公司、《崩坏：星穹铁道》官方无任何隶属或授权关系。",
            "2. 应用中涉及的游戏名称、角色名称、图标、美术素材等知识产权归相应权利人所有。"
        )
    ),
    PolicySection(
        "三、用途限制",
        listOf(
            "1. 本应用仅供个人学习交流与日常打卡辅助使用，禁止用于任何商业用途。",
            "2. 禁止利用本应用进行批量刷取、自动化破坏游戏公平性、违反游戏规则或法律法规的行为。",
            "3. 不得对本应用进行反编译、二次打包、去除版权声明或用于其他侵权用途。"
        )
    ),
    PolicySection(
        "四、账号与风险自担",
        listOf(
            "1. 你应妥善保管自己的游戏账号与 Cookie，因账号、凭证保管不当或主动授权他人使用所造成的损失，由你自行承担。",
            "2. 辅助工具的使用存在被官方风控限制的可能性，因使用本应用导致的账号异常、封禁等后果，由使用者自行承担，开发者不承担相关责任。"
        )
    ),
    PolicySection(
        "五、服务说明",
        listOf(
            "1. 打卡提醒、战绩查询、体力与派遣提醒等功能依赖官方接口、系统权限与网络环境，本应用不保证所有功能在任何设备、任何网络下都能 100% 成功送达或返回数据。",
            "2. 应用按“现状”提供，开发者可能根据使用情况调整、增加或停止部分功能。"
        )
    ),
    PolicySection(
        "六、免责声明",
        listOf(
            "因网络故障、系统权限限制、官方接口变更或风控、设备兼容性等非开发者可控原因导致的提醒延迟、漏提醒、数据查询失败等，开发者不承担相应责任；本应用不构成对任何游戏内结果的承诺或保证。"
        )
    ),
    PolicySection(
        "七、协议变更与终止",
        listOf(
            "开发者有权根据应用发展修订本协议，修订后的协议将在本页面公布。若你不同意修订内容，应停止使用本应用；继续使用即视为接受修订后的协议。"
        )
    ),
    PolicySection(
        "八、联系我们",
        listOf(
            "如对本协议有疑问，可加入作者 QQ 群反馈：981703967。本协议自 2026 年 12 月 5 日起生效。"
        )
    )
)

@Composable
private fun PolicyScreen(type: String, onBack: () -> Unit) {
    val dark = LocalAppDarkTheme.current
    val isPrivacy = type == PolicyActivity.TYPE_PRIVACY
    val title = if (isPrivacy) "隐私政策" else "用户协议"
    val sections = if (isPrivacy) privacySections else agreementSections

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(if (dark) DarkBackgroundGradient else LightBackgroundGradient))
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = title,
            color = Color.Transparent,
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

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(modifier = Modifier.height(16.dp))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                sections.forEach { section ->
                    Text(
                        text = section.title,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    section.paragraphs.forEach { p ->
                        Text(
                            text = p,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = "生效日期：2026 年 12 月 5 日",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
        }
        }
    }
}
