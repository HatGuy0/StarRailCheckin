package com.mh.starrailcheckin.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 统一 OkHttp 客户端提供者：共享连接池与默认超时，避免各处重复 new 客户端造成
 * TLS/握手重复开销。写接口（签到/续期）不在此做自动重试，由调用方控制。
 */
object HttpClientProvider {

    private val sharedPool = ConnectionPool()

    /** 默认客户端：connect 15s / read 20s / write 15s，跟随重定向，连接失败可重试一次。 */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 按场景定制超时的 Builder（仍复用共享连接池）。 */
    fun builder(
        connectSec: Long = 15,
        readSec: Long = 20,
        writeSec: Long = 15
    ): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .writeTimeout(writeSec, TimeUnit.SECONDS)
            .followRedirects(true)
}
