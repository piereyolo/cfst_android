package com.cloudflare.speedtest.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * VLESS 链接「真连接验证」引擎。
 *
 * 做法：把 assets 里的 xray 解到 filesDir，按链接生成 xray 配置，起一个本地 HTTP 代理
 * 进程，用 OkHttp 走这个代理访问测试地址，拿到 HTTP 状态码和耗时，然后杀掉进程。
 *
 * 不自己实现 VLESS / XHTTP 协议——那套东西 Xray 才是参考实现，自己搓必然出问题。
 *
 * 配置格式跟电脑上 tools/vless_check.py 完全一致，两边判定结果可以互相校验。
 */
class XrayVerifier(private val context: Context) {

    companion object {
        const val ASSET_NAME = "xray"
        /** jniLibs 里的库名。必须是 lib*.so，AGP 才会当 native lib 处理 */
        const val LIB_NAME = "libxray.so"
        const val DEFAULT_TEST_URL = "https://www.google.com/generate_204"

        /** 链接里的 type= 参数 → 核心的传输层协议名 */
        private val NETWORK_MAP = mapOf(
            "xhttp" to "xhttp",
            "ws" to "ws",
            "grpc" to "grpc",
            "tcp" to "tcp",
            "kcp" to "kcp",
            "h2" to "h2",
            "splithttp" to "splithttp"
        )

        /**
         * 追踪本应用起过的所有 xray 进程（全局）。
         * 跨 Fragment 生命周期，销毁时调 [shutdownAll] 统一杀干净。
         */
        private val running = java.util.Collections.synchronizedSet(mutableSetOf<Process>())

        /** 杀掉本应用起过的所有 xray 进程，避免进程残留占内存 */
        fun shutdownAll() {
            running.toList().forEach { proc ->
                try {
                    // 直接强杀，不等优雅退出——验证场景不需要 xray 写临时状态
                    proc.destroyForcibly()
                } catch (e: Exception) {
                    // ignore
                }
                running.remove(proc)
            }
        }

        /**
         * 打包的二进制是 arm64 的，32 位设备跑不起来（Exec format error）。
         * 启动前先检查，别让用户测完一堆才发现全废。
         */
        fun isArchSupported(): Boolean {
            return android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64") }
        }

        fun archDescription(): String =
            android.os.Build.SUPPORTED_ABIS.joinToString(", ")
    }

    /** 配置文件和日志放这里。filesDir 可写——SELinux 只禁执行，不禁止读写 */
    private val dir: File by lazy { File(context.filesDir, "xray").apply { mkdirs() } }

    /**
     * xray 二进制路径。
     *
     * Android Q (API 29) 起，SELinux 禁止执行 filesDir 里的文件：
     *
     *   avc: denied { execute_no_trans }
     *     scontext=u:r:untrusted_app tcontext=u:object_r:app_data_file
     *
     * 表现就是 ProcessBuilder.start() 抛 `error=13 Permission denied`，
     * chmod 777 也治不了——这是 SELinux 策略层的拒绝，不是文件权限位的问题。
     *
     * jniLibs 里的 .so 会被解压到 /data/app/<pkg>/lib/<abi>/，
     * 那里的 SELinux 上下文允许执行。所以核心必须走 jniLibs，不能放 assets。
     */
    private val binary: File by lazy {
        File(context.applicationInfo.nativeLibraryDir, LIB_NAME)
    }

    /** 最后一次失败的诊断信息，方便在 UI 上给用户看 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 检查核心是否在位。
     * 走 jniLibs 的话安装时就解压好了，这里只做存在性检查。
     */
    fun ensureBinary(): File {
        if (!binary.exists()) {
            throw IllegalStateException(
                "找不到核心：${binary.absolutePath}\n" +
                "确认 build.gradle.kts 里 extractNativeLibs = true，" +
                "且 app/src/main/jniLibs/arm64-v8a/$LIB_NAME 存在"
            )
        }
        return binary
    }

    // ---------------------------------------------------------------- 链接解析

    /**
     * 解析 vless:// 或 vmess:// 链接。
     */
    fun parseLink(link: String): VlessNode? {
        val s = link.trim()
        return when {
            s.startsWith("vless://") -> parseVless(s)
            s.startsWith("vmess://") -> parseVmess(s)
            else -> null
        }
    }

    /** 解析 vless://uuid@host:port?params#remark */
    private fun parseVless(link: String): VlessNode? {
        val s = link.trim()
        var rest = s.substring("vless://".length)
        val hashAt = rest.indexOf('#')
        if (hashAt >= 0) rest = rest.substring(0, hashAt)

        val at = rest.lastIndexOf('@')
        if (at < 0) return null
        val uuid = rest.substring(0, at).trim()
        val auth = rest.substring(at + 1).trim()
        if (uuid.isEmpty() || auth.isEmpty()) return null

        val q = auth.indexOf('?')
        val head = if (q >= 0) auth.substring(0, q) else auth
        val query = if (q >= 0) auth.substring(q + 1) else ""

        val colon = head.indexOf(':')
        val hostPart = if (colon >= 0) head.substring(0, colon) else head
        val portStr = if (colon >= 0) head.substring(colon + 1) else ""
        if (hostPart.contains("/")) return null

        // 参数值都是 URL 编码的（path=%2F 就是 /），必须解码。
        // 不解码会把 %2F 当字面 path 喂给核心，XHTTP 握手必然失败。
        val params = mutableMapOf<String, String>()
        for (kv in query.split('&')) {
            val eq = kv.indexOf('=')
            if (eq > 0) {
                val k = kv.substring(0, eq).lowercase()
                val v = try {
                    URLDecoder.decode(kv.substring(eq + 1), "UTF-8")
                } catch (e: Exception) {
                    kv.substring(eq + 1)
                }
                params[k] = v
            }
        }

        val port = portStr.toIntOrNull() ?: 443
        val type = (params["type"] ?: "xhttp").lowercase()

        // SNI：v2rayN 导出会同时给 sni= 和 host=，优先 sni
        val sni = params["sni"]?.takeIf { it.isNotEmpty() }
            ?: params["host"]?.takeIf { it.isNotEmpty() }
            ?: hostPart

        // alpn=h2%2Chttp%2F1.1 → ["h2", "http/1.1"]，没给就用默认
        val alpn = params["alpn"].orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("h2", "http/1.1") }

        return VlessNode(
            raw = s,
            protocol = "vless",
            uuid = uuid,
            server = hostPart,
            port = port,
            security = (params["security"] ?: "tls").lowercase(),
            sni = sni,
            path = params["path"].orEmpty().ifEmpty { "/" },
            mode = params["mode"] ?: "auto",
            type = type,
            network = NETWORK_MAP[type] ?: type,
            alpn = alpn,
            encryption = params["encryption"] ?: "none",
            pbk = params["pbk"].orEmpty(),
            sid = params["sid"].orEmpty(),
            spx = params["spx"].orEmpty(),
            fp = params["fp"] ?: "chrome"
        )
    }

    /**
     * 解析 vmess://BASE64(json) 或 vmess://BASE64(json)#remark。
     *
     * JSON 字段（v2rayN/Shadowrocket 通用）：
     *   add 端口 port id aid scy net type host path tls sni ps(备注) fp alpn
     */
    private fun parseVmess(link: String): VlessNode? {
        val s = link.trim()
        var rest = s.substring("vmess://".length)
        // 允许 #remark 结尾，但 vmess 链接里 # 之前是完整 base64
        val hashAt = rest.indexOf('#')
        if (hashAt >= 0) rest = rest.substring(0, hashAt)
        if (rest.isEmpty()) return null

        val jsonStr = try {
            String(java.util.Base64.getDecoder().decode(rest), java.nio.charset.StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // 有的客户端用 url-safe base64
            try {
                String(java.util.Base64.getUrlDecoder().decode(rest), java.nio.charset.StandardCharsets.UTF_8)
            } catch (e2: Exception) {
                return null
            }
        }

        val json = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return null
        }

        val server = json.optString("add", "").trim()
        val id = json.optString("id", "").trim()
        if (server.isEmpty() || id.isEmpty()) return null

        val port = json.optInt("port", 0).let { if (it > 0) it else json.optString("port", "443").toIntOrNull() ?: 443 }
        val aid = json.optInt("aid", 0).let { if (it >= 0) it else json.optString("aid", "0").toIntOrNull() ?: 0 }
        val scy = json.optString("scy", "auto").ifEmpty { "auto" }

        val net = json.optString("net", "tcp").lowercase()
        val type = json.optString("type", "none").lowercase()
        val path = json.optString("path", "").ifEmpty { "/" }
        val mode = json.optString("mode", "auto").ifEmpty { "auto" }

        // host 字段在 vmess 里既可能是 sni 也可能是 host，v2rayN 用 sni= 单独标
        val sni = json.optString("sni", "").ifEmpty {
            json.optString("host", "").ifEmpty { server }
        }

        val security = when (json.optString("tls", "").lowercase()) {
            "tls", "1" -> "tls"
            "xtls" -> "tls"
            "reality" -> "reality"
            else -> "none"
        }

        // vmess 一般不带 alpn，但部分客户端会给；给了就用
        val alpn = json.optString("alpn", "")
            .split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("h2", "http/1.1") }

        return VlessNode(
            raw = s,
            protocol = "vmess",
            uuid = id,
            server = server,
            port = port,
            security = security,
            sni = sni,
            path = path,
            mode = mode,
            type = type,
            network = NETWORK_MAP[net] ?: net,
            alpn = alpn,
            encryption = scy,
            alterId = aid,
            pbk = json.optString("pbk", ""),
            sid = json.optString("sid", ""),
            spx = json.optString("spx", ""),
            fp = json.optString("fp", "chrome").ifEmpty { "chrome" }
        )
    }

    // ---------------------------------------------------------------- 配置生成

    /**
     * 生成 xray 配置。字段名跟 V2rayN 实际生成的配置一致：
     * network=xhttp / xhttpSettings / alpn。
     *
     * 写错键名不会报错，而是被静默忽略，path/host 完全不生效——这是最容易踩的坑。
     *
     * vless 和 vmess 差别只在 outbound.protocol 和 users 结构：
     *   vless: users[].{id, encryption:"none"}
     *   vmess: users[].{id, alterId, security}
     */
    fun buildConfig(node: VlessNode, port: Int): String {
        val stream = JSONObject()
            .put("network", node.network)
            .put("security", node.security)

        when (node.network) {
            "xhttp" -> stream.put("xhttpSettings", JSONObject()
                .put("path", node.path)
                .put("host", node.sni)
                .put("mode", node.mode))
            "ws" -> stream.put("wsSettings", JSONObject()
                .put("path", node.path)
                .put("host", node.sni))
            "grpc" -> stream.put("grpcSettings", JSONObject()
                .put("serviceName", node.path.trim('/'))
                .put("multiMode", false))
        }

        when (node.security) {
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", node.sni)
                .put("allowInsecure", false)
                .put("alpn", JSONArray(node.alpn)))
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("show", false)
                .put("fingerprint", node.fp)
                .put("serverName", node.sni)
                .put("publicKey", node.pbk)
                .put("shortId", node.sid)
                .put("spiderX", node.spx))
        }

        // users 结构按协议区分
        val user = when (node.protocol) {
            "vmess" -> JSONObject()
                .put("id", node.uuid)
                .put("alterId", node.alterId)
                .put("security", node.scy.ifEmpty { "auto" })
            else -> JSONObject()
                .put("id", node.uuid)
                .put("encryption", node.encryption.ifEmpty { "none" })
        }

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("tag", "httpin")
                .put("listen", "127.0.0.1")
                .put("port", port)
                .put("protocol", "http")))
            .put("outbounds", JSONArray().put(JSONObject()
                .put("tag", "proxy")
                .put("protocol", node.protocol)
                .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", node.server)
                    .put("port", node.port)
                    .put("users", JSONArray().put(user)))))
                .put("streamSettings", stream)))
            .toString()
    }

    // ---------------------------------------------------------------- 单次测试

    /**
     * 测一次。返回 RTT 毫秒，失败返回 -1。
     *
     * 整体超时保护：如果因为某种原因（xray hang、OkHttp 不触发子超时）导致
     * 执行超过硬超时，看门狗线程强杀 proc 并中断当前线程，避免整个批次卡死。
     * 用户反馈过「显示开始测 N 条链接后就没下文了」的间歇性 bug，根因大概率
     * 是上一轮残留 xray 进程导致系统资源耗尽。这里加了三层防线：
     *   1. 看门狗线程（整体硬超时）
     *   2. OkHttpClient.callTimeout（总超时，兜底所有子超时不生效的情况）
     *   3. verifyBatch 开头 shutdownAll（清理残留进程）
     */
    fun testOnce(node: VlessNode, testUrl: String, timeoutSec: Int): Long {
        if (node.security == "reality" && node.pbk.isEmpty()) return -1
        if (node.security !in setOf("tls", "none", "reality")) return -1

        ensureBinary()
        val port = freePort()
        val cfgFile = File(dir, "cfg_$port.json")
        // 每次用独立日志文件，避免多进程争抢同一个 xray.log
        val logFile = File(dir, "xray_$port.log")

        // 看门狗：整体超时 = timeoutSec + 5 秒缓冲。
        // 超时后强杀所有还在跑的 xray 进程，让 OkHttp / waitPort 尽快返回。
        val hardTimeoutMs = (timeoutSec + 5L) * 1000
        val watchdog = Thread {
            try {
                Thread.sleep(hardTimeoutMs)
            } catch (e: InterruptedException) {
                return@Thread  // 正常完成，不需要强杀
            }
            running.toList().forEach { p ->
                try {
                    p.destroyForcibly()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        watchdog.isDaemon = true

        val proc = try {
            cfgFile.writeText(buildConfig(node, port))
            val p = ProcessBuilder(binary.absolutePath, "run", "-config", cfgFile.absolutePath)
                .directory(dir)
                .redirectOutput(logFile)
                .redirectErrorStream(true)
                .start()
            running.add(p)
            p
        } catch (e: Exception) {
            lastError = "核心启动失败: ${e.message}"
            return -1
        }

        watchdog.start()

        try {
            if (!waitPort(port, 3000)) {
                val log = try { logFile.readText().trim().takeLast(240) } catch (e: Exception) { "" }
                lastError = if (log.isEmpty()) "核心没监听端口" else "核心没起来: $log"
                return -1
            }

            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .readTimeout((timeoutSec + 2L), TimeUnit.SECONDS)
                // callTimeout 是总超时，兜底所有子超时不生效的极端情况
                // （比如 TCP 连上了但 xray 不写也不断，readTimeout 可能一直等）
                .callTimeout((timeoutSec + 3L), TimeUnit.SECONDS)
                .build()

            val t0 = System.currentTimeMillis()
            client.newCall(Request.Builder().url(testUrl).build()).execute().use { resp ->
                if (resp.isSuccessful) return System.currentTimeMillis() - t0
            }
            return -1
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = "单次测试超时（${hardTimeoutMs / 1000}s）"
            return -1
        } catch (e: Exception) {
            return -1
        } finally {
            watchdog.interrupt()  // 通知看门狗退出，别让它误杀后续进程
            running.remove(proc)
            proc.destroy()
            try {
                proc.waitFor(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (proc.isAlive) proc.destroyForcibly()
            cfgFile.delete()
            logFile.delete()
        }
    }

    // ---------------------------------------------------------------- 批量验证

    /**
     * 批量验证。失败次数一超过阈值就立刻停掉该链接的剩余次数，不傻跑完。
     *
     * @param onResult 每测完一条链接就回调一次，不管通过还是死链。
     *                 用在「实时刷列表」场景——通过一条就立刻往 UI 里塞一条，
     *                 用户不用等全部测完才看到结果。null 则不回调。
     */
    fun verifyBatch(
        links: List<String>,
        attempts: Int,
        intervalSec: Long,
        failThreshold: Int,
        testUrl: String,
        shouldStop: () -> Boolean,
        onEvent: (String) -> Unit,
        onResult: ((VerifyResult) -> Unit)? = null,
    ): List<VerifyResult> {
        ensureBinary()
        // 清理上一轮可能残留的 xray 进程。用户反馈过「显示开始测后就没下文」的
        // 间歇性 bug，根因大概率是残留进程耗尽资源导致新一轮 start() hang。
        // 这里先清一遍，保证每个批次从干净状态开始。
        shutdownAll()
        // 顺便清理上一轮的日志文件，防止磁盘慢慢撑爆
        try {
            dir.listFiles { f -> f.name.startsWith("xray_") && f.name.endsWith(".log") }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            // ignore
        }
        val results = mutableListOf<VerifyResult>()

        for ((idx, link) in links.withIndex()) {
            if (shouldStop()) break

            val node = parseLink(link)
            if (node == null) {
                onEvent("[$idx] 解析失败，跳过")
                continue
            }
            val label = "${node.server}:${node.port}"
            onEvent("$label  ${node.protocol}  SNI=${node.sni}  path=${node.path}  net=${node.network}")

            val detail = mutableListOf<Pair<Long, String>>()
            var earlyStopped = false

            for (k in 0 until attempts) {
                if (shouldStop()) break

                val rtt = testOnce(node, testUrl, 5)
                if (rtt >= 0) {
                    detail.add(Pair(rtt, "OK"))
                    onEvent("  第${k + 1}次: ${rtt}ms")
                } else {
                    detail.add(Pair(-1L, "FAIL"))
                    onEvent("  第${k + 1}次: -1")
                }

                // 失败次数一超过阈值就死定了，没必要再测
                val failsNow = detail.count { it.first < 0 }
                if (failsNow > failThreshold) {
                    val left = attempts - (k + 1)
                    if (left > 0) {
                        earlyStopped = true
                        onEvent("  ↳ 失败已超阈值，提前结束（跳过剩余 $left 次）")
                    }
                    break
                }

                if (k < attempts - 1 && intervalSec > 0) {
                    sleepInterruptible(intervalSec * 1000, shouldStop)
                }
            }

            // 一条测完，喘口气再测下一条
            if (!shouldStop() && idx < links.size - 1 && intervalSec > 0) {
                sleepInterruptible(intervalSec * 1000, shouldStop)
            }

            val fails = detail.count { it.first < 0 }
            val ok = detail.filter { it.first >= 0 }
            val best = ok.minOfOrNull { it.first } ?: -1
            val dead = fails > failThreshold

            val result = VerifyResult(
                link = link,
                label = label,
                address = node.server,
                bestMs = best,
                fails = fails,
                attempts = detail.size,
                dead = dead,
                earlyStopped = earlyStopped,
                detail = detail
            )
            results.add(result)

            val status = if (dead) "-1（判定死链）" else "通（最佳 ${best}ms）"
            val suffix = if (earlyStopped) " ←提前结束" else ""
            onEvent("=> $label  失败 ${fails}/${detail.size}  =>  $status$suffix\n")

            // 每条测完就回调，UI 那边可以立刻把通过的地址塞进列表
            if (!dead) onResult?.invoke(result)
        }

        return results
    }

    // ---------------------------------------------------------------- 工具

    /** 找一个空闲端口。有极小竞态，可接受。 */
    private fun freePort(): Int {
        return try {
            val s = ServerSocket(0)
            val p = s.localPort
            s.close()
            p
        } catch (e: Exception) {
            // 退回到随机高位端口
            18100 + ((System.currentTimeMillis() % 500).toInt())
        }
    }

    /** 等本地代理端口真正起来 */
    private fun waitPort(port: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                val s = Socket()
                s.soTimeout = 150
                s.connect(InetSocketAddress("127.0.0.1", port), 150)
                s.close()
                return true
            } catch (e: Exception) {
                // 还没起来
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** 可被 shouldStop 打断的睡眠 */
    private fun sleepInterruptible(ms: Long, shouldStop: () -> Boolean) {
        var left = ms
        while (left > 0) {
            if (shouldStop()) return
            val step = minOf(left, 200L)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                return
            }
            left -= step
        }
    }
}

/**
 * 解析出来的节点信息。既支持 vless 也支持 vmess。
 *
 * protocol 字段区分协议，其他字段对两者都通用。
 * vmess 特有：alterId（aid）、scy（encryption 类型，如 auto/aes-128-gcm）。
 * vless 特有：encryption 恒为 "none"。
 */
data class VlessNode(
    val raw: String,
    val protocol: String = "vless",  // "vless" | "vmess"
    val uuid: String,
    val server: String,
    val port: Int,
    val security: String,
    val sni: String,
    val path: String,
    val mode: String,
    val type: String,
    val network: String,
    val alpn: List<String>,
    val encryption: String = "none",
    val alterId: Int = 0,             // vmess only
    val scy: String = "auto",         // vmess only
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    val fp: String = "chrome"
)

/**
 * 一条链接的验证结果。
 *
 * @property address 节点服务器地址（IP 或域名），用户复制用的就是这个
 * @property detail  每次尝试的 RTT，(rttMs, 备注)，rtt < 0 表示失败
 */
data class VerifyResult(
    val link: String,
    val label: String,
    val address: String,
    val bestMs: Long,
    val fails: Int,
    val attempts: Int,
    val dead: Boolean,
    val earlyStopped: Boolean,
    val detail: List<Pair<Long, String>>
) {
    val good: Boolean get() = !dead && bestMs >= 0
}
