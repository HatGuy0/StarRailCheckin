package com.mh.starrailcheckin.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * 带边界预采样的位图解码工具。
 *
 * 大图（立绘、远程头像/封面）若直接全尺寸解码，会先在内存里铺开整张原图再缩放，
 * 内存峰值偏高。这里先以 [BitmapFactory.Options.inJustDecodeBounds] 只读宽高，
 * 按目标长边算出 2 的幂次 [BitmapFactory.Options.inSampleSize]，再二次采样解码，
 * 避免解码瞬间加载整张原图。
 */
object BitmapSampler {

    /** 按目标长边计算 inSampleSize（2 的幂，采样后长边仍尽量不小于目标值）。 */
    private fun sampleSize(outW: Int, outH: Int, reqEdge: Int): Int {
        if (reqEdge <= 0 || outW <= 0 || outH <= 0) return 1
        var sample = 1
        val longEdge = maxOf(outW, outH)
        while (longEdge / sample / 2 >= reqEdge) sample *= 2
        return sample
    }

    /** 解码本地文件，按目标长边预采样；失败返回 null。 */
    fun decodeFile(path: String, reqEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqEdge)
        }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }

    /** 解码字节数组（网络响应先 readBytes 后走这里），按目标长边预采样；失败返回 null。 */
    fun decodeByteArray(data: ByteArray, reqEdge: Int): Bitmap? {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqEdge)
        }
        return runCatching { BitmapFactory.decodeByteArray(data, 0, data.size, opts) }.getOrNull()
    }

    /**
     * 解码流：支持 mark/reset 的流（assets、本地文件流）先读边界再采样；
     * 不支持重置的流退化为直接解码（调用方应优先把网络流读成字节数组走 [decodeByteArray]）。
     */
    fun decodeStream(stream: InputStream, reqEdge: Int): Bitmap? {
        if (stream.markSupported()) {
            runCatching {
                stream.mark(Int.MAX_VALUE)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                stream.reset()
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqEdge)
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                } else {
                    stream.reset()
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.let { return it }
        }
        return runCatching { BitmapFactory.decodeStream(stream) }.getOrNull()
    }
}
