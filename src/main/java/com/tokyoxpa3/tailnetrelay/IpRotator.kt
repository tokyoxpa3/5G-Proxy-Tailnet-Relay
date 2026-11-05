package com.tokyoxpa3.tailnetrelay

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [IP 輪替] 飛航模式 ON → 等待 → OFF → 等蜂巢式恢復 → 經主程式 SOCKS5 查公網 IP。
 *
 * 公網 IP 必須透過 127.0.0.1 的主程式代理查：本 App 在 Tailscale 內，自己對外會走
 * Wi-Fi（或被 Tailscale 擋下蜂巢式 bind），查到的不是 5G 出口。
 *
 * 飛航模式期間蜂巢一定會斷，所以整個流程在背景執行緒自行跑完，結果寫進
 * status 供查詢 —— 呼叫端不該同步阻塞等它。
 *
 * ★ 但 **Wi-Fi 不必跟著斷**：輪替前會把 `wifi` 從 `airplane_mode_radios`
 *   裡拿掉（見 [AirplaneToggle.keepWifiAlive]），飛航就只關蜂巢。實測
 *   （POCO rodin / Android 15）兩次連續輪替、PC 側每 0.4 秒查一次 `/status`，
 *   **零次中斷**，而且能在輪替過程中即時看到 `running → changed`。
 *   沒有 `WRITE_SECURE_SETTINGS` 時退回原本行為（Wi-Fi 會斷）。
 */
class IpRotator(private val context: Context, private val socksPort: () -> Int) {

    companion object {
        private const val TAG = "IpRotator"
        private const val MAX_ATTEMPTS = 3

        /**
         * 飛航模式維持時間（第一次嘗試）。
         *
         * 這段是「中斷時間」的主要成分：射頻關掉 → 等 → 打開 → 重新 attach。
         * 實測（POCO / Android 15）整個中斷約 = hold + 2.5 秒（重新 attach）。
         *
         * 原本固定 5 秒 → 中斷 8 秒。改成 2.5 秒起跳、失敗才遞增（見
         * [AIRPLANE_HOLD_STEP_MS]）：短 hold 只要能讓電信商釋放原本的 PDP
         * context 就夠，重新取得 IP 是關閉之後才發生的事，不必在這裡等。
         */
        private const val AIRPLANE_HOLD_MS = 2_500L

        /**
         * 每次重試把 hold 拉長多少。
         *
         * 如果 2.5 秒不夠讓電信商換 IP（實測會有這種情況），重試時拉長到
         * 4.5 / 6.5 秒，而不是從頭就用最長的等待。
         */
        private const val AIRPLANE_HOLD_STEP_MS = 2_000L
        private const val RECOVER_TIMEOUT_MS = 90_000L

        /**
         * 恢復後查公網 IP 的輪詢間隔。
         *
         * 原本 3000ms，實測是整個流程最大的單一延遲來源：蜂巢介面在關閉飛航後
         * **約 1.5 秒**就拿到 IP（見 docs/relay-test-report.md 的射頻軌跡），
         * 但 3 秒的間隔讓偵測整整晚 1.5–4.5 秒才發生。
         *
         * 改成 500ms：每次探測就是一次 SOCKS5 連線，主程式在飛航期間不會被殺，
         * 連線幾乎立刻建立、立刻失敗返回，成本可以忽略。
         */
        private const val POLL_MS = 500L

        /**
         * 單次探測的 SOCKS 讀取逾時。
         *
         * 8 秒會掩蓋「其實已經通了」的時機：蜂巢半通時連線可能卡住，
         * 讓一次探測吃掉整個輪詢週期。3 秒足夠完成一次 80 port 的 HTTP 往返。
         */
        private const val PROBE_TIMEOUT_MS = 3000
        private const val IP_HOST = "api.ipify.org"
    }

    data class Status(
        val state: String = "idle",        // idle | running | changed | unchanged | failed
        val oldIp: String? = null,
        val newIp: String? = null,
        val attempts: Int = 0,
        val error: String? = null,
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        /** 這輪是否把 Wi-Fi 從飛航的關閉清單裡拿掉（true = 遠端控制口全程沒斷）。 */
        val keptWifi: Boolean = false,
    )

    @Volatile var status = Status()
        private set
    private val running = AtomicBoolean(false)

    /** 回 false 代表已有輪替在進行中。 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        status = Status(state = "running", startedAt = System.currentTimeMillis())
        Thread({
            try { status = rotate(status) } catch (e: Exception) {
                Log.w(TAG, "rotation crashed", e)
                status = status.copy(state = "failed", error = e.message ?: e.javaClass.simpleName)
            } finally {
                status = status.copy(finishedAt = System.currentTimeMillis())
                // ★ 安全網：任何離開路徑都不能把手機留在「射頻關著」的狀態 ——
                //   那台手機就再也連不回來，只能靠人去按實體開關。
                //
                //   判準同時看設定值與射頻：只看設定值會漏掉「設定值已是 0、
                //   射頻卻還關著」這種半關狀態（實際發生過，現象就是
                //   「進得去、回不來」）。這裡重試三次，因為單次可能剛好
                //   撞上射頻還沒 attach 完。
                if (AirplaneToggle.isAirplaneOn(context) || !AirplaneToggle.hasCellular(context)) {
                    var restored = false
                    for (attempt in 1..3) {
                        if (setAirplane(false)) { restored = true; break }
                        Log.w(TAG, "airplane restore attempt $attempt failed")
                        Thread.sleep(2_000L)
                    }
                    if (!restored) {
                        Log.e(
                            TAG,
                            "STUCK: airplane mode could not be turned off " +
                                "(setting=${AirplaneToggle.isAirplaneOn(context)}, " +
                                "cellular=${AirplaneToggle.hasCellular(context)})"
                        )
                    }
                }
                // 射頻清單一定要還原成使用者的原設定，否則他手動開飛航時會發現
                // Wi-Fi 沒關 —— 那是全域系統設定，不能留在我們的偏好上。
                //
                // 但**飛航還開著時不還原**：這時把 wifi 加回去會立刻關掉 Wi-Fi，
                // 遠端就再也連不進來救。寧可留著讓 Wi-Fi 通，下次啟動由
                // AirplaneToggle.cleanupIfStale() 收拾。
                if (!AirplaneToggle.isAirplaneOn(context)) {
                    AirplaneToggle.restoreRadios(context)
                } else {
                    Log.w(TAG, "airplane still on; keeping Wi-Fi alive so remote can reconnect")
                }
                running.set(false)
            }
        }, "ip-rotate").apply { isDaemon = true; start() }
        return true
    }

    private fun rotate(initial: Status): Status {
        if (!AirplaneToggle.canToggle(context)) {
            return initial.copy(
                state = "failed",
                error = "no way to toggle airplane mode: grant WRITE_SECURE_SETTINGS " +
                    "or set this app as the default digital assistant"
            )
        }
        // IP 輪替只需要讓 modem 重新 attach，沒有理由把遠端控制所依賴的 Wi-Fi
        // 一起斷掉。這一步把 wifi 從飛航的關閉清單裡拿掉（需要 WRITE_SECURE_SETTINGS）；
        // 沒有權限時回 null，行為與原本相同。
        val keptWifi = AirplaneToggle.keepWifiAlive(context) != null
        val oldIp = fetchPublicIp()
        var s = initial.copy(oldIp = oldIp, keptWifi = keptWifi)
        status = s
        for (attempt in 1..MAX_ATTEMPTS) {
            s = s.copy(attempts = attempt)
            status = s
            Log.i(TAG, "attempt $attempt: old=$oldIp")
            if (!setAirplane(true)) return s.copy(state = "failed", error = "airplane mode did not turn on")
            // 第一次短、之後遞增：快路徑要快，但不能因為 hold 太短就永遠換不到 IP
            val hold = AIRPLANE_HOLD_MS + (attempt - 1) * AIRPLANE_HOLD_STEP_MS
            Thread.sleep(hold)
            if (!setAirplane(false)) return s.copy(state = "failed", error = "airplane mode did not turn off")
            val newIp = waitForPublicIp()
                ?: return s.copy(state = "failed", error = "cellular egress did not recover in ${RECOVER_TIMEOUT_MS / 1000}s")
            s = s.copy(newIp = newIp)
            status = s
            if (oldIp == null || newIp != oldIp) return s.copy(state = "changed")
        }
        return s.copy(state = "unchanged")
    }

    /**
     * 切換飛航模式並確認**射頻真的跟著變**（不只是設定值）。
     *
     * 為什麼強調射頻：實測發現「寫 Settings.Global.AIRPLANE_MODE_ON」在
     * Android 15 / MIUI 上不會切射頻，只會改設定值。如果只驗證設定值，
     * 流程會一路「成功」到最後、IP 卻沒變。詳見 [AirplaneToggle]。
     */
    private fun setAirplane(on: Boolean): Boolean = AirplaneToggle.toggle(context, on)

    private fun waitForPublicIp(): String? {
        val deadline = System.currentTimeMillis() + RECOVER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            fetchPublicIp()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** 經 127.0.0.1:socksPort（主程式）以 SOCKS5 查 api.ipify.org；失敗回 null。 */
    fun fetchPublicIp(): String? {
        val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
        val user = prefs.getString("socks_user", "") ?: ""
        val pass = prefs.getString("socks_pass", "") ?: ""
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort()), 3000)
                s.soTimeout = PROBE_TIMEOUT_MS
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())
                val useAuth = user.isNotEmpty() && pass.isNotEmpty()
                out.write(if (useAuth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
                if (inp.readByte().toInt() != 5) return null
                when (inp.readByte().toInt()) {
                    0 -> {}
                    2 -> {
                        val u = user.toByteArray(); val p = pass.toByteArray()
                        out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                        inp.readByte()
                        if (inp.readByte().toInt() != 0) return null
                    }
                    else -> return null
                }
                val host = IP_HOST.toByteArray()
                out.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(0, 80))
                inp.readByte()
                if (inp.readByte().toInt() != 0) return null
                inp.readByte()
                when (inp.readByte().toInt()) {
                    1 -> inp.skipBytes(4)
                    4 -> inp.skipBytes(16)
                    3 -> inp.skipBytes(inp.readUnsignedByte())
                    else -> return null
                }
                inp.skipBytes(2)
                out.write("GET / HTTP/1.1\r\nHost: $IP_HOST\r\nConnection: close\r\n\r\n".toByteArray())
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = inp.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                val resp = buf.toString("ISO-8859-1")
                if (!resp.startsWith("HTTP/1.1 200") && !resp.startsWith("HTTP/1.0 200")) return null
                ControlHttp.parseIpBody(resp.substringAfter("\r\n\r\n"))
            }
        } catch (e: IOException) {
            null
        }
    }
}
