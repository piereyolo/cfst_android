package com.cloudflare.speedtest.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File


class IPTestFragment : Fragment() {

    private lateinit var sharedViewModel: SharedViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ABAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var etThreadCount: EditText
    private lateinit var etDStart: EditText
    private lateinit var etDInterval: EditText
    private lateinit var tvSelectedCount: TextView
    private lateinit var tvGeneratedIPs: TextView

    private val sTestConfigFile="testconfig.json"

    // 权限请求码

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_ip_test, container, false)

        // 初始化视图
        initViews(view)

        return view
    }

    private fun initViews(view: View) {
        recyclerView = view.findViewById(R.id.recyclerView)
        progressBar = view.findViewById(R.id.progressBar)
        statusText = view.findViewById(R.id.statusText)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        etThreadCount = view.findViewById(R.id.etThreadCount)
        etDStart = view.findViewById(R.id.etDStart)
        etDInterval = view.findViewById(R.id.etDInterval)
        tvSelectedCount = view.findViewById(R.id.tvSelectedCount)
        tvGeneratedIPs = view.findViewById(R.id.tvGeneratedIPs)

        // 设置RecyclerView
        adapter = ABAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // 设置按钮点击事件
        startButton.setOnClickListener { startTest() }
        stopButton.setOnClickListener { stopTest() }

        // 设置参数输入监听
        setupParameterInputs()
    }

    private fun setupParameterInputs() {
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateTestParams()
            }
        }

        etThreadCount.addTextChangedListener(textWatcher)
        etDStart.addTextChangedListener(textWatcher)
        etDInterval.addTextChangedListener(textWatcher)
    }

    private fun updateTestParams() {
        val threads = etThreadCount.text.toString().toIntOrNull() ?: 0
        val dStart = etDStart.text.toString().toIntOrNull() ?: 1
        val dInterval = etDInterval.text.toString().toIntOrNull() ?: 8

        sharedViewModel.updateTestParams(threads, dStart, dInterval)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化ViewModel
        sharedViewModel = ViewModelProvider(requireActivity()).get(SharedViewModel::class.java)

        // 观察选中CIDR列表，展开为A.B列表
        sharedViewModel.selectedCIDRs.observe(viewLifecycleOwner) { selectedCIDRs ->
            if (selectedCIDRs.isNotEmpty()) {
                val abList = sharedViewModel.expandCIDRs()
                adapter.setData(abList)
                updateSelectedCount(adapter.getSelectedCount())
            } else {
                adapter.setData(emptyList())
                updateSelectedCount(0)
                tvGeneratedIPs.text = "预计生成IP: 0个"
            }
        }

        // 观察测试状态
        sharedViewModel.testStatus.observe(viewLifecycleOwner) { status ->
            updateTestStatus(status)
        }

        // 观察测试参数
        sharedViewModel.testThreads.observe(viewLifecycleOwner) { threads ->
            if (etThreadCount.text.toString() != threads.toString()) {
                etThreadCount.setText(threads.toString())
            }
        }

        // 初始化参数
        etThreadCount.setText("20")
        etDStart.setText("1")
        etDInterval.setText("8")
        updateTestParams()
    }

    private fun startTest() {
        val selectedABs = adapter.getSelectedABs()
        if (selectedABs.isEmpty()) {
            Toast.makeText(requireContext(), "请至少选择一个A.B前缀", Toast.LENGTH_SHORT).show()
            return
        }

        val threads = etThreadCount.text.toString().toIntOrNull() ?: 10
        val dStart = etDStart.text.toString().toIntOrNull() ?: 1
        val dInterval = etDInterval.text.toString().toIntOrNull() ?: 8

        // 计算预计生成的IP数量
        val estimatedIPs = selectedABs.size * 256 * ((255 - dStart) / dInterval + 1)
        tvGeneratedIPs.text = "预计生成IP: ${estimatedIPs}个"

        SaveConfig()

        sharedViewModel.startTest(selectedABs)
    }

    private fun stopTest() {
        sharedViewModel.stopTest()
    }



    private fun updateTestStatus(status: SharedViewModel.TestStatus) {
        startButton.isEnabled = !status.isRunning
        stopButton.isVisible = status.isRunning
        progressBar.isVisible = status.isRunning

        if (status.isRunning) {
            progressBar.progress = (status.progress * 100).toInt()
            statusText.text = status.message
        } else {
            statusText.text = status.message
        }
    }

    private fun updateSelectedCount(count: Int) {
        tvSelectedCount.text = "已选中: $count 个A.B前缀"
    }

    // RecyclerView适配器
    inner class ABAdapter : RecyclerView.Adapter<ABAdapter.ViewHolder>() {

        private val items = mutableListOf<SharedViewModel.ABItem>()

        fun setData(newItems: List<SharedViewModel.ABItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            updateSelectedCount(getSelectedCount())
        }

        fun getSelectedABs(): List<String> = items.filter { it.isSelected }.map { it.ab }

        fun getSelectedCount(): Int = items.count { it.isSelected }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val checkBox: android.widget.CheckBox = itemView.findViewById(R.id.checkBox)
            val abTextView: TextView = itemView.findViewById(R.id.abTextView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ab, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.abTextView.text = item.ab

            // 先移除监听器
            holder.checkBox.setOnCheckedChangeListener(null)

            // 设置选中状态
            holder.checkBox.isChecked = item.isSelected

            // 设置监听器
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                item.isSelected = isChecked
                updateSelectedCount(getSelectedCount())
            }

            holder.itemView.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }

        override fun getItemCount(): Int = items.size
    }

    fun LoadConfig(){
        val context = requireContext()
        val j_text = File(context.filesDir,sTestConfigFile).let { file -> if (file.isFile) file.readText().let { if (it == "") "{}" else it } else "{}" }
        val j_json = JSONObject(j_text)

        try {
            if (j_json.has("测试线程数")){
                val nThreadCount = j_json.getInt("测试线程数")
                view?.findViewById<EditText>(R.id.etThreadCount)?.setText(nThreadCount.toString())
            }
        } catch (_: Exception) {
        }

        try {
            if (j_json.has("D段起始值")){
                val nDValueBegin = j_json.getInt("D段起始值")
                view?.findViewById<EditText>(R.id.etDStart)?.setText(nDValueBegin.toString())
            }
        } catch (_: Exception) {
        }

        try {
            if (j_json.has("间隔数")){
                val nDIntervalValue = j_json.getInt("间隔数")
                view?.findViewById<EditText>(R.id.etDInterval)?.setText(nDIntervalValue.toString())
            }
        } catch (_: Exception) {
        }

        updateTestParams()
    }

    fun SaveConfig(){

        val j_json = JSONObject()

        j_json.put("测试线程数", view?.findViewById<EditText>(R.id.etThreadCount)?.text.toString())
        j_json.put("D段起始值", view?.findViewById<EditText>(R.id.etDStart)?.text.toString())
        j_json.put("间隔数", view?.findViewById<EditText>(R.id.etDInterval)?.text.toString())

        val context = requireContext()
       File(context.filesDir, sTestConfigFile)?.writeText(j_json.toString())

    }

    override fun onResume() {
        super.onResume()
        LoadConfig()
    }
}