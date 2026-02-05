package com.cloudflare.speedtest.ui

import android.os.AsyncTask
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class IPListFragment : Fragment() {

    private lateinit var urlEditText: EditText
    private lateinit var fetchButton: Button
    private lateinit var ipRecyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var ipAdapter: IPAdapter
    private lateinit var sharedViewModel: SharedViewModel

    private val ipList = mutableListOf<IPItem>()
    private val lastIPFile = "lastipv4.txt"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ip_list, container, false)

        // 初始化视图
        urlEditText = view.findViewById(R.id.urlEditText)
        fetchButton = view.findViewById(R.id.fetchButton)
        ipRecyclerView = view.findViewById(R.id.ipRecyclerView)
        progressBar = view.findViewById(R.id.progressBar)

        // 设置默认 URL
        urlEditText.setText("https://www.cloudflare.com/ips-v4")

        // 设置 RecyclerView
        ipAdapter = IPAdapter(ipList)
        ipRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        ipRecyclerView.adapter = ipAdapter

        // 设置按钮点击事件
        fetchButton.setOnClickListener {
            val url = urlEditText.text.toString().trim()
            if (url.isNotEmpty()) {
                fetchIPsFromUrl(url)
            } else {
                Toast.makeText(requireContext(), "请输入URL", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化ViewModel
        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        // 设置CheckBox监听器
        ipAdapter.setOnItemCheckedListener { position, isChecked ->
            val item = ipList[position]
            item.isChecked = isChecked

            // 更新ViewModel中的选中CIDR列表
            val selectedCIDRs = ipList.filter { it.isChecked }.map { it.ip }.toSet()
            sharedViewModel.updateSelectedCIDRs(selectedCIDRs)
        }

        // 启动时先尝试加载本地保存的IP列表
        loadLocalIPs()
    }

    // 加载本地保存的IP列表
    private fun loadLocalIPs() {
        Thread {
            try {
                // 获取应用私有文件目录
                val context = requireContext()
                val file = File(context.filesDir, lastIPFile)

                if (file.exists()) {
                    val lines = file.readLines()

                    requireActivity().runOnUiThread {
                        if (lines.isNotEmpty()) {
                            // 解析并显示本地保存的IP列表
                            parseAndDisplayIPs(lines.joinToString("\n"), isFromLocal = true)
                            Toast.makeText(context, "已加载本地保存的IP列表", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "本地IP列表为空，请从网络获取", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "未找到本地IP列表，请从网络获取", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "加载本地IP列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 保存IP列表到本地文件
    private fun saveIPsToLocal(ips: List<String>) {
        Thread {
            try {
                val context = requireContext()
                val file = File(context.filesDir, lastIPFile)

                // 写入IP列表，每行一个
                val content = ips.joinToString("\n")
                file.writeText(content)

                // 添加保存时间戳
                val timestampFile = File(context.filesDir, "lastipv4_timestamp.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                timestampFile.writeText("最后更新时间: $timestamp")

                println("IP列表已保存到: ${file.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
                println("保存IP列表失败: ${e.message}")
            }
        }.start()
    }

    private fun fetchIPsFromUrl(url: String) {
        progressBar.visibility = View.VISIBLE
        fetchButton.isEnabled = false

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()

                    requireActivity().runOnUiThread {
                        parseAndDisplayIPs(responseBody)
                        progressBar.visibility = View.GONE
                        fetchButton.isEnabled = true
                    }
                } else {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "请求失败: ${response.code}", Toast.LENGTH_SHORT).show()
                        progressBar.visibility = View.GONE
                        fetchButton.isEnabled = true
                    }
                }
            } catch (e: IOException) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    fetchButton.isEnabled = true
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread {
                    Toast.makeText(requireContext(), "错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    progressBar.visibility = View.GONE
                    fetchButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun parseAndDisplayIPs(text: String?, isFromLocal: Boolean = false) {
        ipList.clear()

        if (text.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "获取的数据为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 按行分割，过滤空行
        val lines = text.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "未找到IP地址", Toast.LENGTH_SHORT).show()
            return
        }

        lines.forEach { line ->
            ipList.add(IPItem(line, false))
        }

        ipAdapter.notifyDataSetChanged()

        val message = if (isFromLocal) {
            "从本地加载 ${lines.size} 个IP段"
        } else {
            "获取到 ${lines.size} 个IP段，已保存到本地"
            // 保存到本地文件
            saveIPsToLocal(lines)
        }

        //Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    // 获取选中的IP列表
    fun getSelectedIPs(): List<String> {
        return ipList.filter { it.isChecked }.map { it.ip }
    }

    // 数据类
    data class IPItem(val ip: String, var isChecked: Boolean)

    // RecyclerView 适配器
    inner class IPAdapter(private val items: List<IPItem>) :
        RecyclerView.Adapter<IPAdapter.ViewHolder>() {

        private var onItemCheckedListener: ((Int, Boolean) -> Unit)? = null

        fun setOnItemCheckedListener(listener: (Int, Boolean) -> Unit) {
            this.onItemCheckedListener = listener
        }
        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val checkBox: CheckBox = itemView.findViewById(R.id.checkBox)
            val ipTextView: android.widget.TextView = itemView.findViewById(R.id.ipTextView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ip, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.ipTextView.text = item.ip
            holder.checkBox.isChecked = item.isChecked

            // 移除之前的监听器，避免重复触发
            holder.checkBox.setOnCheckedChangeListener(null)

            // 设置新的监听器
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                item.isChecked = isChecked
                onItemCheckedListener?.invoke(position, isChecked)
            }

            // 点击整行也可以选中/取消选中
            holder.itemView.setOnClickListener {
                val newChecked = !holder.checkBox.isChecked
                holder.checkBox.isChecked = newChecked
            }
        }

        override fun getItemCount(): Int = items.size
    }
}