package com.cloudflare.speedtest.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import android.os.Handler
import android.os.Looper
import com.cloudflare.speedtest.core.IPTestHelper
import com.cloudflare.speedtest.core.IPTestHelper.extractRegionCode1
import com.cloudflare.speedtest.core.RegionMatcher
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class SharedViewModel : ViewModel() {

    // 存储选中的CIDR列表
    private val _selectedCIDRs = MutableLiveData<Set<String>>(emptySet())
    val selectedCIDRs: LiveData<Set<String>> get() = _selectedCIDRs

    // 测试参数
    private val _testThreads = MutableLiveData(100)
    private val _testDStart = MutableLiveData("LAX")
    private val _testDInterval = MutableLiveData(55)
    private val _testPort = MutableLiveData(DEFAULT_TEST_PORT)
    val testThreads: LiveData<Int> get() = _testThreads
    val testDStart: LiveData<String> get() = _testDStart
    val testDInterval: LiveData<Int> get() = _testDInterval
    val testPort: LiveData<Int> get() = _testPort

    companion object {
        /** 默认测试端口（TCP 延迟测试 + 下载测速）。 */
        const val DEFAULT_TEST_PORT = 443
    }

    // 测试状态
    private val _testStatus = MutableLiveData<TestStatus>()
    val testStatus: LiveData<TestStatus> get() = _testStatus

    // 测试结果
    private val _testResults = MutableLiveData<List<TestResult>>(emptyList())
    val testResults: LiveData<List<TestResult>> get() = _testResults

    // 协程作用域
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 任务控制（isTesting / shouldStop 由 UI 线程写、工作线程读，必须 volatile）
    @Volatile private var isTesting = false
    @Volatile private var shouldStop = false

    // 线程池
    private var executor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 待取消的任务（两阶段分开存，停止时都要取消）
    @Volatile private var phase1Futures: List<Future<*>>? = null
    @Volatile private var phase2Futures: List<Future<*>>? = null


    // 数据类
    data class ABItem(val ab: String, var isSelected: Boolean = false)

    data class TestResult(
        val ip: String,
        val latency: Long, // 毫秒
        val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean,
        val region: String = "" // CF 节点所在地（FRA/LAX/HKG...），来自第一阶段的 CF-RAY
    )

    data class TestStatus(
        val isRunning: Boolean = false,
        val progress: Float = 0f,
        val total: Int = 0,
        val completed: Int = 0,
        val message: String = ""
    )

    // 更新选中的CIDR列表
    fun updateSelectedCIDRs(cidrs: Set<String>) {
        _selectedCIDRs.value = cidrs
    }

    // 展开CIDR为A.B格式列表（按选中集合缓存，勾选时不必每次全量重算）
    private var expandCacheKey: String? = null
    private var expandCacheValue: List<ABItem> = emptyList()

    fun expandCIDRs(): List<ABItem> {
        val cidrs = _selectedCIDRs.value ?: return emptyList()
        val key = cidrs.joinToString(",")
        if (key == expandCacheKey) return expandCacheValue

        val uniqueMap = LinkedHashMap<String, ABItem>()
        cidrs.forEach { cidr ->
            expandSingleCIDR(cidr).forEach { uniqueMap[it.ab] = it }
        }

        val result = uniqueMap.values.sortedBy { it.ab }
        expandCacheKey = key
        expandCacheValue = result
        return result
    }

    // 展开单个CIDR为A.B格式列表
    private fun expandSingleCIDR(cidr: String): List<ABItem> {
        val result = mutableListOf<ABItem>()

        try {
            val parts = cidr.split("/")
            if (parts.size != 2) return emptyList()

            val ip = parts[0]
            val mask = parts[1].toInt()

            val ipParts = ip.split(".").map { it.toInt() }
            if (ipParts.size != 4) return emptyList()

            val (a, b, c, d) = ipParts

            // 将IP转换为32位整数
            val ipInt = (a shl 24) or (b shl 16) or (c shl 8) or d

            // 计算网络地址和广播地址
            val networkMask = if (mask == 0) 0 else (-1 shl (32 - mask))
            val network = ipInt and networkMask
            val broadcast = network or (networkMask.inv())

            // 根据掩码展开A.B
            when {
                mask <= 8 -> {
                    // 展开第一个字节
                    val startA = (network shr 24) and 0xFF
                    val endA = (broadcast shr 24) and 0xFF
                    for (newA in startA..endA) {
                        for (newB in 0..255) {
                            result.add(ABItem("$newA.$newB"))
                        }
                    }
                }
                mask <= 16 -> {
                    // 展开第二个字节
                    val startA = (network shr 24) and 0xFF
                    val startB = (network shr 16) and 0xFF
                    val endB = (broadcast shr 16) and 0xFF
                    for (newB in startB..endB) {
                        result.add(ABItem("$startA.$newB"))
                    }
                }
                mask <= 24 -> {
                    // 展开第三个字节（A.B格式）
                    val startA = (network shr 24) and 0xFF
                    val startB = (network shr 16) and 0xFF
                    result.add(ABItem("$startA.$startB"))
                }
                else -> {
                    // 掩码大于24，只显示A.B
                    result.add(ABItem("$a.$b"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    // 更新测试参数
    fun updateTestParams(threads: Int, dStart: String, dInterval: Int, port: Int = DEFAULT_TEST_PORT) {
        _testThreads.value = threads.coerceIn(0, 200)
        _testDInterval.value = dInterval.coerceIn(1, 255)
        _testPort.value = port.coerceIn(1, 65535)
        _testDStart.value = dStart
    }

    /**
     * 拼「测试完成」的状态文案，附带各 CF 区域分布。
     *
     * 为什么要突出区域：实测发现决定 IP 能不能用的不是 HTTP 状态码，
     * 而是 Cloudflare 节点所在地（同一段 CF IP 有的能通、有的全挂，差别就在区域）。
     * 所以扫完之后按区域聚合给你看，你才知道该往哪个大区找。
     */
    private fun buildMessage(results: List<TestResult>): String {
        val ok = results.count { it.success }
        val fail = results.count { !it.success }
        val sb = StringBuilder("测试完成！共 $ok 个IP连通、$fail 个失败")
        if (results.isEmpty()) {
            sb.append("\n没有IP通过延迟测试。把「过滤区域」填成 * 再扫一次看看。")
            return sb.toString()
        }
        val byRegion = results
            .groupBy { it.region.ifEmpty { "未知区域" } }
            .map { "${it.key}:${it.value.size}" }
            .sortedByDescending { s -> s.substringAfter(':').toIntOrNull() ?: 0 }
        sb.append("\n各区域IP数：${byRegion.joinToString("  ")}")
        sb.append("\n提示：挑IP先看区域。同区域的CF节点行为接近，跨大区换IP往往没用；")
        sb.append("\n结果已按延迟排序并写入CSV，CSV里带区域列，可以直接分组挑。")
        return sb.toString()
    }

    // 开始测试 - 使用协程和异步处理，正确使用线程数
    // 开始测试 - 使用线程池
    fun startTest(selectedABs: List<String>) {
        if (isTesting) {
            return
        }

        // 停止之前的测试
        stopTest()

        shouldStop = false
        isTesting = true

        // 创建新的线程池
        val threads = _testThreads.value ?: 10
        executor = Executors.newFixedThreadPool(threads)

        mainHandler.post {
            _testStatus.value = TestStatus(
                isRunning = true,
                progress = 0f,
                total = 0,
                completed = 0,
                message = "准备测试中..."
            )
        }

        // 在线程池中执行测试准备
        executor?.execute {
            try {
                val selectedCIDRs = _selectedCIDRs.value ?: emptySet()
                // 「过滤区域」支持多个模式（`,`/`;`/`|`/空格 分隔）+ `*`/`?` 通配符，
                // 例如 `LAX,HKG`、`中国*`、`*`（* 单独出现 = 不过滤，任意国家地区）
                val includePattern = RegionMatcher.compile(_testDStart.value)
                val dInterval = _testDInterval.value ?: 8
                val port = _testPort.value ?: DEFAULT_TEST_PORT

                // 注意：这里刻意不做「拿你的域名发 HTTP 请求看返回码」那套端点验证。
                // 实测证明它判别不了 IP 好不好用：
                //   162.159.160.214 (FRA) 返回 521 → 代理正常
                //   162.159.245.156 (LAX) 返回 521 → 代理不通
                // 同一个 521 既可能通也可能不通，HTTP 返回码没有判别力，
                // 用它过滤只会把能用的 IP 当成坏的扔掉。
                // 真正决定能不能用的是 CF 节点区域，已经在第一阶段拿到了。

                // 生成测试IP列表
                val testIPs = generateTestIPs(selectedABs, selectedCIDRs, 254)

                if (testIPs.isEmpty()) {
                    mainHandler.post {
                        _testStatus.value = TestStatus(
                            isRunning = false,
                            message = "没有可测试的IP地址"
                        )
                        isTesting = false
                    }
                    return@execute
                }

                mainHandler.post {
                    _testStatus.value = TestStatus(
                        isRunning = true,
                        total = testIPs.size,
                        completed = 0,
                        message = "开始测试 ${testIPs.size} 个IP，使用 $threads 个线程..."
                    )
                }

                // ===== 第一阶段：筛选区域，生成 testIPs2 =====
                val testIPs2 = Collections.synchronizedList(mutableListOf<String>())
                val phase1Latch = CountDownLatch(testIPs.size)
                val phase1FutureList = Collections.synchronizedList(mutableListOf<Future<*>>())

                // 记下每个 IP 的 CF 区域（FRA/LAX/HKG...），结果汇总和 CSV 都要用到。
                // 这是挑 IP 唯一真正有用的字段：实测能不能用基本由区域决定，
                // 跟 HTTP 返回码无关。
                val regionOfIp = java.util.Collections.synchronizedMap(
                    java.util.HashMap<String, String>()
                )

                mainHandler.post {
                    _testStatus.value = TestStatus(
                        isRunning = true,
                        total = testIPs.size,
                        completed = 0,
                        message = "第一阶段：筛选区域 ${testIPs.size} 个IP..."
                    )
                }

                for (ip in testIPs) {
                    if (shouldStop) break

                    val future = executor?.submit {
                        try {
                            if (shouldStop) return@submit

                            val sRegion = testTCPConnectRegion(ip, 8080)

                            // 区域是 IP 的属性，先记下来，第二阶段生成结果时带上
                            if (sRegion.isNotEmpty()) regionOfIp[ip] = sRegion

                            if (RegionMatcher.matches(includePattern, sRegion)) {
                                // addAll 在 synchronizedList 上不是原子的，逐条 add 才安全
                                generateTestIPs(ip, selectedCIDRs, dInterval)
                                    .forEach { testIPs2.add(it) }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            phase1Latch.countDown()
                        }
                    }

                    future?.let { phase1FutureList.add(it) }
                }

                // 记下第一阶段任务，stopTest() 才能取消它们
                phase1Futures = phase1FutureList

                phase1Latch.await()

                if (shouldStop) {
                    mainHandler.post {
                        _testStatus.value = TestStatus(isRunning = false, message = "测试已停止")
                    }
                    return@execute
                }

                // ===== 第二阶段：测试 testIPs2 的延迟 =====
                val results = Collections.synchronizedList(mutableListOf<TestResult>())
                val totalTest = testIPs2.size
                val completedCount = AtomicInteger(0)
                val phase2Latch = CountDownLatch(totalTest)
                val phase2FutureList = mutableListOf<Future<*>>()

                if (totalTest == 0) {
                    mainHandler.post {
                        _testStatus.value = TestStatus(
                            isRunning = false,
                            message = "第一阶段筛选后没有匹配的IP（跟域名无关，端点验证根本没跑到）。\n" +
                                "最常见原因：区域探测固定走 8080 端口，你网络连不上它，" +
                                "全部IP被识别成「未知地区」然后被「过滤区域」刷掉了。\n" +
                                "把过滤区域填成 * 再扫一次。"
                        )
                        isTesting = false
                    }
                    return@execute
                }

                mainHandler.post {
                    _testStatus.value = TestStatus(
                        isRunning = true,
                        total = totalTest,
                        completed = 0,
                        message = "第二阶段：测试 ${totalTest} 个IP延迟，端口 $port，使用 $threads 个线程..."
                    )
                }

                for (ip in testIPs2) {
                    if (shouldStop) break

                    val future = executor?.submit {
                        try {
                            if (shouldStop) {
                                phase2Latch.countDown()
                                return@submit
                            }

                            val latency = testTCPConnect(ip, port)

                            // 直接进结果，不带区域过滤。区域信息从第一阶段带上，
                            // 让你扫完能自己按区域挑，而不是被一个判别不了好坏的过滤器删掉。
                            results.add(TestResult(
                                ip = ip,
                                latency = latency,
                                success = latency > 0,
                                region = regionOfIp[ip] ?: ""
                            ))

                            val currentCompleted = completedCount.incrementAndGet()
                            if (currentCompleted % 50 == 0 || currentCompleted == totalTest) {
                                val progress = currentCompleted.toFloat() / totalTest
                                mainHandler.post {
                                    _testStatus.value = TestStatus(
                                        isRunning = true,
                                        progress = progress,
                                        total = totalTest,
                                        completed = currentCompleted,
                                        message = "已测试: $currentCompleted/$totalTest (${String.format("%.1f", progress * 100)}%)"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            phase2Latch.countDown()
                        }
                    }

                    future?.let { phase2FutureList.add(it) }
                }

                phase2Futures = phase2FutureList

                phase2Latch.await()

                if (!shouldStop) {
                    // 测试完成，排序结果
                    val sortedResults = results.sortedBy { it.latency }

                    mainHandler.post {
                        _testResults.value = sortedResults
                        _testStatus.value = TestStatus(
                            isRunning = false,
                            progress = 1f,
                            total = totalTest,
                            completed = totalTest,
                            message = buildMessage(sortedResults)
                        )
                    }

                    // 保存结果到文件
                    saveResultsToFile(sortedResults)
                } else {
                    mainHandler.post {
                        _testStatus.value = TestStatus(
                            isRunning = false,
                            message = "测试已停止"
                        )
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    _testStatus.value = TestStatus(
                        isRunning = false,
                        message = "测试出错: ${e.message}"
                    )
                }
            } finally {
                mainHandler.post {
                    isTesting = false
                    shouldStop = false
                }
            }
        }
    }

    // 停止测试
    fun stopTest() {
        shouldStop = true
        isTesting = false

        // 取消所有未完成的任务（两阶段都要取消，否则第一阶段会跑完才停）
        cancelAllFutures()

        // 关闭线程池
        executor?.shutdownNow()
        executor = null

        mainHandler.post {
            _testStatus.value = TestStatus(
                isRunning = false,
                message = "测试已停止"
            )
        }
    }

    /** 取消两个阶段里所有还没结束的任务。 */
    private fun cancelAllFutures() {
        val f1 = phase1Futures
        val f2 = phase2Futures
        phase1Futures = null
        phase2Futures = null

        for (future in f1.orEmpty()) {
            if (!future.isDone) future.cancel(true)
        }
        for (future in f2.orEmpty()) {
            if (!future.isDone) future.cancel(true)
        }
    }


    // 生成测试IP列表
    private fun generateTestIPs(
        selectedABs: List<String>,
        selectedCIDRs: Set<String>,
        dInterval: Int
    ): List<String> {
        val result = mutableListOf<String>()

        // 将CIDR转换为IP范围集合
        val cidrRanges = selectedCIDRs.mapNotNull { parseCIDR(it) }

        selectedABs.forEach { ab ->
            val (a, b) = ab.split(".").map { it.toInt() }

            // 为每个A.B生成C.D
            for (c in 0..255) {
                if (shouldStop) return@forEach

                var d = Random.nextInt(1, dInterval + 1)
                while (d <= 255) {
                    val ip = "$a.$b.$c.$d"

                    // 检查IP是否在任意CIDR范围内
                    if (isInCIDRRanges(ip, cidrRanges)) {
                        result.add(ip)
                    }

                    d += dInterval
                }
            }
        }

        return result
    }

    private fun generateTestIPs(
        selectedABCs: String,
        selectedCIDRs: Set<String>,
        dInterval: Int
    ): List<String> {
        val result = mutableListOf<String>()

        // 将CIDR转换为IP范围集合
        val cidrRanges = selectedCIDRs.mapNotNull { parseCIDR(it) }

        val (a, b,c,k) = selectedABCs.split(".").map { it.toInt() }

        var d = Random.nextInt(1, dInterval + 1)
        while (d <= 255) {
            val ip = "$a.$b.$c.$d"

            // 检查IP是否在任意CIDR范围内
            if (isInCIDRRanges(ip, cidrRanges)) {
                result.add(ip)
            }

            d += dInterval
        }

        return result
    }

    // 解析CIDR为范围
    private data class IPRange(val start: Long, val end: Long)

    /**
     * 解析 CIDR 为 IP 范围。
     *
     * 用 Long 做位运算：旧实现用 Int，当首段 ≥ 128 时 `shl 24` 溢出成负数，
     * `.toLong()` 又会符号扩展，导致 192.x / 203.x 这类常见网段算出的
     * start > end，范围直接失效。
     */
    private fun parseCIDR(cidr: String): IPRange? {
        return try {
            val parts = cidr.split("/")
            if (parts.size != 2) return null

            val ipLong = ipToLong(parts[0]) ?: return null
            val mask = parts[1].trim().toIntOrNull() ?: return null
            if (mask !in 0..32) return null

            val hostBits = 32 - mask
            val hostCount = if (hostBits >= 32) 0xFFFFFFFFL else ((1L shl hostBits) - 1L)
            val network = ipLong and (0xFFFFFFFFL - hostCount)
            IPRange(network, network + hostCount)
        } catch (e: Exception) {
            null
        }
    }

    // 检查IP是否在CIDR范围内
    private fun isInCIDRRanges(ip: String, ranges: List<IPRange>): Boolean {
        val ipLong = ipToLong(ip) ?: return false
        return ranges.any { ipLong in it.start..it.end }
    }

    /** IPv4 → Long（0..0xFFFFFFFF）；格式非法返回 null。 */
    private fun ipToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val octet = p.trim().toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            v = (v shl 8) or octet.toLong()
        }
        return v
    }

    // TCP连接测试
    private fun testTCPConnect(ip: String, port: Int, timeout: Int = 3000): Long {
        return try {
            val socket = Socket()
            val startTime = System.currentTimeMillis()

            // 设置连接超时
            socket.connect(InetSocketAddress(ip, port), timeout)

            val endTime = System.currentTimeMillis()
            socket.close()
            endTime - startTime
        } catch (e: Exception) {
            -1L
        }
    }

    private fun testTCPConnectRegion(ip: String, port: Int,timeout: Int = 5000): String {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.soTimeout = timeout

            // 1️⃣ TCP connect
            socket.connect(InetSocketAddress(ip, port), timeout)

            // 2️⃣ 发送最小 HTTP 请求（Cloudflare 能识别）
            val request = buildString {
                append("GET / HTTP/1.1\r\n")
                append("Host: $ip\r\n")
                append("User-Agent: curl/8.7.1\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            val out = socket.getOutputStream()
            out.write(request.toByteArray())
            out.flush()

            // 3️⃣ 只需要响应头里的 CF-RAY：读到空行就停，
            //    旧实现会把整个响应体缓存进内存，大响应会白白吃光内存
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            var cfRay: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("CF-RAY:", ignoreCase = true)) {
                    cfRay = line.substringAfter(':').trim()
                    break
                }
            }

            if (cfRay != null) IPTestHelper.extractRegionCode1(cfRay) else "unknown"
        } catch (e: Exception) {
            "unknown"
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }


    // 保存结果到文件
    // 保存结果到固定路径
    private fun saveResultsToFile(results: List<TestResult>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "iptestresult.csv"

                val content = StringBuilder()

                results.forEach { result ->
                    val status = if (result.success) "成功" else "失败"
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(result.timestamp))
                    val region = result.region.ifEmpty { "未知区域" }
                   if(result.success&&result.latency<5000)content.append("${result.ip},${region},${result.latency},$status,$time\n")
                }

                // 保存到应用的files目录
                val context = MyApplication.instance
                val file = context.getFileStreamPath(fileName)
                file.writeText(content.toString())

                // 发送广播通知文件已保存
                withContext(Dispatchers.Main) {
                    _testStatus.value = TestStatus(
                        isRunning = false,
                        message = "结果已保存到: ${file.absolutePath}"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 清空数据
    fun clear() {
        cancelAllFutures()
        executor?.shutdownNow()
        executor = null
        _selectedCIDRs.value = emptySet()
        expandCacheKey = null
        _testStatus.value = TestStatus(isRunning = false)
        isTesting = false
        shouldStop = false
    }

    override fun onCleared() {
        super.onCleared()
        shouldStop = true
        isTesting = false
        cancelAllFutures()
        executor?.shutdownNow()
        executor = null
        viewModelScope.cancel()
    }
}

// 创建Application类以获取Context
class MyApplication : android.app.Application() {
    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
