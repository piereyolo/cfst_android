package com.cloudflare.speedtest.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ResultFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ResultAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var etMaxDelay: EditText
    private lateinit var btnLoadResults: Button

    private lateinit var btnCopyAll: Button
    private lateinit var btnCheckAllIPResults: Button



    private lateinit var tvFileInfo: TextView
    private lateinit var tvStats: TextView

    private val executor: ExecutorService = Executors.newFixedThreadPool(10)
    private val handler = Handler(Looper.getMainLooper())
    // 默认延迟阈值
    private var maxDelayThreshold = 200

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_result, container, false)

        // 初始化视图
        initViews(view)

        return view
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.recyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        emptyView = view.findViewById(R.id.emptyView)
        etMaxDelay = view.findViewById(R.id.etMaxDelay)
        btnLoadResults = view.findViewById(R.id.btnLoadResults)
        btnCopyAll = view.findViewById(R.id.btnCopyAll)
        btnCheckAllIPResults = view.findViewById(R.id.btnCheckAllIPResults)

        tvFileInfo = view.findViewById(R.id.tvFileInfo)
        tvStats = view.findViewById(R.id.tvStats)

        // 设置默认值
        etMaxDelay.setText(maxDelayThreshold.toString())

        // 设置RecyclerView
        adapter = ResultAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // 设置列表项点击监听
        adapter.setOnItemClickListener { ip ->
            copyToClipboard(ip)
        }

        // 设置按钮点击事件
        btnLoadResults.setOnClickListener {
            val thresholdText = etMaxDelay.text.toString()
            if (thresholdText.isNotEmpty()) {
                maxDelayThreshold = thresholdText.toIntOrNull() ?: 200
                loadResultsFromFile()
            }
        }

        btnCheckAllIPResults.setOnClickListener {
            val allIPs = adapter.getAllIPs()
            if (allIPs.isNotEmpty()) {
                // 开始验证所有IP
                validateAllIPs(allIPs)
            }
        }

        btnCopyAll.setOnClickListener {
            val thresholdText = adapter.GetAllData()
            if (thresholdText.isNotEmpty()) {
               copyToClipboard(thresholdText)
            }
        }

        // 自动加载结果文件
        loadResultsFromFile()
    }


    private fun validateAllIPs(ips: List<String>) {
        btnCheckAllIPResults.isEnabled = false
        btnCheckAllIPResults.text = "验证中..."
        progressBar.visibility = View.VISIBLE

        val totalCount = ips.size
        val completedCount = AtomicInteger(0)

        ips.forEachIndexed { index, ip ->
            executor.submit {
                val isValid = testTCPConnect(ip, 443, 5000)

                handler.post {
                    // 更新该IP的验证状态
                    adapter.updateIPValidStatus(ip, if (isValid) 1 else 0)

                    val currentCompleted = completedCount.incrementAndGet()
                    if (currentCompleted == totalCount) {
                        // 所有验证完成
                        progressBar.visibility = View.GONE
                        btnCheckAllIPResults.isEnabled = true
                        btnCheckAllIPResults.text = "验证所有IP"

                        // 统计有效IP数量
                        val validCount = adapter.getValidIPsCount()
                        Toast.makeText(requireContext(),
                            "验证完成！有效IP: $validCount/$totalCount",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun loadResultsFromFile() {
        progressBar.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE

        Thread {
            try {
                //val filePath = "/storage/emulated/0/iptestresult.csv"
                //val file = File(filePath)

                val fileName = "iptestresult.csv"
                val context = requireContext()
                val file = File(context.filesDir, fileName) // 或使用 context.getFileStreamPath(fileName)

                if (!file.exists()) {
                    requireActivity().runOnUiThread {
                        progressBar.visibility = View.GONE
                        emptyView.text = "未找到结果文件\n文件路径: $fileName"
                        emptyView.visibility = View.VISIBLE
                        tvFileInfo.text = "文件状态: 不存在"
                        tvStats.text = "统计: 无数据"
                    }
                    return@Thread
                }

                // 读取文件内容
                val lines = file.readLines()

                if (lines.size <= 1) { // 只有表头或为空
                    requireActivity().runOnUiThread {
                        progressBar.visibility = View.GONE
                        emptyView.text = "结果文件为空"
                        emptyView.visibility = View.VISIBLE
                        tvFileInfo.text = "文件: ${file.name} (${file.length()} bytes)"
                        tvStats.text = "统计: 0个结果"
                    }
                    return@Thread
                }

                // 解析CSV数据（跳过表头）
                val results = mutableListOf<ResultItem>()

                for (i in 1 until lines.size) {
                    val line = lines[i].trim()
                    if (line.isEmpty()) continue

                    // 简单的CSV解析（假设没有引号和转义）
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val ip = parts[0].trim()
                        val delay = parts[1].trim().toLongOrNull() ?: -1
                        val success = parts[2].trim() == "成功"

                        // 只添加成功且延迟小于阈值的项
                        if (success && delay in 0..maxDelayThreshold) {
                            results.add(ResultItem(ip, delay))
                        }
                    }
                }

                // 按延迟排序（从小到大）
                results.sortBy { it.delay }

                requireActivity().runOnUiThread {
                    progressBar.visibility = View.GONE

                    if (results.isEmpty()) {
                        emptyView.text = "没有找到符合条件的测试结果\n(延迟 ≤ ${maxDelayThreshold}ms 且成功的IP)"
                        emptyView.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    } else {
                        emptyView.visibility = View.GONE
                        recyclerView.visibility = View.VISIBLE
                        adapter.setData(results)
                    }

                    // 更新文件信息和统计
                    tvFileInfo.text = "文件: ${file.name} (${file.length()} bytes, ${file.lastModified().toReadableTime()})"

                    val totalLines = lines.size - 1
                    val filteredCount = results.size
                    val successCount = lines.count { it.contains(",成功,") } - 1
                    val failedCount = totalLines - successCount

                    tvStats.text = "统计: 总共 {$totalLines} 个结果 | 成功{$successCount}个 | 失败{$failedCount}个 | 过滤后{$filteredCount}个"
                }

            } catch (e: Exception) {
                e.printStackTrace()
                requireActivity().runOnUiThread {
                    progressBar.visibility = View.GONE
                    emptyView.text = "读取文件失败: ${e.message}"
                    emptyView.visibility = View.VISIBLE
                    tvFileInfo.text = "错误: ${e.message}"
                    tvStats.text = "统计: 读取失败"
                }
            }
        }.start()
    }

    private fun testTCPConnect(
        ip: String,
        port: Int,
        timeout: Int = 3000
    ): Boolean {
        return run {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.soTimeout = timeout

                // 1️⃣ TCP connect
                socket.connect(InetSocketAddress(ip, port), timeout)

                val connectEndTime = System.currentTimeMillis()


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

                // 3️⃣ 尝试读取 1 个字节
                val input = socket.getInputStream()
                val firstByte = input.read()

                // -1 说明直接被断（RST / close）
                if (firstByte == -1) {
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                false
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun copyToClipboard(ip: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("IP地址", ip)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(requireContext(), "已复制: $ip", Toast.LENGTH_SHORT).show()
    }

    // 时间戳转可读时间
    private fun Long.toReadableTime(): String {
        val diff = System.currentTimeMillis() - this
        return when {
            diff < 60000 -> "刚刚" // 1分钟内
            diff < 3600000 -> "${diff / 60000}分钟前" // 1小时内
            diff < 86400000 -> "${diff / 3600000}小时前" // 1天内
            else -> "${diff / 86400000}天前" // 超过1天
        }
    }

    // 数据类
    data class ResultItem(val ip: String, val delay: Long, var valid: Int = 0)

    // RecyclerView适配器
    inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

        private val items = mutableListOf<ResultItem>()
        private var onItemClickListener: ((String) -> Unit)? = null

        fun setData(newItems: List<ResultItem>) {
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

        fun getValidIPsCount(): Int {
            return items.count { it.valid == 1 }
        }

        fun updateIPValidStatus(ip: String, valid: Int) {
            val index = items.indexOfFirst { it.ip == ip }
            if (index != -1) {
                items[index].valid = valid
                notifyItemChanged(index)
            }
        }

        fun setOnItemClickListener(listener: (String) -> Unit) {
            this.onItemClickListener = listener
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ipTextView: TextView = itemView.findViewById(R.id.ipTextView)
            val delayTextView: TextView = itemView.findViewById(R.id.delayTextView)

            val validTextView: TextView = itemView.findViewById(R.id.validTextView) // 添加这一行
            val delayBar: ProgressBar = itemView.findViewById(R.id.delayBar)

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
                .inflate(R.layout.item_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ipTextView.text = item.ip
            holder.delayTextView.text = "${item.delay}ms"
            holder.validTextView.text = item.valid.toString() // 设置validTextView的文本

            // 设置延迟进度条（最大值为阈值）
            holder.delayBar.max = maxDelayThreshold
            holder.delayBar.progress = item.delay.toInt()

            // 根据延迟设置不同的颜色
            when {
                item.delay < 50 -> holder.delayTextView.setTextColor(
                    requireContext().getColor(android.R.color.holo_green_dark)
                )
                item.delay < 100 -> holder.delayTextView.setTextColor(
                    requireContext().getColor(android.R.color.holo_orange_dark)
                )
                else -> holder.delayTextView.setTextColor(
                    requireContext().getColor(android.R.color.holo_red_dark)
                )
            }
        }

        override fun getItemCount(): Int = items.size
    }

    override fun onResume() {
        super.onResume()
        // 每次切换到这个Tab时，重新加载数据
        if (::etMaxDelay.isInitialized) {
            val thresholdText = etMaxDelay.text.toString()
            if (thresholdText.isNotEmpty()) {
                maxDelayThreshold = thresholdText.toIntOrNull() ?: 200
                loadResultsFromFile()
            }
        }
    }
}