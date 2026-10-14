package com.cloudflare.speedtest.core

/**
 * ProxyParser.kt - 完美方案
 * 支持: vmess:// vless:// trojan:// ss:// (SIP002 + legacy)
 * 兼容: v2rayNG / Shadowrocket / Clash / sing-box 导出的分享链接
 * 功能: 解析 + 修改 address + 重建链接 + 批量订阅解析
 * 依赖: 零依赖 (仅 JDK) ，Android 直接可用
 * Kotlin 1.6+ / JVM 8+
 */

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64 as JBase64
import org.json.JSONObject

object B64 {
    private val decoder = JBase64.getDecoder()
    private val urlDecoder = JBase64.getUrlDecoder()
    private val encoder = JBase64.getEncoder().withoutPadding()
    private val urlEncoder = JBase64.getUrlEncoder().withoutPadding()

    fun decode(input: String): ByteArray {
        var s = input.trim()
        // 兼容 url-safe 和标准 base64
        s = s.replace('-', '+').replace('_', '/')
        // 补齐 =
        val mod = s.length % 4
        if (mod != 0) s += "=".repeat(4 - mod)
        return try {
            decoder.decode(s)
        } catch (e: Exception) {
            urlDecoder.decode(s)
        }
    }

    fun decodeToString(input: String): String = String(decode(input), StandardCharsets.UTF_8)

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun encodeStr(str: String): String = encode(str.toByteArray(StandardCharsets.UTF_8))
    fun encodeUrlSafe(str: String): String = urlEncoder.encodeToString(str.toByteArray(StandardCharsets.UTF_8))
}

sealed class ProxyNode {
    abstract var address: String
    abstract var port: Int
    abstract var remark: String
    abstract fun toLink(): String
    abstract fun withNewAddress(newAddr: String): ProxyNode

    data class Vmess(
        override var address: String,
        override var port: Int,
        var id: String,
        var aid: String = "0",
        var net: String = "tcp",
        var type: String = "none",
        var host: String = "",
        var path: String = "",
        var tls: String = "",
        var sni: String = "",
        override var remark: String = "",
        var rawJson: JSONObject = JSONObject()
    ) : ProxyNode() {
        override fun toLink(): String {
            rawJson.put("add", address)
            rawJson.put("port", port.toString())
            rawJson.put("id", id)
            rawJson.put("aid", aid)
            rawJson.put("net", net)
            rawJson.put("type", type)
            rawJson.put("host", host)
            rawJson.put("path", path)
            rawJson.put("tls", tls)
            rawJson.put("ps", remark)
            if (sni.isNotEmpty()) rawJson.put("sni", sni)
            val jsonStr = rawJson.toString()
            return "vmess://${B64.encodeStr(jsonStr)}"
        }

        override fun withNewAddress(newAddr: String): ProxyNode {
            return copy(address = newAddr, rawJson = JSONObject(rawJson.toString()).apply { put("add", newAddr) })
        }
    }

    data class Vless(
        var uuid: String,
        override var address: String,
        override var port: Int,
        var params: MutableMap<String, String> = mutableMapOf(),
        override var remark: String = "",
        var originalQuery: String = ""
    ) : ProxyNode() {
        override fun toLink(): String {
            val hostFormatted = formatHost(address)
            val query = if (originalQuery.isNotEmpty()) "?$originalQuery" else if (params.isNotEmpty()) "?${params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }}" else ""
            val frag = if (remark.isNotEmpty()) "#${URLEncoder.encode(remark, "UTF-8")}" else ""
            return "vless://$uuid@$hostFormatted:$port$query$frag"
        }

        override fun withNewAddress(newAddr: String): ProxyNode = copy(address = newAddr)
    }

    data class Trojan(
        var password: String,
        override var address: String,
        override var port: Int,
        var params: MutableMap<String, String> = mutableMapOf(),
        override var remark: String = "",
        var originalQuery: String = ""
    ) : ProxyNode() {
        override fun toLink(): String {
            val hostFormatted = formatHost(address)
            val query = if (originalQuery.isNotEmpty()) "?$originalQuery" else if (params.isNotEmpty()) "?${params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }}" else ""
            val frag = if (remark.isNotEmpty()) "#${URLEncoder.encode(remark, "UTF-8")}" else ""
            return "trojan://$password@$hostFormatted:$port$query$frag"
        }

        override fun withNewAddress(newAddr: String): ProxyNode = copy(address = newAddr)
    }

    data class SS(
        var method: String,
        var password: String,
        override var address: String,
        override var port: Int,
        override var remark: String = "",
        var plugin: String = "",
        var isSip002: Boolean = true
    ) : ProxyNode() {
        override fun toLink(): String {
            val hostFormatted = formatHost(address)
            val frag = if (remark.isNotEmpty()) "#${URLEncoder.encode(remark, "UTF-8")}" else ""
            return if (isSip002) {
                val userInfo = B64.encodeStr("$method:$password")
                val pluginStr = if (plugin.isNotEmpty()) "/?plugin=${URLEncoder.encode(plugin, "UTF-8")}" else ""
                "ss://$userInfo@$hostFormatted:$port$pluginStr$frag"
            } else {
                val full = "$method:$password@$hostFormatted:$port"
                "ss://${B64.encodeStr(full)}$frag"
            }
        }

        override fun withNewAddress(newAddr: String): ProxyNode = copy(address = newAddr)
    }

    companion object {
        fun formatHost(host: String): String {
            // IPv6 需要 []
            return if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) "[$host]" else host
        }
    }
}

object ProxyParser {

    fun parse(link: String): ProxyNode {
        val l = link.trim()
        return when {
            l.startsWith("vmess://", true) -> parseVmess(l)
            l.startsWith("vless://", true) -> parseVless(l)
            l.startsWith("trojan://", true) -> parseTrojan(l)
            l.startsWith("ss://", true) -> parseSS(l)
            else -> error("不支持的协议: $l")
        }
    }

    /** 完美的改 address 方法 - 保留所有参数 */
    fun replaceAddress(link: String, newAddress: String): String {
        return try {
            val node = parse(link)
            node.address = newAddress
            node.remark +=newAddress
            if (node is ProxyNode.Vmess) {
                node.rawJson.put("add", newAddress)
            }
            node.toLink()
        } catch (e: Exception) {
            // 容错降级: 字符串替换 (针对 vless/trojan/ss)
            fastReplace(link, newAddress)
        }
    }

    /** 不依赖解析的极速替换，用于 vless/trojan/ss SIP002 */
    private fun fastReplace(link: String, newAddress: String): String {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd == -1) return link
        val afterScheme = link.substring(schemeEnd + 3)
        val hashIdx = afterScheme.indexOf('#')
        val withoutFrag = if (hashIdx >= 0) afterScheme.substring(0, hashIdx) else afterScheme
        val frag = if (hashIdx >= 0) afterScheme.substring(hashIdx) else ""
        val qIdx = withoutFrag.indexOf('?')
        val withoutQuery = if (qIdx >= 0) withoutFrag.substring(0, qIdx) else withoutFrag
        val query = if (qIdx >= 0) withoutFrag.substring(qIdx) else ""
        val atIdx = withoutQuery.lastIndexOf('@')
        if (atIdx == -1) return link
        val userInfo = withoutQuery.substring(0, atIdx)
        val hostPort = withoutQuery.substring(atIdx + 1)
        val port = hostPort.substringAfterLast(":")
        val newHost = ProxyNode.formatHost(newAddress)
        return "${link.substring(0, schemeEnd + 3)}$userInfo@$newHost:$port$query$frag"
    }

    private fun parseVmess(link: String): ProxyNode.Vmess {
        val b64 = link.removePrefix("vmess://").substringBefore("#").trim()
        val jsonStr = B64.decodeToString(b64)
        val obj = JSONObject(jsonStr)
        return ProxyNode.Vmess(
            address = obj.optString("add", ""),
            port = obj.optString("port", "443").toIntOrNull() ?: 443,
            id = obj.optString("id", ""),
            aid = obj.optString("aid", "0"),
            net = obj.optString("net", "tcp"),
            type = obj.optString("type", "none"),
            host = obj.optString("host", ""),
            path = obj.optString("path", ""),
            tls = obj.optString("tls", ""),
            sni = obj.optString("sni", obj.optString("host", "")),
            remark = obj.optString("ps", ""),
            rawJson = obj
        )
    }

    private fun parseVless(link: String): ProxyNode.Vless {
        // 手动解析防止 URI 编码问题
        var s = link.removePrefix("vless://")
        val remark = if (s.contains("#")) URLDecoder.decode(s.substringAfterLast("#"), "UTF-8") else ""
        s = s.substringBefore("#")
        val queryStr = if (s.contains("?")) s.substringAfter("?") else ""
        s = s.substringBefore("?")
        val atIdx = s.lastIndexOf('@')
        val uuid = s.substring(0, atIdx)
        val hostPort = s.substring(atIdx + 1)
        val (host, port) = splitHostPort(hostPort)
        val params = parseQuery(queryStr).toMutableMap()
        return ProxyNode.Vless(uuid, host, port, params, remark, queryStr)
    }

    private fun parseTrojan(link: String): ProxyNode.Trojan {
        var s = link.removePrefix("trojan://")
        val remark = if (s.contains("#")) URLDecoder.decode(s.substringAfterLast("#"), "UTF-8") else ""
        s = s.substringBefore("#")
        val queryStr = if (s.contains("?")) s.substringAfter("?") else ""
        s = s.substringBefore("?")
        val atIdx = s.lastIndexOf('@')
        val password = s.substring(0, atIdx)
        val hostPort = s.substring(atIdx + 1)
        val (host, port) = splitHostPort(hostPort)
        return ProxyNode.Trojan(password, host, port, parseQuery(queryStr).toMutableMap(), remark, queryStr)
    }

    private fun parseSS(link: String): ProxyNode.SS {
        var s = link.removePrefix("ss://")
        val remark = if (s.contains("#")) URLDecoder.decode(s.substringAfterLast("#"), "UTF-8") else ""
        s = s.substringBefore("#")
        // 判断是 SIP002 还是 legacy: 是否包含 @ 且 @ 之前是 base64
        return if (s.contains("@")) {
            // SIP002: ss://base64(method:pass)@host:port
            val atIdx = s.lastIndexOf('@')
            val b64Part = s.substring(0, atIdx)
            var hostPortQuery = s.substring(atIdx + 1)
            val plugin = if (hostPortQuery.contains("?")) {
                val q = hostPortQuery.substringAfter("?")
                hostPortQuery = hostPortQuery.substringBefore("?")
                URLDecoder.decode(q.substringAfter("plugin=", ""), "UTF-8")
            } else ""
            val (host, port) = splitHostPort(hostPortQuery)
            val decoded = B64.decodeToString(b64Part)
            val method = decoded.substringBefore(":")
            val password = decoded.substringAfter(":")
            ProxyNode.SS(method, password, host, port, remark, plugin, true)
        } else {
            // legacy: ss://base64(method:password@host:port)
            val decoded = B64.decodeToString(s)
            val atIdx = decoded.lastIndexOf('@')
            val methodPass = decoded.substring(0, atIdx)
            val hostPort = decoded.substring(atIdx + 1)
            val method = methodPass.substringBefore(":")
            val password = methodPass.substringAfter(":")
            val (host, port) = splitHostPort(hostPort)
            ProxyNode.SS(method, password, host, port, remark, "", false)
        }
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        var h = hostPort.trim()
        // 去掉 /?plugin 部分
        h = h.substringBefore("/").substringBefore("?")
        return if (h.startsWith("[")) {
            // [ipv6]:port
            val end = h.indexOf(']')
            val host = h.substring(1, end)
            val port = h.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
            host to port
        } else {
            val lastColon = h.lastIndexOf(':')
            if (lastColon == -1) h to 443
            else h.substring(0, lastColon) to (h.substring(lastColon + 1).toIntOrNull() ?: 443)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            if (it.isBlank()) return@mapNotNull null
            val kv = it.split("=", limit = 2)
            val k = URLDecoder.decode(kv[0], "UTF-8")
            val v = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
            k to v
        }.toMap()
    }

    /** 解析订阅: base64后的多行链接 */
    fun parseSubscription(subContent: String): List<ProxyNode> {
        val text = try {
            B64.decodeToString(subContent.trim())
        } catch (e: Exception) {
            subContent
        }
        return text.lines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty()) null else try { parse(t) } catch (e: Exception) { null }
        }
    }
}

// ============ 使用示例 ============
// fun main() {
//     val links = listOf(
//         "vless://b0dd8d2d-7a1a-4d2a-9f5c-1a2b3c4d5e6f@1.1.1.1:443?type=ws&security=tls&host=example.com#MyNode",
//         "vmess://eyJhZGQiOiIxLjEuMS4xIiwicG9ydCI6IjQ0MyIsImlkIjoiYiIsInBzIjoiVGVzdCJ9",
//         "trojan://password@1.1.1.1:443?sni=example.com#Trojan",
//         "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.1.1.1:8388#SS-Node"
//     )
//
//     links.forEach { old ->
//         val newLink = ProxyParser.replaceAddress(old, "2.2.2.2")
//         println("$old ->\n$newLink\n")
//     }
//
//     // 批量替换订阅
//     val sub = "dnBess://... (base64后的订阅内容)"
//     val nodes = ProxyParser.parseSubscription(sub)
//     val newSubLinks = nodes.map { it.withNewAddress("8.8.8.8").toLink() }
// }