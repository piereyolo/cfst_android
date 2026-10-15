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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudflare.speedtest.core.XrayVerifier
import com.cloudflare.speedtest.core.VerifyResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 真连接验证独立 tab。
 *
 * 用 xray 走真实代理会话测出链接是不是真的能通，把通过验证的
 * 服务器地址列到列表里，点击单项复制地址、点全部按钮一次复制所有。
 *
 * 之所以独立成 tab，是避免跟「生成链接 / 本地下载测速」混在一起——
 * 那边是把 IP 塞进模板做生成，这边是拿链接去验证，两件事语义完全不同。
 */
class VerifyNodeFragment : Fragment() {

    companion object {
        private const val TAG = "VerifyNode"

        /**
         * 全局验证作用域。故意放在 companion object，不受单个 Fragment
         * 实例的生命周期影响。
         *
         * 之前用实例级 ioScope，ViewPager2 提前调 onDestroy 时 ioScope.cancel()
         * 会连带干掉还没启动的 coroutine——用户点了按钮看到"开始测 N 条链接"
         * 但 launch 的 block 根本不执行（因为 scope 已 cancel）。
         * 改成全局 scope + currentJob 追踪，onDestroy 只 cancel 任务不 cancel scope。
         */
        private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 当前正在跑的验证任务。同一时间只允许一个批次。 */
        private val currentJob = AtomicReference<Job?>()
    }

    private lateinit var etVerifyAttempts: EditText
    private lateinit var etVerifyInterval: EditText
    private lateinit var etVerifyFailThreshold: EditText
    private lateinit var btnStartVerify: Button
    private lateinit var btnStopVerify: Button
    private lateinit var btnCopyAll: Button
    private var tvStatus: TextView? = null
    private var recyclerView: RecyclerView? = null

    private var stopRequested = false

    private val adapter = VerifiedAdapter()

    /** 本轮通过的验证结果，按 RTT 升序 */
    private val results = mutableListOf<VerifyResult>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_verify_node, container, false)
        initViews(view)
        return view
    }

    private fun initViews(view: View) {
        etVerifyAttempts = view.findViewById(R.id.etVerifyAttempts)
        etVerifyInterval = view.findViewById(R.id.etVerifyInterval)
        etVerifyFailThreshold = view.findViewById(R.id.etVerifyFailThreshold)
        btnStartVerify = view.findViewById(R.id.btnVlessVerify)
        btnStopVerify = view.findViewById(R.id.btnStopVlessVerify)
        btnCopyAll = view.findViewById(R.id.btnCopyAllVerified)
        tvStatus = view.findViewById(R.id.tvVerifyStatus)
        val rv = view.findViewById<RecyclerView>(R.id.rvVerified)
        recyclerView = rv

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        adapter.setOnItemClickListener { address ->
            copyToClipboard(address, "节点地址")
        }

        btnStartVerify.setOnClickListener {
            Log.i(TAG, "btnStart clicked, isAdded=$isAdded")
            if (!isAdded) {
                Log.w(TAG, "isAdded=false, ignore click")
                return@setOnClickListener
            }
            stopRequested = false
            btnStartVerify.isEnabled = false
            btnStartVerify.isVisible = false
            btnStopVerify.isVisible = true
            btnStopVerify.isEnabled = true
            results.clear()
            adapter.notifyDataSetChanged()
            runVerify()
        }

        btnStopVerify.setOnClickListener {
            stopRequested = true
            updateStatus("正在停止…")
        }

        btnCopyAll.setOnClickListener { copyAllVerified() }
    }

    /**
     * 读取剪切板，解析出 vless/vmess 链接，跑批量验证。
     * 通过的地址按 RTT 升序填进列表，让用户看到最快的是谁。
     */
    private fun runVerify() {
        val attempts = etVerifyAttempts.text.toString().trim().toIntOrNull() ?: 3
        val intervalSec = etVerifyInterval.text.toString().trim().toLongOrNull() ?: 2L
        val failThreshold = etVerifyFailThreshold.text.toString().trim().toIntOrNull() ?: 0

        if (!XrayVerifier.isArchSupported()) {
            Toast.makeText(
                requireContext(),
                "设备架构 ${XrayVerifier.archDescription()} 不支持，需要 arm64-v8a",
                Toast.LENGTH_LONG
            ).show()
            updateStatus("设备架构不支持：${XrayVerifier.archDescription()}")
            resetButtons()
            return
        }

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip
        if (clipData == null || clipData.itemCount == 0) {
            Toast.makeText(requireContext(), "剪切板为空，先粘贴 vless/vmess 链接", Toast.LENGTH_SHORT).show()
            resetButtons()
            return
        }

        val links = clipData.getItemAt(0).text.toString()
            .trim().split("\n").map { it.trim() }
            .filter { it.startsWith("vless://") || it.startsWith("vmess://") }

        if (links.isEmpty()) {
            Toast.makeText(requireContext(), "剪切板里没找到 vless/vmess 链接", Toast.LENGTH_SHORT).show()
            resetButtons()
            return
        }

        // 如果上一轮还没结束，先取消
        currentJob.get()?.cancel()

        updateStatus("开始测 ${links.size} 条链接…")
        Log.i(TAG, "launching verifyBatch")

        // 用全局 scope launch，不受 Fragment 生命周期 cancel 影响
        val job = verifyScope.launch {
            try {
                Log.i(TAG, "verifyBatch started")
                val verifier = XrayVerifier(requireContext())
                val testUrl = XrayVerifier.DEFAULT_TEST_URL

                val all = verifier.verifyBatch(
                    links = links,
                    attempts = attempts,
                    intervalSec = intervalSec,
                    failThreshold = failThreshold,
                    testUrl = testUrl,
                    shouldStop = { stopRequested },
                    onEvent = { line -> updateStatus(line) },
                    onResult = { r -> addResultLive(r) }
                )

                val goodCount = all.count { !it.dead }
                val dead = all.count { it.dead }

                val summary = StringBuilder()
                summary.append("完成：通过 $goodCount 条 / 死链 $dead 条")
                if (stopRequested) summary.append("（已手动停止）")
                if (goodCount > 0) {
                    summary.append("\n最快：")
                    summary.append(
                        all.filter { !it.dead }
                            .sortedBy { it.bestMs }
                            .take(3)
                            .joinToString("，") { "${it.address} ${it.bestMs}ms" }
                    )
                }

                withContext(Dispatchers.Main) {
                    updateStatus(summary.toString())
                    if (goodCount == 0) {
                        val err = verifier.lastError
                        val msg = if (err.isEmpty()) "没有一条通过验证"
                                else "没有一条通过验证\n诊断：$err"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        if (err.isNotEmpty()) updateStatus(msg)
                    }
                }

                saveResults(all)
            } catch (e: Exception) {
                Log.e(TAG, "verifyBatch error", e)
                withContext(Dispatchers.Main) {
                    val msg = "验证出错：${e.message}"
                    updateStatus(msg)
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            } finally {
                currentJob.set(null)
                withContext(Dispatchers.Main) { resetButtons() }
            }
        }
        currentJob.set(job)
    }

    /** 通过列表：一条复制 IP，全复制则换行分隔塞进剪切板 */
    private fun copyAllVerified() {
        if (results.isEmpty()) {
            Toast.makeText(requireContext(), "还没有通过验证的地址", Toast.LENGTH_SHORT).show()
            return
        }
        val text = results.map { it.address }.joinToString("\n")
        copyToClipboard(text, "节点地址（${results.size} 个）")
    }

    /**
     * 实时塞进列表。verifyBatch 每测完一条通过验证的地址就回调一次，
     * 我们直接切到主线程把新条目插到最前面。
     *
     * 同一个 address 可能因为测多条链接出现多次——不去重，让用户看到
     * 每个地址的最佳 RTT（后测的同地址 RTT 更小就覆盖，否则保留原条）。
     */
    private fun addResultLive(r: VerifyResult) {
        recyclerView?.post {
            val existingIdx = results.indexOfFirst { it.address == r.address }
            if (existingIdx >= 0) {
                if (r.bestMs < results[existingIdx].bestMs) {
                    results[existingIdx] = r
                    adapter.notifyItemChanged(existingIdx)
                }
            } else {
                results.add(r)
                adapter.notifyItemInserted(results.size - 1)
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        val preview = if (text.contains("\n")) "${results.size} 个地址" else text
        Toast.makeText(requireContext(), "已复制: $preview", Toast.LENGTH_SHORT).show()
    }

    private fun resetButtons() {
        if (!isAdded) return
        btnStartVerify.isEnabled = true
        btnStartVerify.isVisible = true
        btnStopVerify.isVisible = false
        btnStopVerify.isEnabled = false
    }

    /** 状态更新，允许从后台线程调用 */
    private fun updateStatus(message: String) {
        val tv = tvStatus ?: return
        val text = "状态: $message"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tv.text = text
        } else {
            tv.post { tv.text = text }
        }
    }

    /** 把本轮结果写文件，方便跟电脑上 tools/vless_check.py 的结果对比 */
    private fun saveResults(results: List<VerifyResult>) {
        verifyScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    val sb = StringBuilder()
                    sb.append("时间,$time\n")

                    val sorted = results.sortedWith(
                        compareBy<VerifyResult> { if (it.dead) 1 else 0 }.thenBy { it.bestMs }
                    )

                    for (r in sorted) {
                        val detail = r.detail.joinToString(" ") {
                            if (it.first >= 0) "OK${it.first}ms" else "-1"
                        }
                        val status = if (r.dead) "死链" else "通"
                        sb.append("${r.address},$status,")
                        sb.append("${r.fails}/${r.attempts},${r.bestMs},$detail\n")
                    }

                    File(requireContext().filesDir, "vlessverifyresult.csv").writeText(sb.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只 cancel 当前任务，不 cancel scope（scope 是全局的，cancel 了下次点按钮就不跑了）
        currentJob.get()?.cancel()
        currentJob.set(null)
        // 杀干净所有 xray 进程，避免残留占内存
        XrayVerifier.shutdownAll()
    }

    // ---------------------------------------------------------------- 结果适配器

    private inner class VerifiedAdapter : RecyclerView.Adapter<VerifiedAdapter.ViewHolder>() {

        private var onItemClickListener: ((String) -> Unit)? = null

        fun setOnItemClickListener(l: (String) -> Unit) { this.onItemClickListener = l }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvAddress: TextView = itemView.findViewById(R.id.tvAddress)
            val tvBestMs: TextView = itemView.findViewById(R.id.tvBestMs)
            val tvHint: TextView = itemView.findViewById(R.id.tvHint)

            init {
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < results.size) {
                        onItemClickListener?.invoke(results[pos].address)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_verified_node, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = results[position]
            holder.tvAddress.text = item.address
            holder.tvBestMs.text = if (item.bestMs >= 0) "${item.bestMs}ms" else ""
            holder.tvHint.isVisible = true
        }

        override fun getItemCount(): Int = results.size
    }

}
