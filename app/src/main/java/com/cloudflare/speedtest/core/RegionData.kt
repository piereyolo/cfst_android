package com.cloudflare.speedtest.core

import android.content.Context
import android.util.Log
import com.cloudflare.speedtest.ui.R
import org.json.JSONObject

/**
 * 地区码（3 位机场码）→ 中文地区名映射。
 *
 * 数据源是 [com.cloudflare.speedtest.R.raw.regions]（res/raw/regions.json，353 条），
 * 改表只需要改 JSON，不再往业务代码里堆字面量。
 *
 * 在 [MainActivity.onCreate] 里调用一次 [init] 即可，幂等；
 * 若尚未初始化，[nameOf] 退化为「原样返回 code」，不影响主流程。
 */
object RegionData {

    const val CODE_UNKNOWN = "UNKNOWN"
    const val CODE_LOCAL = "LOCAL"

    const val LABEL_UNKNOWN = "未知地区"
    const val LABEL_LOCAL = "本地网络"

    private val lock = Any()
    private var names: Map<String, String> = emptyMap()
    private var reverse: Map<String, List<String>> = emptyMap()
    private var loaded = false

    /** 幂等：首次调用读取 res/raw/regions.json。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        try {
            val json = context.resources.openRawResource(R.raw.regions)
                .bufferedReader().use { it.readText() }
            parse(JSONObject(json))
            loaded = true
            Log.i("RegionData", "已加载 ${names.size} 条地区映射")
        } catch (e: Exception) {
            Log.e("RegionData", "加载 regions.json 失败", e)
        }
    }

    /** 机场码 → 中文地区名。未知时返回 [LABEL_UNKNOWN]，`LOCAL` 返回 [LABEL_LOCAL]。 */
    fun nameOf(code: String?): String {
        if (code.isNullOrEmpty()) return LABEL_UNKNOWN
        val c = code.trim().uppercase()
        if (c == CODE_LOCAL) return LABEL_LOCAL
        ensureLoaded()
        return names[c] ?: if (c == CODE_UNKNOWN) LABEL_UNKNOWN else code.trim()
    }

    /** 中文地区名 → 该名称下的所有区域码。 */
    fun codesOf(name: String?): List<String> {
        if (name.isNullOrEmpty()) return emptyList()
        ensureLoaded()
        return reverse[name.trim()] ?: emptyList()
    }

    /** 与 [nameOf] 相同，但查不到时返回 [LABEL_UNKNOWN]（保持旧版 `未知地区` 的展示口径）。 */
    fun labelOf(code: String?): String {
        if (code.isNullOrEmpty() || code.trim().equals(CODE_UNKNOWN, true)) return LABEL_UNKNOWN
        ensureLoaded()
        return names[code.trim().uppercase()] ?: LABEL_UNKNOWN
    }

    /**
     * 从 Cloudflare CF-RAY 响应头里取区域码。
     * 形如 `7f3a2b1c4d5e6f78-LAX` → `LAX`；取最后一个 `-` 之后的部分。
     */
    fun regionCodeFromRay(ray: String?): String {
        if (ray.isNullOrBlank()) return CODE_UNKNOWN
        val trimmed = ray.trim()
        val idx = trimmed.lastIndexOf('-')
        if (idx >= 0 && idx + 1 < trimmed.length) {
            val tail = trimmed.substring(idx + 1)
            if (tail.isNotEmpty()) return tail.uppercase()
        }
        // 没有 `-`：仅当本身像一个短区域码时接受，否则视为未知
        return if (trimmed.length in 2..4 && trimmed.all { it.isLetterOrDigit() }) trimmed.uppercase() else CODE_UNKNOWN
    }

    private fun ensureLoaded() {
        if (!loaded) {
            Log.w("RegionData", "RegionData 尚未 init()，nameOf() 退化为原样返回")
        }
    }

    private fun parse(json: JSONObject) {
        val m = LinkedHashMap<String, String>()
        val rev = LinkedHashMap<String, ArrayList<String>>()
        // 注意：Android 的 JSONObject.names() 返回的是 JSONArray，不是 Iterator
        val keys = json.names() ?: return
        for (i in 0 until keys.length()) {
            val key = keys.getString(i)
            val code = key.uppercase()
            val name = json.getString(key).trim()
            if (code.isEmpty() || name.isEmpty() || code == CODE_UNKNOWN) continue
            m[code] = name
            rev.getOrPut(name) { ArrayList() }.add(code)
        }
        names = m
        reverse = rev
    }
}
