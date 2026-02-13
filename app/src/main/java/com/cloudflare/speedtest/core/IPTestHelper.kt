package com.cloudflare.speedtest.core

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.system.measureTimeMillis
import kotlin.text.ifEmpty
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.TimeoutException

import java.util.concurrent.atomic.AtomicLong


object IPTestHelper {

    fun getRegionChineseName(code: String): String {
        return regionMap[code.uppercase()] ?: "未知地区"
    }

    // Cloudflare 数据中心地区码映射（部分常见）
    val regionMap = mapOf(
        // 亚洲
        "HKG" to "香港",
        "NRT" to "日本东京",
        "KIX" to "日本大阪",
        "SIN" to "新加坡",
        "ICN" to "韩国首尔",
        "DEL" to "印度德里",
        "BOM" to "印度孟买",
        "TPE" to "台湾台北",

        // 北美
        "DEN" to "美国丹佛",      // 科罗拉多州，落基山脉地区
        "SJC" to "美国圣何塞",
        "SEA" to "美国西雅图",
        "LAX" to "美国洛杉矶",
        "SFO" to "美国旧金山",
        "DFW" to "美国达拉斯",
        "ORD" to "美国芝加哥",
        "IAD" to "美国华盛顿",
        "EWR" to "美国纽约",
        "YYZ" to "加拿大多伦多",
        "YVR" to "加拿大温哥华",

        // 欧洲
        "AMS" to "荷兰阿姆斯特丹",
        "FRA" to "德国法兰克福",
        "CDG" to "法国巴黎",
        "LHR" to "英国伦敦",
        "MAD" to "西班牙马德里",
        "MXP" to "意大利米兰",

        // 大洋洲
        "SYD" to "澳大利亚悉尼",
        "MEL" to "澳大利亚墨尔本",

        // 南美
        "GRU" to "巴西圣保罗",
        "EZE" to "阿根廷布宜诺"
    )

    fun extractRegionCode1(ray: String): String {
        return if (ray.contains("-")) {
            ray.split("-").last()
        } else {
            // 如果没有连字符，可能是纯代码或无效格式
            if (ray.length <= 4 && ray.all { it.isLetter() }) ray else "UNKNOWN"
        }
    }
    class CustomDns(private val targetDomain: String, private val targetIp: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return if (hostname == targetDomain) {
                // 将目标域名解析为指定IP
                listOf(InetAddress.getByName(targetIp))
            } else {
                // 其他域名使用系统DNS
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }
    fun createTestClient(hostname:String,ip:String,timeoutSeconds: Int = 6): OkHttpClient {
        return try {
            // 创建信任所有证书的TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 安装信任所有证书的SSLContext
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // 创建不验证主机名的HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .dns(CustomDns(hostname,ip))
                .build()
        } catch (e: Exception) {
            // 如果SSL初始化失败，创建普通客户端
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    data class TestResult(
        val ip: String,
        val success: Boolean,
        val delay: Long,
        val statusCode: Int = -1
    ) : Comparable<TestResult> {
        override fun compareTo(other: TestResult): Int {
            // 排序规则：成功的在前，失败的在后；成功的按延迟升序排序
            return when {
                this.success && !other.success -> -1
                !this.success && other.success -> 1
                this.success && other.success -> this.delay.compareTo(other.delay)
                else -> 0 // 两个都失败，保持原顺序
            }
        }
    }

    data class TestDownloadResult(
        val ip: String,
        val success: Boolean,
        val delay: Long,
        val regionCode: String = "未知地区"
    )

    fun testSingleIpSync(
        ip: String,
        timeoutSeconds: Int = 6,
        targetUrl: String = "https://www.cloudflare-cn.com"
    ): TestResult {
        val url = targetUrl.toHttpUrlOrNull() ?: return TestResult(ip, false, -1)

        val hostname = url.host  // 使用 .host 替代 .host()

        val request = Request.Builder()
            .url(targetUrl)
            .head() // 使用HEAD方法
            .header("Host", hostname)
            .header("Connection", "close")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val client = createTestClient(hostname,ip,timeoutSeconds)

        return try {
            // 测试连接时间
            val startTime = System.nanoTime()
            // 重新获取状态码
            val response = client.newCall(request).execute()

            val statusCode = response.code
            response.close()

            val delayMs =((System.nanoTime()-startTime)/1_000_000).toLong()


            if (statusCode in listOf(200, 204, 301, 302, 307, 308,421,403)) {
            TestResult(ip, true, delayMs, statusCode)
            } else {
                TestResult(ip, false, -1, statusCode)
            }
        } catch (e: IOException) {
            TestResult(ip, false, -1)
        } catch (e: Exception) {
            TestResult(ip, false, -1)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }


    fun downloadWithSpeedTracking(
        ip: String,
        timeoutSeconds: Long,
        targetUrl: String = "https://speed.cloudflare.com/__down?bytes=40485760"
    ): TestDownloadResult {
        val url = targetUrl.toHttpUrlOrNull() ?: return TestDownloadResult(ip,false,0,"未知地区")
        val hostname = url.host
        val client = createTestClient(hostname, ip,(timeoutSeconds+5).toInt())

        val request = Request.Builder()
            .url(targetUrl)
            .header("Host", hostname)
            .header("Connection", "keep-alive")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val startTime = System.nanoTime()
        val totalBytesDownloaded = AtomicLong(0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val call = client.newCall(request)
            val future = executor.submit<TestDownloadResult?> {
                try {
                    var cfRayCodeChinese:String="未知地区"
                    val response = call.execute()
                    response.use { // 使用use确保资源自动关闭
                        val inputStream = response.body?.byteStream() ?: return@submit null
                        val buffer = ByteArray(20480)
                        var bytesRead: Int



                        if (it.isSuccessful) {
                            val cfRay = it.headers["CF-RAY"]

                            val cfRayCode = extractRegionCode1(cfRay.toString())

                            if(cfRayCode.isNotEmpty()){
                                cfRayCodeChinese = cfRayCode+":"+getRegionChineseName(cfRayCode)
                            }

                        }

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            totalBytesDownloaded.addAndGet(bytesRead.toLong())
                            val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0

                            // 提前检查超时
                            if (elapsedSec >= timeoutSeconds) {
                                println("Download timed out after $timeoutSeconds seconds.")
                                break
                            }
                        }
                    }

                    // 计算平均下载速度
                    val totalBytes = totalBytesDownloaded.get()
                    val totalTimeSec = (System.nanoTime() - startTime) / 1_000_000_000.0

                    if (totalTimeSec > 0) {
                        TestDownloadResult(ip,true,(totalBytes / 1024.0 / totalTimeSec).toLong(),cfRayCodeChinese)

                    } else {
                        TestDownloadResult(ip,false,0,cfRayCodeChinese)
                    }
                } catch (e: IOException) {
                    println("Download failed with exception: ${e.message}")
                    TestDownloadResult(ip,false,0,"未知地区")
                }
            }

            // 使用future.get的超时控制，而不是Thread.sleep
            return try {
                future.get(timeoutSeconds+5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                println("Download timed out after $timeoutSeconds seconds.")
                call.cancel()
                TestDownloadResult(ip,false,0,"未知地区1")
            } catch (e: Exception) {
                println("Download failed: ${e.message}")
                TestDownloadResult(ip,false,0,"未知地区2")
            }
        } finally {
            executor.shutdown()
        }
    }

}