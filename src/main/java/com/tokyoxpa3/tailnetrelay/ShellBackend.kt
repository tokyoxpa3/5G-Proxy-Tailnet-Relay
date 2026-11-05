package com.tokyoxpa3.tailnetrelay

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * [IP 輪替] 選配的 shell 後端：透過 OmniADB-Host 的窄介面切飛航模式。
 *
 * ## 為什麼放在降級鏈最前面
 * `cmd connectivity airplane-mode` 是實測唯一「不需助理、不需 root、不需 Shizuku」
 * 且真的會動射頻的路徑。走 loopback 127.0.0.1，飛航期間連線也不會斷，
 * 所以遠端在換 IP 的幾秒內仍看得到 /status。
 *
 * ## 邊界
 * - **不持有 ADB 私鑰、不自行配對、不下任意指令**。這裡只能送四個固定 method，
 *   真正的 shell 指令寫死在 Host 的 `RelayBridgeProvider`。
 * - **選配**：沒裝 OmniADB-Host（或版本太舊、adbd 沒活著）時 [isAvailable] 回 false，
 *   `AirplaneToggle` 自動退回原本的「寫設定 → 助理」兩段，F-Droid 上架不受影響。
 * - **不信任 Host 的回覆當成「射頻已切」**。這裡只回「指令有沒有跑完」，
 *   射頻狀態一律由 `AirplaneToggle.awaitRadio()` 用蜂巢介面驗證。
 */
object ShellBackend {
    private const val TAG = "ShellBackend"
    private const val AUTHORITY = "com.omniadb.host.relay"
    private val URI: Uri = Uri.parse("content://$AUTHORITY")

    /** 探測：Host 在不在、版本夠不夠新、呼叫端身分過不過。不觸發 ADB 連線。 */
    fun isAvailable(context: Context): Boolean =
        call(context, "ping")?.getBoolean("ok", false) == true

    fun setAirplane(context: Context, enabled: Boolean): Boolean {
        val method = if (enabled) "airplane_on" else "airplane_off"
        val reply = call(context, method)
        if (reply?.getBoolean("ok", false) != true) {
            Log.w(TAG, "$method failed: ${reply?.getString("error") ?: "no reply"}")
            return false
        }
        return true
    }

    /**
     * 請 Host 幫本 App 取得 WRITE_SECURE_SETTINGS，供 `keepWifiAlive()` 使用
     * （把 wifi 移出飛航清單，換 IP 時遠端不失聯）。冪等，失敗不影響主流程。
     */
    fun grantSecureSettings(context: Context): Boolean =
        call(context, "grant_secure")?.getBoolean("ok", false) == true

    private fun call(context: Context, method: String): Bundle? = try {
        // arg / extras 不帶任何使用者輸入：Host 端也不會拿它們組指令。
        context.contentResolver.call(URI, method, null, Bundle())
    } catch (e: Exception) {
        Log.w(TAG, "Host bridge unavailable ($method): ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
