package com.cloudflare.speedtest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL


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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.forEach
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
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

    private lateinit var etUrl1: EditText
    private lateinit var etUrl2: EditText
    private lateinit var btnSaveUrls: Button
    private lateinit var btnSendRequest: Button
    private lateinit var btnGetResult: Button

    private lateinit var btnLocalTestHttping: Button

    private lateinit var stopTestDownloadButton: Button

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultAdapter

    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val urlFileName = "saved_urls.txt"

    private  var bStartDownloadTest=true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ip_push_testserver, container, false)

        // 初始化视图
        initViews(view)

        return view
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.rvAbPrefixes)
        btnSaveUrls = view.findViewById(R.id.btnSaveUrls)
        btnSendRequest = view.findViewById(R.id.btnSendRequest)
        btnGetResult = view.findViewById(R.id.btnGetResult)
        btnLocalTestHttping = view.findViewById(R.id.btnLocalTestHttping)
        stopTestDownloadButton = view.findViewById(R.id.stopTestDownloadButton)

        etUrl1 = view.findViewById(R.id.etUrl1)
        etUrl2 = view.findViewById(R.id.etUrl2)

        // 设置RecyclerView
        adapter = ResultAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        adapter.setOnItemClickListener { ip ->
            copyToClipboard(ip)
        }

        // 设置按钮点击事件
        btnSaveUrls.setOnClickListener {
            saveUrlsToFile()
        }

        btnSendRequest.setOnClickListener {
            sendPostRequest()
        }

        btnGetResult.setOnClickListener {
            sendGetRequest()
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

    private fun saveUrlsToFile() {
        val url1 = etUrl1.text.toString().trim()
        val url2 = etUrl2.text.toString().trim()

        if (url1.isEmpty() || url2.isEmpty()) {
            Toast.makeText(requireContext(), "请填写两个网址", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 将两个网址保存到文件，每行一个
            val content = "$url1\n$url2"
            val file = File(requireContext().filesDir, urlFileName)

            FileOutputStream(file).use { fos ->
                fos.write(content.toByteArray())
            }

            Toast.makeText(requireContext(), "网址已保存", Toast.LENGTH_SHORT).show()
            updateStatus("网址已保存到文件")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSavedUrls() {
        try {
            val file = File(requireContext().filesDir, urlFileName)
            if (file.exists()) {
                FileInputStream(file).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { reader ->
                        val url1 = reader.readLine() ?: ""
                        val url2 = reader.readLine() ?: ""

                        if (url1.isNotEmpty()) etUrl1.setText(url1)
                        if (url2.isNotEmpty()) etUrl2.setText(url2)

                        if (url1.isNotEmpty() || url2.isNotEmpty()) {
                            updateStatus("已加载保存的网址")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendPostRequest() {
        var url = etUrl1.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写POST请求网址", Toast.LENGTH_SHORT).show()
            return
        }

        // 获取剪切板数据
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(requireContext(), "剪切板中没有数据", Toast.LENGTH_SHORT).show()
            return
        }

        val clipData = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(requireContext(), "剪切板中没有数据", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboardText = clipData.getItemAt(0).text?.toString() ?: ""
        if (clipboardText.isEmpty()) {
            Toast.makeText(requireContext(), "剪切板内容为空", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("正在发送POST请求...")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // 创建 OkHttpClient
                val client = if (url.startsWith("https://")) {
                    OkHttpHelper.createOkHttpClient()
                } else {
                    // 对于 HTTP 请求，可能需要特殊处理
                    OkHttpHelper.createUnsafeOkHttpClient()
                }

                // 创建请求体
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = clipboardText.toRequestBody(mediaType)

                // 创建请求
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .build()

                // 执行请求
                val response = client.newCall(request).execute()
                val responseCode = response.code
                val responseBody = response.body?.string() ?: ""

                GlobalScope.launch(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(requireContext(), "POST请求成功", Toast.LENGTH_SHORT).show()
                        updateStatus("POST请求成功: $responseBody")
                    } else {
                        Toast.makeText(requireContext(), "POST请求失败: $responseCode", Toast.LENGTH_SHORT).show()
                        updateStatus("POST请求失败($responseCode): $responseBody")
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                GlobalScope.launch(Dispatchers.Main) {
                    val errorMsg = if (e.message?.contains("Cleartext HTTP traffic") == true) {
                        "HTTP请求被禁止，请添加网络配置或使用HTTPS"
                    } else {
                        "请求异常: ${e.message}"
                    }
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    updateStatus(errorMsg)
                }
            }
        }
    }

    private fun sendGetRequest() {
        val url = etUrl2.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写GET请求网址", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("正在发送GET请求...")

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream.bufferedReader().use { it.readText() }
                }

                connection.disconnect()

                // 解析返回的数据
                val resultItems = parseResponseData(response)

                GlobalScope.launch(Dispatchers.Main) {
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        if (resultItems.isNotEmpty()) {
                            adapter.setData(resultItems)
                            Toast.makeText(requireContext(), "获取到 ${resultItems.size} 条结果", Toast.LENGTH_SHORT).show()
                            updateStatus("获取到 ${resultItems.size} 条结果")
                        } else {
                            Toast.makeText(requireContext(), "返回数据为空或格式不正确", Toast.LENGTH_SHORT).show()
                            updateStatus("返回数据为空或格式不正确")
                        }
                    } else {
                        Toast.makeText(requireContext(), "GET请求失败: $responseCode", Toast.LENGTH_SHORT).show()
                        updateStatus("GET请求失败($responseCode): $response")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                GlobalScope.launch(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "请求异常: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateStatus("请求异常: ${e.message}")
                }
            }
        }
    }

    private fun parseResponseData(response: String): List<ResultItem> {
        val resultItems = mutableListOf<ResultItem>()

        if (response.isEmpty()) {
            return resultItems
        }

        val lines = response.trim().split("\n")
        for (line in lines) {
            val parts = line.trim().split(",")
            if (parts.size >= 2) {
                try {
                    val ip = parts[0].trim()
                    val delay = parts[1].trim().toLong()
                    val regionCode = parts[3].trim()?:"未知地区"
                    resultItems.add(ResultItem(ip,regionCode,delay))
                } catch (e: NumberFormatException) {
                    // 跳过格式不正确的行
                }
            }
        }

        return resultItems
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

            // 显示开始测试的Toast
            Toast.makeText(requireContext(), "开始测试 ${ipList.size} 个IP...", Toast.LENGTH_SHORT).show()
            val testresults = Collections.synchronizedList(mutableListOf<PushIPToServerTestFragment.TestResult>())
            // 使用协程进行异步顺序测试
            CoroutineScope(Dispatchers.IO).launch {
                val results = mutableListOf<ResultItem>()
                var completedCount = 0

                // 顺序测试每个IP
                for (ip in ipList) {
                    if(!bStartDownloadTest)continue
                    val ipAddress = ip.trim()
                    // 测试当前IP
                    val speed = IPTestHelper.downloadWithSpeedTracking(ipAddress, 10)




                    results.add(ResultItem(ipAddress, speed.regionCode,(speed.delay ?: 0.0).toLong()))
                    completedCount++

                    val tmpresult = TestResult(
                        ip = ipAddress,
                        regionCode = speed.regionCode,
                        latency = (speed.delay ?: 0.0).toLong(),
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
        //tvStatus.text = "状态: $message"
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
                        val regionCode = parts[3].trim() ?: "未知地区"

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

        fun setOnItemClickListener(listener: (String) -> Unit) {
            this.onItemClickListener = listener
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ipTextView: TextView = itemView.findViewById(R.id.ipTextView)
            val regionTextView: TextView = itemView.findViewById(R.id.regionTextView)
            val delayTextView: TextView = itemView.findViewById(R.id.delayTextView)



            init {
                itemView.setOnClickListener {
                    val position = adapterPosition
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

    override fun onResume() {
        super.onResume()
        // 每次切换到这个Tab时，重新加载数据
        loadSavedUrls()
        if(adapter.itemCount<1){
            loadDownloadResultsFromFile()
        }

    }

}