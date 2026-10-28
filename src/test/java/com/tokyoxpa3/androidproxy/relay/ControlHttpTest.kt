package com.tokyoxpa3.androidproxy.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlHttpTest {
    private fun req(vararg lines: String) = ControlHttp.parseHead(lines.toList())

    @Test
    fun parsesRequestLineAndHeaders() {
        val r = req("post /rotate?x=1 HTTP/1.1", "Host: a", "User-Agent:  curl/8 ")!!
        assertEquals("POST", r.method)
        assertEquals("/rotate", r.path)
        assertEquals("curl/8", r.headers["user-agent"])
    }

    @Test
    fun rejectsMalformedRequestLine() {
        assertNull(req())
        assertNull(req("GET /status"))
        assertNull(req("GET /status FTP/1.0"))
    }

    @Test
    fun parsesQueryForTokenAuth() {
        // 控制 API 認證可以走 ?token=，所以 query 必須被解出來
        val r = req("GET /status?token=abc123&x=1 HTTP/1.1")!!
        assertEquals("/status", r.path)
        assertEquals("abc123", r.query["token"])
        assertEquals("1", r.query["x"])
        // 重複參數取第一個，後面的不能覆蓋掉已驗證的值
        assertEquals("a", req("GET /x?token=a&token=b HTTP/1.1")!!.query["token"])
        assertEquals(emptyMap<String, String>(), req("GET /status HTTP/1.1")!!.query)
    }

    @Test
    fun queryDecodingNeverThrows() {
        // 壞掉的 %-序列不能讓整條請求變成 400，否則會蓋掉 401
        val r = req("GET /status?token=%zz HTTP/1.1")!!
        assertEquals("%zz", r.query["token"])
    }

    @Test
    fun parsesIpBody() {
        assertEquals("175.223.14.234", ControlHttp.parseIpBody("175.223.14.234\n"))
        assertEquals("2001:db8::1", ControlHttp.parseIpBody("2001:db8::1"))
        assertNull(ControlHttp.parseIpBody("<html>captive</html>"))
        assertNull(ControlHttp.parseIpBody(""))
    }
}
