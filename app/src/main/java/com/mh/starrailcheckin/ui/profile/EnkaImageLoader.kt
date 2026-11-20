package com.mh.starrailcheckin.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片加载器（全角色内置 + 新角色云端兜底）
 *
 * 优先级：内存 → assets 内置（立绘 webp + 技能图标 webp，老角色秒开不联网）
 *       → 磁盘缓存（之前下过的新角色）
 *       → 多源网络下载（gh-proxy 国内代理优先 → jsdelivr → vizual → raw）
 *       → 下载后自动压缩 WebP 写缓存，LRU 50MB 上限
 */
object EnkaImageLoader {
    private val memCache = ConcurrentHashMap<String, Bitmap>()
    private const val BASE_JS = "https://cdn.jsdelivr.net/gh/Mar-7th/StarRailRes@master"
    private const val BASE_VIZ = "https://vizualabstract.github.io/StarRailStaticAPI/assets"
    private const val RAW = "https://raw.githubusercontent.com/Mar-7th/StarRailRes/master"
    private val GH_PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
        "https://gh.llkk.cc/"
    )
    private const val CACHE_LIMIT_BYTES = 50L * 1024 * 1024

    /** 角色头像（内置98张 PNG） */
    fun loadAvatar(ctx: Context, aid: Int, iv: ImageView) {
        val key = "avatar_$aid"
        if (tryAssets(ctx, listOf("avatar/$aid.webp", "avatar/$aid.png"), key, iv)) return
        loadFromNetwork(ctx, key, fullUrls("icon/character/$aid.png"), iv, maxEdge = 256)
    }

    /** 光锥（内置 PNG） */
    fun loadLightCone(ctx: Context, lid: Int, iv: ImageView) {
        val key = "lc_$lid"
        if (tryAssets(ctx, listOf("lightcone/$lid.webp", "lightcone/$lid.png"), key, iv)) return
        loadFromNetwork(ctx, key, fullUrls("icon/light_cone/$lid.png"), iv, maxEdge = 256)
    }

    /** 遗器（内置 PNG） */
    fun loadRelic(ctx: Context, setId: Int, type: Int, iv: ImageView) {
        val suffix = when (type) {
            1 -> "_0"; 2 -> "_1"; 3 -> "_2"; 4 -> "_3"; 5 -> "_0"; 6 -> "_1"; else -> "_0"
        }
        val key = "relic_${setId}_$suffix"
        if (tryAssets(ctx, listOf("relic/$setId$suffix.webp", "relic/$setId$suffix.png"), key, iv)) return
        loadFromNetwork(ctx, key, fullUrls("icon/relic/$setId$suffix.png"), iv, maxEdge = 256)
    }

    /** 角色立绘：优先 assets webp，再网络 */
    fun loadPortrait(ctx: Context, aid: Int, iv: ImageView) {
        val key = "portrait_$aid"
        val candidates = listOf("portrait/$aid.webp", "portrait/$aid.png")
        if (tryAssets(ctx, candidates, key, iv)) return
        val rel = "image/character_portrait/$aid.png"
        loadFromNetwork(ctx, key, fullUrls(rel), iv, maxEdge = 1024)
    }

    /**
     * 立绘 Bitmap（供 Compose 做严格裁剪用）：内存 → assets → 磁盘 → 网络。
     * 阻塞调用，请放在 IO 线程；失败返回 null，由调用方降级为头像。
     */
    fun portraitBitmap(ctx: Context, aid: Int): Bitmap? {
        val key = "portrait_$aid"
        memCache[key]?.let { return it }
        for (p in listOf("portrait/$aid.webp", "portrait/$aid.png")) {
            val bmp = runCatching {
                ctx.assets.open(p).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) {
                memCache[key] = bmp
                return bmp
            }
        }
        val disk = File(ctx.cacheDir, md5(key))
        if (disk.exists() && disk.length() > 500) {
            val bmp = runCatching { BitmapFactory.decodeFile(disk.absolutePath) }.getOrNull()
            if (bmp != null) {
                memCache[key] = bmp
                return bmp
            }
        }
        for (u in fullUrls("image/character_portrait/$aid.png")) {
            val raw = runCatching {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: continue
            val scale = 1024f / maxOf(raw.width, raw.height)
            val final = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else raw
            runCatching {
                FileOutputStream(disk).use { final.compress(Bitmap.CompressFormat.WEBP, 80, it) }
            }
            memCache[key] = final
            return final
        }
        return null
    }

    /** 技能图标：iconPath 形如 "icon/skill/1506_basic_atk.png" */
    fun loadMihomoIcon(ctx: Context, iconPath: String, iv: ImageView) {
        if (iconPath.isBlank()) { showPlaceholder(iv); return }
        if (iconPath.startsWith("http://") || iconPath.startsWith("https://")) {
            loadUrl(ctx, iconPath, iv, maxEdge = 512)
            return
        }
        val key = "mihomo_$iconPath"
        // assets 里是 skill/{name}.webp（把 .png 换成 .webp）
        val fileName = iconPath.substringAfter("icon/")           // skill/1506_basic_atk.png
        val webpName = fileName.substringBeforeLast(".") + ".webp" // skill/1506_basic_atk.webp
        if (tryAssets(ctx, listOf(webpName, fileName), key, iv)) return
        loadFromNetwork(ctx, key, fullUrls(iconPath), iv, maxEdge = 512)
    }

    /** 通用直链图片（公告封面等）：内存 → 磁盘缓存 → 单源网络，复用 WebP 压缩与 LRU 上限。 */
    fun loadUrl(ctx: Context, url: String, iv: ImageView, maxEdge: Int = 1024, quality: Int = 80) {
        if (url.isBlank()) { showPlaceholder(iv); return }
        val key = "url_" + md5(url)
        memCache[key]?.let { iv.setImageBitmap(it); return }
        val disk = File(ctx.cacheDir, md5(key))
        if (disk.exists() && disk.length() > 500) {
            try {
                val bmp = BitmapFactory.decodeFile(disk.absolutePath)
                if (bmp != null) { memCache[key] = bmp; iv.background = null; iv.setImageBitmap(bmp); return }
            } catch (_: Exception) {}
        }
        showPlaceholder(iv)
        CoroutineScope(Dispatchers.IO).launch {
            var raw: Bitmap? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                raw = BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
            } catch (_: Exception) {}
            val final = raw?.let { bmp ->
                val w = bmp.width; val h = bmp.height
                val scale = maxEdge.toFloat() / maxOf(w, h)
                val scaled = if (scale < 1f)
                    Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
                else bmp
                try {
                    FileOutputStream(disk).use { out -> scaled.compress(Bitmap.CompressFormat.WEBP, quality.coerceIn(50, 100), out) }
                } catch (_: Exception) {}
                if (scaled != bmp) bmp.recycle()
                trimCache(ctx)
                scaled
            }
            withContext(Dispatchers.Main) {
                if (final != null) { memCache[key] = final; iv.background = null; iv.setImageBitmap(final) }
            }
        }
    }

    /** 高清原图加载（全屏大图查看器）：长边放大上限、高质量压缩。 */
    fun loadHighRes(ctx: Context, url: String, iv: ImageView) {
        loadUrl(ctx, url, iv, maxEdge = 2048, quality = 95)
    }

    /**
     * Compose 用：按直链返回缓存/下载后的 Bitmap（内存 → 磁盘 → 网络）。
     * 阻塞下载，调用方需放在 IO 线程；失败返回 null 由 UI 降级为占位。
     */
    suspend fun bitmapForUrl(ctx: Context, url: String, maxEdge: Int = 128): Bitmap? {
        if (url.isBlank()) return null
        val key = "url_" + md5(url)
        memCache[key]?.let { return it }
        val disk = File(ctx.cacheDir, md5(key))
        if (disk.exists() && disk.length() > 500) {
            runCatching { BitmapFactory.decodeFile(disk.absolutePath) }.getOrNull()?.let {
                memCache[key] = it
                return it
            }
        }
        val raw = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                try {
                    BitmapFactory.decodeStream(conn.inputStream)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        } ?: return null
        val scaled = raw.let { bmp ->
            val w = bmp.width; val h = bmp.height
            val scale = maxEdge.toFloat() / maxOf(w, h)
            if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1), true)
            else bmp
        }
        runCatching { FileOutputStream(disk).use { scaled.compress(Bitmap.CompressFormat.WEBP, 80, it) } }
        memCache[key] = scaled
        return scaled
    }

    private fun fullUrls(relPath: String): List<String> {
        val list = mutableListOf<String>()
        for (p in GH_PROXIES) list.add("$p$RAW/$relPath")
        list.add("$BASE_JS/$relPath")
        list.add("$BASE_VIZ/$relPath")
        list.add("$RAW/$relPath")
        return list
    }

    private fun tryAssets(ctx: Context, paths: List<String>, key: String, iv: ImageView): Boolean {
        memCache[key]?.let { iv.setImageBitmap(it); return true }
        for (p in paths) {
            try {
                val bmp = ctx.assets.open(p).use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    memCache[key] = bmp
                    iv.background = null
                    iv.setImageBitmap(bmp)
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun loadFromNetwork(ctx: Context, key: String, urls: List<String>, iv: ImageView, maxEdge: Int) {
        memCache[key]?.let { iv.setImageBitmap(it); return }
        val disk = File(ctx.cacheDir, md5(key))
        if (disk.exists() && disk.length() > 500) {
            try {
                val bmp = BitmapFactory.decodeFile(disk.absolutePath)
                if (bmp != null) { memCache[key] = bmp; iv.background = null; iv.setImageBitmap(bmp); return }
            } catch (_: Exception) {}
        }
        showPlaceholder(iv)
        CoroutineScope(Dispatchers.IO).launch {
            var raw: Bitmap? = null
            for (u in urls) {
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.instanceFollowRedirects = true
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    raw = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (raw != null) break
                } catch (_: Exception) {}
            }
            val final = raw?.let { bmp ->
                val w = bmp.width; val h = bmp.height
                val scale = maxEdge.toFloat() / maxOf(w, h)
                val scaled = if (scale < 1f)
                    Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
                else bmp
                try {
                    FileOutputStream(disk).use { out -> scaled.compress(Bitmap.CompressFormat.WEBP, 80, out) }
                } catch (_: Exception) {}
                if (scaled != bmp) bmp.recycle()
                trimCache(ctx)
                scaled
            }
            withContext(Dispatchers.Main) {
                if (final != null) { memCache[key] = final; iv.background = null; iv.setImageBitmap(final) }
            }
        }
    }

    private fun trimCache(ctx: Context) {
        try {
            val files = ctx.cacheDir.listFiles()?.filter { it.isFile && it.name.length == 32 } ?: return
            val total = files.sumOf { it.length() }
            if (total <= CACHE_LIMIT_BYTES) return
            var remaining = total
            for (f in files.sortedBy { it.lastModified() }) {
                if (remaining <= CACHE_LIMIT_BYTES) break
                val sz = f.length()
                if (f.delete()) remaining -= sz
            }
        } catch (_: Exception) {}
    }

    /** 清空内存缓存、磁盘图片文件以及 WebView 产生的本地缓存。 */
    fun clearCache(ctx: Context) {
        memCache.clear()
        // 图片磁盘缓存（32 字符 md5 文件名）
        ctx.cacheDir.listFiles()?.forEach { if (it.name.length == 32) it.delete() }
        // WebView 缓存目录（不同系统版本落在 cacheDir 或 filesDir 下）
        listOf(
            File(ctx.cacheDir, "webview"),
            File(ctx.filesDir, "app_webview"),
            File(ctx.cacheDir, "org.chromium.android_webview")
        ).forEach { dir ->
            if (dir.exists()) dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun showPlaceholder(iv: ImageView) {
        val night = (iv.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val placeholderColor = if (night) 0xFF3A3F4A.toInt() else 0xFF243456.toInt()
        iv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(iv, 8)
            setColor(placeholderColor)
        }
        iv.setImageBitmap(null)
    }

    private fun md5(s: String): String = MessageDigest.getInstance("MD5")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun dp(iv: ImageView, v: Int): Float = (v * iv.resources.displayMetrics.density)
}
