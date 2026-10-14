package com.cloudflare.speedtest.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 测试用 OkHttpClient 池。
 *
 * 原先每个 IP 都新建一个 `SSLContext` + `TrustManager` + `OkHttpClient`（各带独立线程池/连接池），
 * 测几百个 IP 就是几百次 TLS 初始化。这里按「超时配置」缓存实例，并共享
 * [Dispatcher] / [ConnectionPool] / `SSLContext`。
 */
object TestClientPool {

    private const val MAX_CLIENTS = 8
    private const val WORKERS = 16

    private val lock = Any()
    private val clients = LinkedHashMap<String, OkHttpClient>(MAX_CLIENTS, 0.75f, true)

    private val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustManager), null)
        ctx
    }

    private val dispatcher: Dispatcher by lazy {
        // OkHttp 4.12 的 Dispatcher.executorService 只能构造时指定（没有 setter）
        Dispatcher(Executors.newFixedThreadPool(WORKERS)).apply {
            maxRequests = 256
            maxRequestsPerHost = 64
        }
    }

    private val connectionPool: ConnectionPool by lazy {
        ConnectionPool(MAX_CLIENTS * 10, 90, TimeUnit.SECONDS)
    }

    /** 取（或建）一个测试客户端。同一超时配置复用同一实例。 */
    fun get(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient = synchronized(lock) {
        val key = "$connectTimeoutMs|$readTimeoutMs"
        clients[key] ?: buildClient(connectTimeoutMs, readTimeoutMs).also { clients[key] = it }
    }

    private fun buildClient(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(5_000, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()

    /**
     * 构造一个测试客户端：共享 [dispatcher] / [connectionPool] / TLS 配置，
     * 只按调用方传入的 [dns] 区分目标 IP。避免每个 IP 都新建一次 SSLContext。
     */
    fun newBuilder(dns: okhttp3.Dns): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .retryOnConnectionFailure(false)
            .dns(dns)

    /** 释放空闲连接（共享线程池不关闭，避免影响其他调用方）。 */
    fun release() {
        synchronized(lock) { connectionPool.evictAll() }
    }
}
