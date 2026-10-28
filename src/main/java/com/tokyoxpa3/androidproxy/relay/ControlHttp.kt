package com.tokyoxpa3.androidproxy.relay

import java.net.URLDecoder

/**
 * 控制 API 的極簡 HTTP/1.1 請求剖析。
 * 只看 request line 與 header（不讀 body），足夠 `curl -X POST .../rotate` 使用。
 *
 * 抽成純 JVM 物件（零 Android 依賴），供單元測試鎖住邊界行為。
 */
object ControlHttp {

    data class Request(
        val method: String,
        val path: String,
        /** query string 已解碼；同名參數取第一個。 */
        val query: Map<String, String>,
        val headers: Map<String, String>,
    )

    /** lines：request line 起、空行前的所有行（不含 CRLF）。格式錯誤回 null。 */
    fun parseHead(lines: List<String>): Request? {
        val first = lines.firstOrNull() ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        // query string 不參與路由，但要保留給認證用（?token=）
        val target = parts[1]
        val path = target.substringBefore('?')
        return Request(parts[0].uppercase(), path, parseQuery(target.substringAfter('?', "")), headers)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            if (key.isEmpty()) continue
            out.putIfAbsent(key, decode(pair.substringAfter('=', "")))
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            // 非法的 %-序列：原樣保留，讓認證比較自然失敗，而不是整條請求 400
            s
        }

    /** api.ipify.org 回應本文 → IP 字串；不像 IP 的內容（錯誤頁、captive portal）回 null。 */
    fun parseIpBody(body: String): String? {
        val ip = body.trim()
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        val v6 = Regex("""^[0-9a-fA-F:]+$""")
        return if (v4.matches(ip) || (ip.contains(':') && v6.matches(ip))) ip else null
    }
}
