package com.cloudflare.speedtest.core

import android.net.Uri
import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.security.cert.X509Certificate

/**
 * IP 连通性 / 下载速度测试工具。
 *
 * - 地区码 → 中文地区名：[RegionData]（数据在 res/raw/regions.json）
 * - 「过滤区域」通配符：[RegionMatcher]
 * - OkHttpClient 复用：[TestClientPool]
 */
object IPTestHelper {

    private const val TAG = "IPTestHelper"

    /** 默认测速端口（https 默认端口）。 */
    const val DEFAULT_TARGET_PORT = 443
    private const val DOWNLOAD_HOST = "speed.cloudflare.com"
    private const val DOWNLOAD_PATH = "/__down?bytes=40485760"

    // ==================== 探测目标 ====================

    /**
     * 探测目标：连目标 IP 时「伪装成访问哪个网站」。
     *
     * 这正是 VLESS/XHTTP 的三要素：SNI = [host]、端口 = [port]、路径 = [path]。
     * OkHttp 会自动用 [host] 作为 TLS SNI，所以直接测出来的结果就是 XHTTP web 模式
     * 真正需要的那个握手过程。
     */
    data class ProbeTarget(
        val host: String,
        val port: Int,
        val path: String,
        val alpn: List<String> = emptyList(),
        val protocol: String = ""
    ) {
        override fun toString(): String {
            val p = if (path.isEmpty() || path == "/") "" else path
            return "$host:$port$p"
        }
    }

    /** 默认目标：Cloudflare 官方测速端点。 */
    val DEFAULT_PROBE_TARGET: ProbeTarget = ProbeTarget(DOWNLOAD_HOST, DEFAULT_TARGET_PORT, DOWNLOAD_PATH)

    /** 用指定端口跑默认的 Cloudflare 测速目标（配合「IP测试」页的端口配置）。 */
    fun defaultTargetWithPort(port: Int): ProbeTarget = ProbeTarget(DOWNLOAD_HOST, port, DOWNLOAD_PATH)

    /** 按 [ProbeTarget] 拼 URL；443 省略端口号。 */
    fun buildProbeUrl(target: ProbeTarget): String {
        val p = when {
            target.path.isEmpty() -> "/"
            target.path.startsWith("/") -> target.path
            else -> "/${target.path}"
        }
        val portPart = if (target.port == DEFAULT_TARGET_PORT) "" else ":${target.port}"
        return "https://${target.host}$portPart$p"
    }

    /**
     * 从 VLESS / Trojan / Shadowsocks 等配置串里提取「伪装目标」参数。
     *
     * `vless://uuid@162.159.140.69:2096?security=tls&alpn=h2%2Chttp%2F1.1&type=xhttp&host=ui.wiofey.qzz.io&path=%2F&mode=auto#tag`
     * → `ProbeTarget(host="ui.wiofey.qzz.io", port=2096, path="/", alpn=["h2","http/1.1"], protocol="vless")`
     *
     * 注意 `@` 后面的 `ip:port` 是「连哪个节点」，`host=` 才是「伪装成哪个网站」，
     * XHTTP 要探测的是后者。也接受 `https://host:port/path` 或 `host:port/path`。
     * 解析失败返回 null。
     */
    fun parseProbeTarget(text: String): ProbeTarget? {
        if (text.isBlank()) return null
        return try {
            val noFragment = text.substringBefore('#').trim()
            val schemeIdx = noFragment.indexOf("://")
            if (schemeIdx < 0) return parseHostPortPath(noFragment)

            val scheme = noFragment.substring(0, schemeIdx).lowercase()
            val rest = noFragment.substring(schemeIdx + 3)
            val qIdx = rest.indexOf('?')
            val authPart = if (qIdx >= 0) rest.substring(0, qIdx) else rest
            val params = if (qIdx >= 0) parseQuery(rest.substring(qIdx + 1)) else emptyMap()

            // 去掉 userinfo（uuid 那一段）
            val authority = if ('@' in authPart) authPart.substringAfterLast('@') else authPart
            val slash = authority.indexOf('/')
            val hostPort = if (slash >= 0) authority.substring(0, slash) else authority
            val inlinePath = if (slash >= 0) authority.substring(slash) else ""

            val authHost = hostPort.substringBefore(':')
            val authPort = hostPort.substringAfter(':', "").toIntOrNull()

            val host = params["host"]?.ifBlank { null } ?: authHost.ifBlank { return null }
            val port = params["port"]?.toIntOrNull() ?: authPort ?: DEFAULT_TARGET_PORT
            val path = (params["path"] ?: inlinePath).ifBlank { "/" }
            val alpn = params["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()

            ProbeTarget(host, port, path, alpn, scheme)
        } catch (e: Exception) {
            Log.e(TAG, "解析探测目标失败: ${e.message}")
            null
        }
    }

    /** 解析 `host:port/path`（无 scheme）。 */
    private fun parseHostPortPath(s: String): ProbeTarget? {
        val slash = s.indexOf('/')
        val hostPort = if (slash >= 0) s.substring(0, slash) else s
        val path = if (slash >= 0) s.substring(slash) else "/"
        val host = hostPort.substringBefore(':').trim()
        if (host.isEmpty()) return null
        val port = hostPort.substringAfter(':', "").toIntOrNull() ?: DEFAULT_TARGET_PORT
        return ProbeTarget(host, port, path)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) return@mapNotNull null
            Uri.decode(kv.substring(0, i)) to Uri.decode(kv.substring(i + 1))
        }.toMap()
    }

    /** 地区码 → 中文地区名（未知返回 `未知地区`）。 */
    fun getRegionChineseName(code: String): String = RegionData.labelOf(code)

    /** 从 CF-RAY 头里取区域码（兼容旧调用）。 */
    fun extractRegionCode1(ray: String?): String = RegionData.regionCodeFromRay(ray)

    /** 把区域码拼成界面上展示的标签，如 `LAX:美国洛杉矶`。 */
    fun formatRegionLabel(code: String): String {
        val c = code.trim()
        if (c.isEmpty() || c.equals(RegionData.CODE_UNKNOWN, true)) return RegionData.LABEL_UNKNOWN
        return "$c:${RegionData.labelOf(c)}"
    }

    /** 把测试流量强制指向指定 IP（绕过 DNS）。 */
    class CustomDns(private val targetDomain: String, private val targetIp: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == targetDomain) {
                listOf(InetAddress.getByName(targetIp))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
    }

    /**
     * 构造测试客户端。TLS 上下文、连接池、调度器都由 [TestClientPool] 共享，
     * 只有 DNS（目标 IP）按调用参数区分。
     */
    fun createTestClient(hostname: String, ip: String, timeoutSeconds: Int = 6): OkHttpClient =
        TestClientPool.newBuilder(CustomDns(hostname, ip))
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
            .build()

    data class TestDownloadResult(
        val ip: String,
        val success: Boolean,
        val delay: Long,          // 单位 KB/s（沿用旧字段含义）
        val regionCode: String,   // 形如 `LAX:美国洛杉矶`
        val httpStatus: Int = -1, // 目标站点的 HTTP 状态码；-1 表示没拿到响应
        val alpn: String? = null, // 协商出的协议（h2 / http1.1）
        val certSubject: String? = null
    )

    /**
     * 下载测试：强制走 [ip]，统计 KB/s 并带上区域标签。
     *
     * [target] 决定「伪装成访问哪个网站」。默认是 Cloudflare 官方测速端点；
     * 传进一个从 VLESS 链接解析出来的 [ProbeTarget] 时，SNI / 端口 / 路径 /
     * Host 头会全部换成代理配置里那套，同时返回 HTTP 状态码、ALPN、证书，
     * 可以直接判断这个 IP 是不是在同一个 Cloudflare 站点、路径是否存在。
     *
     * 直接同步执行（调用方已在后台线程），不再额外起一个单线程池 + Future。
     */
    fun downloadWithSpeedTracking(
        ip: String,
        timeoutSeconds: Long,
        target: ProbeTarget = DEFAULT_PROBE_TARGET
    ): TestDownloadResult {
        val url = buildProbeUrl(target).toHttpUrlOrNull() ?: return fail(ip)
        val hostname = url.host
        // 非默认端口按 RFC 要求在 Host 头里带上端口
        val hostHeader = if (url.port == DEFAULT_TARGET_PORT) hostname else "$hostname:${url.port}"

        val request = Request.Builder()
            .url(url)
            .header("Host", hostHeader)
            .header("Connection", "keep-alive")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val client = createTestClient(hostname, ip, (timeoutSeconds + 5).toInt())

        val startTime = System.nanoTime()
        val totalBytesDownloaded = AtomicLong(0)
        var regionLabel = RegionData.LABEL_UNKNOWN
        var httpStatus = -1
        var alpn: String? = null
        var certSubject: String? = null

        try {
            val call = client.newCall(request)
            call.execute().use { response ->
                httpStatus = response.code
                alpn = (response.networkResponse?.protocol ?: response.protocol)?.toString()
                certSubject = (response.handshake?.peerCertificates?.getOrNull(0) as? X509Certificate)
                    ?.subjectDN?.name
                if (response.isSuccessful) {
                    val cfRayCode = extractRegionCode1(response.headers["CF-RAY"])
                    regionLabel = if (cfRayCode.isNotEmpty()) formatRegionLabel(cfRayCode)
                        else RegionData.LABEL_UNKNOWN
                }

                response.body?.byteStream()?.use { inputStream ->
                    val buffer = ByteArray(20480)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesDownloaded.addAndGet(bytesRead.toLong())
                        val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0
                        if (elapsedSec >= timeoutSeconds) break
                    }
                } ?: run {
                    Log.w(TAG, "响应体为空: $ip")
                }
            }

            val totalBytes = totalBytesDownloaded.get()
            val totalTimeSec = (System.nanoTime() - startTime) / 1_000_000_000.0
            if (totalTimeSec > 0 && totalBytes > 0) {
                return TestDownloadResult(
                    ip, true, (totalBytes / 1024.0 / totalTimeSec).toLong(),
                    regionLabel, httpStatus, alpn, certSubject
                )
            }
            return TestDownloadResult(ip, false, 0, regionLabel, httpStatus, alpn, certSubject)
        } catch (e: IOException) {
            Log.w(TAG, "下载失败: $ip - ${e.message}")
            return fail(ip)
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: $ip", e)
            return fail(ip)
        }
    }

    private fun fail(ip: String) = TestDownloadResult(ip, false, 0, RegionData.LABEL_UNKNOWN)

    /**
     * 把探测结果拼成一行可读文本，例如：
     * `LAX:美国洛杉矶 | HTTP 200 | h2 | 证书 ui.wiofey.qzz.io`
     *
     * HTTP 状态码 + ALPN + 证书是判断「这个 IP 能不能当 XHTTP 目标」的直接依据。
     */
    fun formatProbeResult(r: TestDownloadResult): String {
        val sb = StringBuilder(r.regionCode)
        if (r.httpStatus > 0) sb.append(" | HTTP ").append(r.httpStatus)
        if (!r.alpn.isNullOrEmpty()) sb.append(" | ").append(r.alpn)
        val cn = r.certSubject?.substringAfter("CN=", "")?.substringBefore(',')?.trim()
        if (!cn.isNullOrEmpty()) sb.append(" | 证书 ").append(cn)
        return sb.toString()
    }

    // ==================== 端点可用性验证 ====================

    /** 一个 IP 能不能真的当 [ProbeTarget] 的代理端点用。 */
    data class EndpointCheck(
        val ip: String,
        val ok: Boolean,        // true = 通过过滤（足够多次拿到 HTTP 响应）
        val httpStatus: Int,    // -1 = 最后一次没拿到响应
        val alpn: String?,
        val certSubject: String?,
        val regionCode: String,
        val passCount: Int,     // 共 [total] 次里拿到 HTTP 响应的次数
        val total: Int,
        val message: String
    )

    /**
     * 快速验证一个 IP 是否真的能当 [target] 的代理端点。
     *
     * 只取响应头、不下载响应体，几百毫秒级，适合对几百个 IP 批量跑。
     *
     * **为什么不能拿 speed.cloudflare.com 的测速结果代替这一步**：
     * `speed.cloudflare.com` 是 Cloudflare 自己的一个 zone，覆盖面比一般用户 zone 广得多。
     * 一个 IP 能给它跑出 3MB/s，只说明「这是个活着的 CF 边缘节点」，
     * **不代表它在你自己的 zone 里**。差集里那些 IP 就是「延迟低、测速快、
     * 换进链接却没网」的假阳性来源。只有拿**你自己的 host:port/path** 测到
     * 2xx + h2，才能确认它在你的 zone 内。
     */
    fun verifyEndpoint(
        ip: String,
        target: ProbeTarget,
        timeoutSeconds: Long = 6,
        attempts: Int = 3,
        minPass: Int = 2
    ): EndpointCheck {
        val url = buildProbeUrl(target).toHttpUrlOrNull()
            ?: return EndpointCheck(ip, false, -1, null, null, RegionData.LABEL_UNKNOWN, 0, attempts, "目标URL非法")

        val hostname = url.host
        val hostHeader = if (url.port == DEFAULT_TARGET_PORT) hostname else "$hostname:${url.port}"

        val request = Request.Builder()
            .url(url)
            .header("Host", hostHeader)
            .header("User-Agent", "Mozilla/5.0")
            .header("Connection", "close")
            .build()

        // 判定标准（对 Cloudflare 边缘 IP 分档，这是关键）：
        //
        //   2xx / 3xx / 4xx  → CF 认这个 Host，**而且真的通到了你的源站**
        //                      （404/403/405 都是源站真的回了 HTTP 响应，只是路径不对）
        //                      → CF→源站这条链路是通的，XHTTP 走这个 IP 就能通 → 可用
        //
        //   5xx              → 链路上有断点，换进 VLESS 链接照样不通 → 不可用
        //                      521/522/524 = CF 连不上你源站的 443（源站挂了 / 防火墙没放行 CF）
        //                      525/526     = CF 到源站的 TLS 握手失败
        //                      523/530     = CF 根本不认这个域名（没开小黄云 / 记录没配好）
        //
        //   -1               → 连接被重置或超时（GFW 干扰，或这个 IP 已下线）→ 不可用
        //
        // 521 是最容易误判的：它证明 IP 在你 zone 内，看着很"可用"，
        // 但它同时说明 CF→源站是断的，实际代理必挂，必须按不可用处理。
        //
        // 多次探测是因为对 CF 的干扰是「按 Host/SNI 粒度」的：同一个 IP 可能对
        // speed.cloudflare.com 稳定放行，对你的域名却间歇性重置。
        // 每次新建 client 强制走全新 TCP+TLS 握手，避免连接复用掩盖抖动。
        val n = attempts.coerceAtLeast(1)
        val need = minPass.coerceIn(1, n)
        var passCount = 0
        var status = -1
        var alpn: String? = null
        var cert: String? = null
        var region = RegionData.LABEL_UNKNOWN

        for (i in 0 until n) {
            val client = createTestClient(hostname, ip, timeoutSeconds.toInt())
            try {
                client.newCall(request).execute().use { r ->
                    status = r.code
                    alpn = (r.networkResponse?.protocol ?: r.protocol)?.toString()
                    cert = (r.handshake?.peerCertificates?.getOrNull(0) as? X509Certificate)
                        ?.subjectDN?.name
                    val c = extractRegionCode1(r.headers["CF-RAY"])
                    region = if (c.isNotEmpty()) formatRegionLabel(c) else RegionData.LABEL_UNKNOWN
                    // 只有源站真的回了 HTTP（2xx/3xx/4xx）才算这次通
                    if (status in 200..499) passCount++
                }
            } catch (e: IOException) {
                status = -1
            } catch (e: Exception) {
                status = -1
            }
        }

        return EndpointCheck(
            ip, passCount >= need, status, alpn, cert, region, passCount, n,
            "$passCount/$n 通｜" + endpointMessage(status, alpn, n, passCount)
        )
    }

    private fun endpointMessage(status: Int, alpn: String?, total: Int, pass: Int): String {
        val stab = when {
            pass == total -> "稳定"
            pass > 0 -> "抖动"
            else -> "无响应"
        }
        val base = when (status) {
            -1 -> if (pass == 0) "连接被重置或超时" else "最后几次连接失败"
            521 -> "不可用：CF连不上你源站443（源站挂了/防火墙没放行CF）"
            522 -> "不可用：CF连你源站超时"
            524 -> "不可用：CF连上源站但没等到响应"
            525 -> "不可用：CF到源站TLS握手失败"
            526 -> "不可用：源站证书无效"
            523 -> "不可用：CF不认这个域名"
            530 -> "不可用：CF不认这个域名"
            else -> if (status in 200..499) "可用：源站有响应（HTTP $status）" else "HTTP $status"
        }
        val proto = if (alpn == "http/1.1") "｜仅http/1.1" else ""
        return "$stab｜$base$proto"
    }
}
