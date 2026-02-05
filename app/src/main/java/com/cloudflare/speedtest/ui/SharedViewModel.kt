package com.cloudflare.speedtest.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel  // 添加这行
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class SharedViewModel : ViewModel() {

    // 存储选中的CIDR列表
    private val _selectedCIDRs = MutableLiveData<Set<String>>(emptySet())
    val selectedCIDRs: LiveData<Set<String>> get() = _selectedCIDRs

    // 存储展开的A.B格式IP列表
    private val _expandedABs = MutableLiveData<List<ABItem>>(emptyList())
    val expandedABs: LiveData<List<ABItem>> get() = _expandedABs

    // 测试参数
    private val _testThreads = MutableLiveData(10)
    private val _testDStart = MutableLiveData(1)
    private val _testDInterval = MutableLiveData(8)
    val testThreads: LiveData<Int> get() = _testThreads
    val testDStart: LiveData<Int> get() = _testDStart
    val testDInterval: LiveData<Int> get() = _testDInterval

    // 测试状态
    private val _testStatus = MutableLiveData<TestStatus>()
    val testStatus: LiveData<TestStatus> get() = _testStatus

    // 测试结果
    private val _testResults = MutableLiveData<List<TestResult>>(emptyList())
    val testResults: LiveData<List<TestResult>> get() = _testResults

    // 协程作用域
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 任务控制
    private var testJob: Job? = null
    private var isTesting = false
    private var shouldStop = false

    // 线程池
    private var executor: ExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 任务控制
    private var futures: List<Future<*>>? = null


    // 数据类
    data class ABItem(val ab: String, var isSelected: Boolean = false)

    data class TestResult(
        val ip: String,
        val latency: Long, // 毫秒
        val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean
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

    // 展开CIDR为A.B格式列表
    fun expandCIDRs(): List<ABItem> {
        val cidrs = _selectedCIDRs.value ?: return emptyList()
        val result = mutableListOf<ABItem>()

        cidrs.forEach { cidr ->
            result.addAll(expandSingleCIDR(cidr))
        }

        // 去重
        val uniqueMap = mutableMapOf<String, ABItem>()
        result.forEach { item ->
            uniqueMap[item.ab] = item
        }

        return uniqueMap.values.sortedBy { it.ab }
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
    fun updateTestParams(threads: Int, dStart: Int, dInterval: Int) {
        _testThreads.value = threads.coerceIn(0, 200)
        _testDStart.value = dStart.coerceIn(0, 255)
        _testDInterval.value = dInterval.coerceIn(1, 254)
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
                val dStart = _testDStart.value ?: 1
                val dInterval = _testDInterval.value ?: 8

                // 生成测试IP列表
                val testIPs = generateTestIPs(selectedABs, selectedCIDRs, dStart, dInterval)

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

                // 准备测试任务
                val results = Collections.synchronizedList(mutableListOf<TestResult>())
                val completedCount = AtomicInteger(0)
                val latch = CountDownLatch(testIPs.size)
                val futuresList = mutableListOf<Future<*>>()

                // 为每个IP创建测试任务
                for (ip in testIPs) {
                    if (shouldStop) break

                    val future = executor?.submit {
                        try {
                            if (shouldStop) {
                                latch.countDown()
                                return@submit
                            }

                            val latency = testTCPConnect(ip, 443)
                            val result = TestResult(
                                ip = ip,
                                latency = latency,
                                success = latency > 0
                            )

                            results.add(result)

                            // 更新进度
                            val currentCompleted = completedCount.incrementAndGet()
                            if (currentCompleted % 50 == 0 || currentCompleted == testIPs.size) {
                                val progress = currentCompleted.toFloat() / testIPs.size
                                mainHandler.post {
                                    _testStatus.value = TestStatus(
                                        isRunning = true,
                                        progress = progress,
                                        total = testIPs.size,
                                        completed = currentCompleted,
                                        message = "已测试: $currentCompleted/${testIPs.size} (${String.format("%.1f", progress * 100)}%)"
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            latch.countDown()
                        }
                    }

                    future?.let { futuresList.add(it) }
                }

                futures = futuresList

                // 等待所有任务完成
                latch.await()

                if (!shouldStop) {
                    // 测试完成，排序结果
                    val sortedResults = results.sortedBy { it.latency }

                    mainHandler.post {
                        _testResults.value = sortedResults
                        _testStatus.value = TestStatus(
                            isRunning = false,
                            progress = 1f,
                            total = testIPs.size,
                            completed = testIPs.size,
                            message = "测试完成！成功: ${sortedResults.count { it.success }}, 失败: ${sortedResults.count { !it.success }}"
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

        // 取消所有未完成的任务
        futures?.forEach { future ->
            if (!future.isDone) {
                future.cancel(true)
            }
        }
        futures = null

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


    // 更新测试进度
    private suspend fun updateTestProgress(progress: Float, completed: Int, total: Int) {
        withContext(Dispatchers.Main) {
            _testStatus.value = TestStatus(
                isRunning = true,
                progress = progress,
                total = total,
                completed = completed,
                message = "已测试: $completed/$total (${String.format("%.1f", progress * 100)}%)"
            )
        }
    }


    // 生成测试IP列表
    private fun generateTestIPs(
        selectedABs: List<String>,
        selectedCIDRs: Set<String>,
        dStart: Int,
        dInterval: Int
    ): List<String> {
        val result = mutableListOf<String>()

        // 将CIDR转换为IP范围集合
        val cidrRanges = selectedCIDRs.mapNotNull { parseCIDR(it) }

        selectedABs.forEach { ab ->
            val (a, b) = ab.split(".").map { it.toInt() }

            // 为每个A.B生成C.D
            for (c in 0..255) {
                if (shouldStop) break

                var d = dStart
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

    // 解析CIDR为范围
    private data class IPRange(val start: Long, val end: Long)

    private fun parseCIDR(cidr: String): IPRange? {
        return try {
            val parts = cidr.split("/")
            val ip = parts[0]
            val mask = parts[1].toInt()

            val ipParts = ip.split(".").map { it.toInt() }
            val ipLong = (ipParts[0] shl 24) or (ipParts[1] shl 16) or
                    (ipParts[2] shl 8) or ipParts[3]

            val networkMask = if (mask == 0) 0 else (-1 shl (32 - mask))
            val network = ipLong and networkMask
            val broadcast = network or (networkMask.inv())

            IPRange(network.toLong(), broadcast.toLong())
        } catch (e: Exception) {
            null
        }
    }

    // 检查IP是否在CIDR范围内
    private fun isInCIDRRanges(ip: String, ranges: List<IPRange>): Boolean {
        val ipLong = ipToLong(ip) ?: return false
        return ranges.any { ipLong in it.start..it.end }
    }

    private fun ipToLong(ip: String): Long? {
        return try {
            val parts = ip.split(".").map { it.toInt() }
            (parts[0] shl 24).toLong() or
                    ((parts[1] shl 16).toLong()) or
                    ((parts[2] shl 8).toLong()) or
                    parts[3].toLong()
        } catch (e: Exception) {
            null
        }
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
                   if(result.success&&result.latency<1000)content.append("${result.ip},${result.latency},$status,$time\n")
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
        _selectedCIDRs.value = emptySet()
        _expandedABs.value = emptyList()
        testJob?.cancel()
        _testStatus.value = TestStatus(isRunning = false)
        isTesting = false
        shouldStop = false
    }

    override fun onCleared() {
        super.onCleared()
        testJob?.cancel()
        viewModelScope.cancel()
        isTesting = false
        shouldStop = false
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