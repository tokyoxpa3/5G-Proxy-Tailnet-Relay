package com.tokyoxpa3.androidproxy.relay

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 控制 API 的共享權杖。
 *
 * 為什麼需要：控制 API 綁在 tailnet 位址上，而 tailnet 內任何裝置都能連進來。
 * 其中 `POST /rotate` 會切飛航模式 —— 一個未認證的請求就能讓這支手機的
 * Wi-Fi / Tailscale / 所有代理連線全部斷掉（而且 Tailscale ACL 只能限制「來源」，
 * 不能限制「路徑」，擋不住 tailnet 內的其他裝置）。
 *
 * 權杖在首次使用時自動產生並存進 `relay` 偏好設定，顯示在 App 介面上供複製。
 * 比對用 [MessageDigest.isEqual]（常數時間），避免以回應時間逐位元組猜測。
 */
object ControlAuth {

    private const val PREF_TOKEN = "control_token"
    private const val TOKEN_BYTES = 16

    /** 取得（必要時產生）權杖。 */
    fun tokenFor(context: Context): String {
        val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_TOKEN, "").orEmpty()
        if (existing.length >= TOKEN_BYTES * 2) return existing
        val raw = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(raw)
        val token = raw.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_TOKEN, token).apply()
        return token
    }

    /** 重新產生權杖（讓舊的呼叫端立刻失效）。 */
    fun regenerate(context: Context): String {
        context.getSharedPreferences("relay", Context.MODE_PRIVATE)
            .edit().remove(PREF_TOKEN).apply()
        return tokenFor(context)
    }

    /**
     * 請求是否帶了正確權杖。接受兩種形式：
     *  - `Authorization: Bearer <token>`（或直接 `Authorization: <token>`）
     *  - `?token=<token>`（給不想處理 header 的簡單客戶端）
     */
    fun isAuthorized(context: Context, req: ControlHttp.Request): Boolean {
        val provided = req.headers["authorization"]
            ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7) else it }
            ?.trim()
            ?: req.query["token"]
            ?: return false
        val expected = tokenFor(context)
        return provided.isNotEmpty() &&
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
    }
}
