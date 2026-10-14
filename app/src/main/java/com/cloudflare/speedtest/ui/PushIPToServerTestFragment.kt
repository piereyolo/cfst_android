package com.cloudflare.speedtest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection


import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.*



import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

import com.cloudflare.speedtest.core.IPTestHelper
import com.cloudflare.speedtest.core.RegionData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.forEach
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.cloudflare.speedtest.core.ProxyParser
import com.cloudflare.speedtest.ui.ResultFragment.ResultItem
import com.cloudflare.speedtest.ui.SharedViewModel.TestResult
import java.util.Collections


object OkHttpHelper {

    // 创建信任所有证书的 OkHttpClient（仅用于测试）
    fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            // 创建一个信任所有证书的 TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 安装信任所有证书的 SSLContext
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // 创建不验证主机名的 HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    // 创建普通 OkHttpClient
    fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

class PushIPToServerTestFragment: Fragment() {

    private lateinit var etNodeLink: EditText
    private lateinit var etMinKb: EditText
    private lateinit var etProbeTarget: EditText
    private lateinit var btnExportLinks: Button

    private lateinit var btnLocalTestHttping: Button

    private lateinit var stopTestDownloadButton: Button

    private lateinit var recyclerView: RecyclerView
    private var tvStatus: TextView? = null
    private lateinit var adapter: ResultAdapter

    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 后台任务作用域：不用 GlobalScope（脱离了任何生命周期、无法取消），
    // 改用跟随 Fragment 实例的作用域，onDestroy 时统一取消
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 共享 ViewModel（Activity 作用域）：读取「IP测试」页配置的端口
    private var sharedViewModel: SharedViewModel? = null

    /** 测速端口，跟「IP测试」页的「测试端口」保持一致。 */
    private fun testPort(): Int {
        val vm = sharedViewModel ?: return IPTestHelper.DEFAULT_TARGET_PORT
        return vm.testPort.value ?: IPTestHelper.DEFAULT_TARGET_PORT
    }

    /**
     * 解析「探测目标」输入框。
     *
     * 填了 VLESS/Trojan 链接或 `host:port/path` 就用它当探测目标（这样 SNI/端口/路径/
     * Host 头都会换成代理配置里那套）；留空则回落到默认的 Cloudflare 官方测速端点，
     * 端口用「IP测试」页配置的值。
     *
     * 返回 null 表示用户填了但解析不出来，调用方应中止测试。
     */
    private fun resolveProbeTarget(fallbackPort: Int): IPTestHelper.ProbeTarget? {
        val raw = etProbeTarget.text.toString().trim()
        if (raw.isEmpty()) return IPTestHelper.defaultTargetWithPort(fallbackPort)

        val target = IPTestHelper.parseProbeTarget(raw) ?: return null
        Log.w("PushTest", "探测目标: $target")
        return target
    }

    private  var bStartDownloadTest=true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ip_push_testserver, container, false)

        // 共享 ViewModel（Activity 作用域），同一个实例，端口配置即时生效
        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        // 初始化视图
        initViews(view)

        return view
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.rvAbPrefixes)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnExportLinks = view.findViewById(R.id.btnExportLinks)
        btnLocalTestHttping = view.findViewById(R.id.btnLocalTestHttping)
        stopTestDownloadButton = view.findViewById(R.id.stopTestDownloadButton)

        etNodeLink = view.findViewById(R.id.etNodeLink)
        etMinKb = view.findViewById(R.id.etMinKb)
        etProbeTarget = view.findViewById(R.id.etProbeTarget)

        // 设置RecyclerView
        adapter = ResultAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { ip ->
            copyToClipboard(ip)
        }

        // 设置按钮点击事件
        btnExportLinks.setOnClickListener {
            exportLinksToClipboard()
        }


        btnLocalTestHttping.setOnClickListener {
            stopTestDownloadButton.isVisible = true
            btnLocalTestHttping.isEnabled = false
            btnLocalTestHttping.isVisible = false
            stopTestDownloadButton.isEnabled=true

            bStartDownloadTest=true
            LocalTestHttping()


        }

        stopTestDownloadButton.setOnClickListener {
            bStartDownloadTest=false
        }


    }


    /**
     * 把列表里速度达标的 IP 逐个替换进节点链接的 [[AABB]]，全部塞进剪切板。
     *
     * [[AABB]] 是**完整 IP** 的占位符，整段替换——
     * 比如链接是 `vless://xxxx@[[AABB]]:2096?...`，就会变成 `vless://xxxx@162.159.207.241:2096?...`。
     * 有几个 IP 就生成几条链接，每条一行，方便直接在客户端里挨个粘。
     * 只在内存里处理，不做任何持久化。
     */
    private fun exportLinksToClipboard() {
        val template = etNodeLink.text.toString().trim()
        if (template.isEmpty()) {
            Toast.makeText(requireContext(), "请先填入节点链接", Toast.LENGTH_SHORT).show()
            return
        }

        val minKb = etMinKb.text.toString().trim().toDoubleOrNull()
            ?: run {
                Toast.makeText(requireContext(), "最低速度不是数字", Toast.LENGTH_SHORT).show()
                return
            }

        val matched = adapter.getItemsAtLeast(minKb)
        if (matched.isEmpty()) {
            Toast.makeText(requireContext(), "没有速度大于 $minKb KB/s 的IP，先跑「本地下载测速」", Toast.LENGTH_SHORT).show()
            return
        }

        val links = matched.map { item ->
            ProxyParser.replaceAddress(template, item.ip.trim())
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("节点链接", links.joinToString("\n")))

        Toast.makeText(requireContext(), "已生成 ${links.size} 条链接到剪切板", Toast.LENGTH_LONG).show()
        updateStatus("已生成 ${links.size} 条链接（速度 > $minKb KB/s），已送入剪切板")
    }

    private fun LocalTestHttping() {
        // 从剪贴板获取IP列表
        var canExit=true
        try{
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show()
                return
            }

            val text = clipData.getItemAt(0).text.toString()
            val ipList = text.trim().split("\n").filter { it.isNotBlank() }

            if (ipList.isEmpty()) {
                Toast.makeText(requireContext(), "未找到有效IP", Toast.LENGTH_SHORT).show()
                return
            }

            canExit=false

            val port = testPort()

            // 探测目标：填了代理链接就用它，留空则回落到「IP测试」页的端口 + Cloudflare 官方端点
            val target = resolveProbeTarget(port) ?: run {
                Toast.makeText(requireContext(),
                    "无法解析「探测目标」：${etProbeTarget.text}", Toast.LENGTH_SHORT).show()
                return
            }

            // 显示开始测试的Toast
            Toast.makeText(requireContext(),
                "开始测试 ${ipList.size} 个IP → ${target}...", Toast.LENGTH_SHORT).show()
            val testresults = Collections.synchronizedList(mutableListOf<PushIPToServerTestFragment.TestResult>())
            // 使用协程进行异步顺序测试
            ioScope.launch {
                val results = mutableListOf<ResultItem>()
                var completedCount = 0

                // 顺序测试每个IP
                for (ip in ipList) {
                    if(!bStartDownloadTest)continue
                    val ipAddress = ip.trim()
                    // 测试当前IP
                    val speed = IPTestHelper.downloadWithSpeedTracking(ipAddress, 10, target)




                    results.add(ResultItem(ipAddress, speed.regionCode,speed.delay))
                    completedCount++

                    val tmpresult = TestResult(
                        ip = ipAddress,
                        regionCode = speed.regionCode,
                        latency = speed.delay,
                        success = true
                    )

                    testresults.add(tmpresult)




                    // 可选：更新进度到UI（每测试完一个IP更新一次）
                    withContext(Dispatchers.Main) {
                        // 更新适配器数据
                        val sortedResults = results.sortedByDescending { it.delay }


//                    testresults.sortByDescending { it.latency }
//                    saveDownloadResultsToFile(testresults)
                        adapter.setData(sortedResults)

                        // 可以在这里更新进度条或状态显示
                        // 例如：progressBar.progress = (completedCount * 100 / ipList.size)
                    }
                }

                // 全部测试完成后更新UI
                withContext(Dispatchers.Main) {
                    // 最终排序
                    val sortedResults = results.sortedByDescending { it.delay }
                    testresults.sortByDescending { it.latency }
                    saveDownloadResultsToFile(testresults)
                    adapter.setData(sortedResults)

                    // 显示测试结果摘要
                    val successfulCount = results.count { it.delay > 0 }
                    val maxSpeed = results.maxOfOrNull { it.delay } ?: 0

                    Toast.makeText(
                        requireContext(),
                        "测试完成！\n成功: $successfulCount/${ipList.size}\n最高速度: ${maxSpeed}\n",
                        Toast.LENGTH_LONG
                    ).show()

                    btnLocalTestHttping.isEnabled=true
                    stopTestDownloadButton.isVisible = false
                    stopTestDownloadButton.isEnabled=false
                    btnLocalTestHttping.isVisible = true

                }
            }
        }catch (e: Exception){

        }
        finally {
            if(canExit){
                btnLocalTestHttping.isEnabled=true
                stopTestDownloadButton.isVisible = false
                stopTestDownloadButton.isEnabled=false
                btnLocalTestHttping.isVisible = true
            }

        }


    }


    private fun copyToClipboard(ip: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("IP地址", ip)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(requireContext(), "已复制: $ip", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(message: String) {
        // 调用方大多在后台线程，统一切回主线程再更新 UI
        val tv = tvStatus ?: return
        val text = "状态: $message"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tv.text = text
        } else {
            tv.post { tv.text = text }
        }
    }

    data class TestResult(
        val ip: String,
        val regionCode: String,
        val latency: Long, // 毫秒
        val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean
    )

    private fun saveDownloadResultsToFile(results: List<TestResult>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "iptestdownloadresult.csv"

                val content = StringBuilder()

                results.forEach { result ->
                    val status = if (result.success) "成功" else "失败"
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(result.timestamp))
                    if(result.success)content.append("${result.ip},${result.latency},$status,${result.regionCode},$time\n")
                }

                // 保存到应用的files目录
                val context = MyApplication.instance
                val file = context.getFileStreamPath(fileName)
                file.writeText(content.toString())


            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun loadDownloadResultsFromFile() {
        Thread {
            try {
                //val filePath = "/storage/emulated/0/iptestresult.csv"
                //val file = File(filePath)

                val fileName = "iptestdownloadresult.csv"
                val context = requireContext()
                val file = File(context.filesDir, fileName) // 或使用 context.getFileStreamPath(fileName)

                if (!file.exists()) {

                    return@Thread
                }

                // 读取文件内容
                val lines = file.readLines()

                if (lines.size <= 0) { // 只有表头或为空

                    return@Thread
                }

                // 解析CSV数据（跳过表头）
                val results = mutableListOf<PushIPToServerTestFragment.ResultItem>()

                for (i in 0 until lines.size) {
                    val line = lines[i].trim()
                    if (line.isEmpty()) continue

                    // 简单的CSV解析（假设没有引号和转义）
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val ip = parts[0].trim()
                        val delay = parts[1].trim().toLongOrNull() ?: -1
                        val success = parts[2].trim() == "成功"
                        val regionCode = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: RegionData.LABEL_UNKNOWN

                        // 只添加成功且延迟小于阈值的项
                        if (success && delay>=0) {
                            results.add(ResultItem(ip,regionCode, delay))
                        }
                    }
                }

                results.sortByDescending { it.delay }


                requireActivity().runOnUiThread {

                    if (results.isEmpty()) {
                        recyclerView.visibility = View.VISIBLE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        adapter.setData(results)
                    }


                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    data class ResultItem(val ip: String, val regionCode:String,val delay: Long)

    // RecyclerView适配器
    inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

        private val items = mutableListOf<PushIPToServerTestFragment.ResultItem>()
        private var onItemClickListener: ((String) -> Unit)? = null

        fun setData(newItems: List<PushIPToServerTestFragment.ResultItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun GetAllData(): String {
            if (items.size > 0) {
                // 使用map提取每个ResultItem的ip，然后用joinToString添加换行符
                return items.map { it.ip }.joinToString("\n")
            } else {
                return "" // 如果列表为空，返回空字符串
            }
        }

        fun getAllIPs(): List<String> {
            return items.map { it.ip }
        }

        /** 速度（KB/s）高于阈值的条目，按速度从高到低排。 */
        fun getItemsAtLeast(minKb: Double): List<PushIPToServerTestFragment.ResultItem> {
            return items.filter { it.delay.toDouble() > minKb }.sortedByDescending { it.delay }
        }

        fun setOnItemClickListener(listener: (String) -> Unit) {
            this.onItemClickListener = listener
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ipTextView: TextView = itemView.findViewById(R.id.ipTextView)
            val regionTextView: TextView = itemView.findViewById(R.id.regionTextView)
            val delayTextView: TextView = itemView.findViewById(R.id.delayTextView)



            init {
                itemView.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemClickListener?.invoke(items[position].ip)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_puship_toserver, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ipTextView.text = item.ip
            holder.regionTextView.text=item.regionCode
            holder.delayTextView.text = "${item.delay}KB/s"
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消后台任务，避免 Fragment 销毁后任务继续跑
        viewModelScope.cancel()
        ioScope.cancel()
    }

    override fun onResume() {
        super.onResume()
        // 切回这个Tab时，列表为空就从上次保存的测速结果恢复
        if(adapter.itemCount<1){
            loadDownloadResultsFromFile()
        }

    }

}
