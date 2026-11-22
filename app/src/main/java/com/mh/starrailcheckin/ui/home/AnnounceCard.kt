package com.mh.starrailcheckin.ui.home

import android.content.Context
import android.content.Intent
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mh.starrailcheckin.network.HttpClientProvider
import com.mh.starrailcheckin.ui.components.AppCard
import com.mh.starrailcheckin.ui.profile.EnkaImageLoader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val NEWS_LIST_API =
    "${com.mh.starrailcheckin.network.ApiHosts.BBS_API_STATIC_MIYOUSHE}/painter/wapi/getNewsList?client_type=4&gids=6&last_id=&page_size=20"

/** 资讯分类：type=1 官方公告 / 版本说明，type=3 活动与资讯速递 */
private val NEWS_TABS = listOf(1 to "公告", 3 to "资讯")

private data class NewsItem(
    val postId: String,
    val subject: String,
    val createdAt: Long,
    val cover: String
)

/**
 * 首页最新资讯卡片：数据源为米游社星铁官方板块（公告 type=1 / 资讯 type=3）。
 * 先渲染本地缓存，联网刷新；封面复用 EnkaImageLoader 缓存链路；点击进入资讯详情页。
 */
@Composable
fun AnnounceCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabType by remember { mutableIntStateOf(1) }
    val bundles = remember { mutableStateOf<Map<Int, List<NewsItem>>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }

    fun loadNews(type: Int) {
        scope.launch {
            refreshing = true
            val sp = context.getSharedPreferences("mys_news", Context.MODE_PRIVATE)
            sp.getString("news_$type", null)?.let { cached ->
                parseNewsList(cached)?.let { list ->
                    bundles.value = bundles.value + (type to list)
                }
            }
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    val client = HttpClientProvider.default
                    val req = Request.Builder()
                        .url("$NEWS_LIST_API&type=$type")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/108 Mobile Safari/537.36")
                        .header("Referer", "${com.mh.starrailcheckin.network.ApiHosts.WWW_MIYOUSHE}/")
                        .build()
                    client.newCall(req).execute().use { it.body?.string().orEmpty() }
                }.getOrNull()
            }
            if (!body.isNullOrBlank()) {
                sp.edit().putString("news_$type", body).apply()
                parseNewsList(body)?.let { list ->
                    bundles.value = bundles.value + (type to list)
                }
            }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { loadNews(tabType) }

    val items = bundles.value[tabType].orEmpty()

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("最新资讯", style = MiuixTheme.textStyles.title1)
                Text(
                    text = if (refreshing) "刷新中…" else "刷新",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !refreshing) { loadNews(tabType) }
                )
            }

            // 分类切换：公告 / 资讯
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NEWS_TABS.forEach { (type, label) ->
                    val selected = type == tabType
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = if (selected) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.clickable {
                            if (tabType != type) {
                                tabType = type
                                loadNews(type)
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                Text(
                    text = if (refreshing) "加载中…" else "暂无资讯",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openDetail(context, item) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.cover.isNotBlank()) {
                            NewsCoverImage(
                                url = item.cover,
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.subject,
                                style = MiuixTheme.textStyles.body2,
                                maxLines = 2
                            )
                            Text(
                                formatDate(item.createdAt),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 资讯封面：复用 EnkaImageLoader 内存/磁盘缓存与网络下载。 */
@Composable
private fun NewsCoverImage(url: String, modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
            }
        },
        update = { iv -> EnkaImageLoader.loadUrl(context, url, iv, 512, quality = 90) },
        modifier = modifier
    )
}

private fun openDetail(ctx: Context, item: NewsItem) {
    runCatching {
        val intent = Intent(ctx, AnnounceDetailActivity::class.java).apply {
            putExtra("n_post_id", item.postId)
            putExtra("n_title", item.subject)
            putExtra("n_cover", item.cover)
        }
        ctx.startActivity(intent)
    }
}

private fun parseNewsList(body: String): List<NewsItem>? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return null
    val list = ArrayList<NewsItem>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val p = obj.optJSONObject("post") ?: continue
        val postId = p.optString("post_id")
        if (postId.isBlank()) continue
        val subject = p.optString("subject")
        val createdAt = p.optLong("created_at")

        val coverUrl = obj.optJSONObject("cover")?.optString("url").orEmpty()
        val imageList = obj.optJSONArray("image_list")
        val firstImage = imageList?.optJSONObject(0)?.optString("url").orEmpty()
        val postImages = p.optJSONArray("images")
        val firstPostImage = if (postImages != null && postImages.length() > 0)
            postImages.optString(0) else ""
        val cover = coverUrl.ifBlank { firstImage }.ifBlank { firstPostImage }

        list.add(NewsItem(postId, subject, createdAt, cover))
    }
    return list
}

private fun formatDate(ts: Long): String {
    if (ts <= 0) return ""
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts * 1000))
    }.getOrDefault("")
}
