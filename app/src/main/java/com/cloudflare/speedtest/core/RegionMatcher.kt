package com.cloudflare.speedtest.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 「过滤区域」输入框的模式匹配器。
 *
 * 支持：
 * - 多个模式用 `,` `;` `|` 或空白分隔，命中任意一个即算命中（如 `LAX,HKG`）
 * - `*` 匹配任意多个字符，`?` 匹配任意单个字符
 * - 大小写不敏感
 * - 空输入 = 不过滤（全部通过），清空输入框即「看所有区域」
 *
 * 同时匹配「3 位区域码」和「中文地区名」，所以 `中国*`、`美国*` 这类写法才有效。
 *
 * 例：`LAX,HKG` 命中 LAX / HKG；`中国*` 命中 SHA / SZX / HKG / KHH / TPE …；
 *     `US*` 这类不写前缀也能用，因为区域码本身就是全码。
 */
object RegionMatcher {

    /** 编译后的模式集合。[isEmpty] 为 true 表示「不过滤」。 */
    data class Compiled(val rawPatterns: List<String>, val regexes: List<Regex>) {
        fun isEmpty(): Boolean = rawPatterns.isEmpty()
    }

    private val cache = ConcurrentHashMap<String, Compiled>()

    /** 把用户输入编译成模式集合，带缓存（同一输入不会重复编译正则）。 */
    fun compile(filter: String?): Compiled {
        val key = (filter ?: "").trim()
        if (key.isEmpty()) return Compiled(emptyList(), emptyList())
        return cache.getOrPut(key) {
            val tokens = key.split(Regex("[,;|\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            Compiled(tokens, tokens.map { toRegex(it) })
        }
    }

    /**
     * 判断某区域是否通过过滤。
     *
     * [regionCode] 既可以是纯区域码（`LAX`），也可以是
     * `code|中文名`（`LAX|美国洛杉矶`）或 `code:中文名` 这类带标签的写法。
     * 空模式（[compile] 返回空）表示不过滤，返回 true。
     */
    fun matches(compiled: Compiled, regionCode: String?): Boolean {
        if (compiled.isEmpty()) return true
        if (regionCode.isNullOrBlank()) return false

        val parts = splitTag(regionCode)
        for (i in compiled.regexes.indices) {
            val re = compiled.regexes[i]
            for (p in parts) if (re.containsMatchIn(p)) return true
        }
        return false
    }

    /**
     * 把一个标签字符串拆成「码 + 中文名」两个候选串。
     * 例如 `LAX|美国洛杉矶` → ["LAX", "美国洛杉矶"]；`LAX` → ["LAX", "美国洛杉矶"]。
     */
    private fun splitTag(raw: String): List<String> {
        val trimmed = raw.trim()
        val sepIdx = trimmed.indexOfFirst { c -> c == '|' || c == ':' || c == '-' }
        if (sepIdx > 0 && sepIdx < trimmed.length - 1) {
            val left = trimmed.substring(0, sepIdx).trim()
            val right = trimmed.substring(sepIdx + 1).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                // 左侧像区域码时用它，否则两侧都当候选
                val code = if (isLikelyCode(left)) left.uppercase() else left
                return mutableListOf(code, right, RegionData.nameOf(code))
            }
        }
        val code = trimmed.uppercase()
        return listOf(code, RegionData.nameOf(code))
    }

    private fun isLikelyCode(s: String): Boolean = s.length in 1..6 && s.all { it.isLetterOrDigit() }

    private fun toRegex(token: String): Regex {
        val sb = StringBuilder(token.length + 2)
        sb.append("^")
        for (ch in token.uppercase()) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
