package com.mh.starrailcheckin.ui.gacha

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import com.mh.starrailcheckin.ui.profile.EnkaNameMap
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val Gold = Color(0xFFF5B041)
private val Purple4 = Color(0xFFB388FF)
private val LoseRed = Color(0xFFE53935)
private val WinGreen = Color(0xFF43A047)

/** 顶部单格指标卡。 */
@Composable
internal fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

/** 五星出金行。 */
@Composable
internal fun FiveStarRow(item: JSONObject, pity: Int, pool: Int, stats: GachaStats) {
    val name = item.optString("name", "未知")
    val time = item.optString("time").take(16)
    val isChar = item.optString("itemType") == "角色" || pool == 0
    val zhai = pool == 0 && stats.isStandard(name)
    val zhaiText = if (pool == 0) (if (zhai) "歪" else "未歪") else ""

    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarImage(name = name, isChar = isChar, modifier = Modifier.size(46.dp).padding(end = 10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Gold)
                    Text(
                        "$time  ·  ${stats.poolName(pool)}  ·  第 $pity 抽出金",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                if (zhaiText.isNotEmpty()) {
                    Text(
                        zhaiText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (zhai) LoseRed else WinGreen)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // 保底进度条
            val cap = if (pool == 1) 80 else 90
            val fillW = pity.coerceIn(1, cap).toFloat()
            val emptyW = (cap - pity.coerceIn(1, cap)).coerceAtLeast(0).toFloat()
            val progressColor = if (zhai) LoseRed else WinGreen
            val barBg = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barBg)
            ) {
                Box(
                    modifier = Modifier
                        .weight(fillW)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(progressColor)
                )
                if (emptyW > 0) Box(modifier = Modifier.weight(emptyW).height(6.dp))
            }

            Text(
                "$pity 抽出金",
                fontSize = 10.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** 四星简化行。 */
@Composable
internal fun FourStarRow(item: JSONObject, pool: Int, stats: GachaStats) {
    val name = item.optString("name", "未知")
    val time = item.optString("time").take(16)

    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 10.dp)
                    .clip(CircleShape)
                    .background(Purple4.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Text("4", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Purple4, textAlign = TextAlign.Center)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                Text("$time  ·  ${stats.poolName(pool)}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** 顶部「当前已垫 N 抽」占位行。 */
@Composable
internal fun CarryRow(carry: Int) {
    AppCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .padding(end = 10.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Text("?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text("已垫 $carry 抽", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WinGreen)
        }
    }
}

@Composable
private fun AvatarImage(name: String, isChar: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        },
        update = { iv ->
            if (isChar) EnkaImageLoader.loadAvatar(iv.context, EnkaNameMap.charIdForName(name), iv)
            else EnkaImageLoader.loadLightCone(iv.context, EnkaNameMap.lcIdForName(name), iv)
        },
        modifier = modifier.clip(CircleShape)
    )
}
