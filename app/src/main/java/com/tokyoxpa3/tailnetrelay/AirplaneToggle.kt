package com.tokyoxpa3.tailnetrelay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log

/**
 * [IP 輪替] 飛航模式切換：**先試 OmniADB-Host shell（選配），再寫設定；
 * 兩者都驗證射頻真的動了，不行才退回數位助理**。
 *
 * ## 為什麼要「驗證射頻」而不是只驗證設定值
 *
 * 這一段是實測修正的結果，不是設計潔癖。曾經以為「有 WRITE_SECURE_SETTINGS
 * 就能寫 `Settings.Global.AIRPLANE_MODE_ON` 來切飛航」，並用
 * `cmd connectivity airplane-mode` 讀回確認 —— 但那是**循環論證**：
 * 那個指令讀的就是同一個設定值，不是射頻狀態。
 *
 * 改用蜂巢網路的 IPv4 位址（`ccmni1` 是否還有 `inet`）當客觀指標後，
 * 小米 Android 15 上的結果是：
 *
 * ```
 * T0     setting=0  ccmni=1
 * A t+3s setting=1  ccmni=1     ← 設定改了
 * A t+6s setting=1  ccmni=1
 * A t+9s setting=1  ccmni=1
 * A t+12s setting=1 ccmni=1     ← 射頻全程沒動
 * ```
 *
 * 對照組（`cmd connectivity airplane-mode enable`）同一台機器立刻 `ccmni=0`。
 * 所以「寫設定」在這台機器上**不會**切飛航，只是把鏡子擦亮。
 *
 * 經典配方「設定 + `am broadcast -a android.intent.action.AIRPLANE_MODE`」
 * 也一起死了 —— 那是受保護廣播，連 adb shell（uid 2000）都被拒：
 * ```
 * SecurityException: Permission Denial: not allowed to send broadcast
 *   android.intent.action.AIRPLANE_MODE from pid=..., uid=2000
 * ```
 *
 * ## 目前真正能切射頻的只有兩條路
 *
 * | 方式 | 從 App | 從 adb shell | 射頻真的關 |
 * |---|---|---|---|
 * | 寫 `Settings.Global.AIRPLANE_MODE_ON` | ✅ | ✅ | ❌ |
 * | 設定 + `AIRPLANE_MODE` 廣播 | ❌ | ❌ | ❌ |
 * | `cmd connectivity airplane-mode` | ❌ | ✅ | ✅ |
 * | 數位助理 + `ACTION_VOICE_CONTROL_AIRPLANE_MODE` | ✅ | — | ✅ |
 *
 * 也就是說：**不靠 adb / root，App 自己只有數位助理這一條路。**
 * 上游作者把助理當成必要條件不是偷懶，是別無選擇。
 *
 * ## 但「飛航關哪些射頻」是可以調的
 *
 * 切飛航躲不開助理，可是可以讓它**只關蜂巢**：把 `wifi` 從
 * `Settings.Global.AIRPLANE_MODE_RADIOS`（本機預設
 * `cell,bluetooth,uwb,wifi,wimax`）裡拿掉，Wi-Fi 與 tailnet 就不會斷。
 * 見 [keepWifiAlive]。
 *
 * 這是本檔案裡對「遠端使用體驗」影響最大的一個發現 —— 總耗時沒變，
 * 但輪替期間遠端從「完全盲等 5–8 秒」變成「全程可查 /status」。
 *
 * ## 那 WRITE_SECURE_SETTINGS 還有什麼用
 *
 * 有，但不是用來切飛航：
 *   - 部分較舊的 ROM 仍會觀察這個設定並真的切射頻 —— 所以設定這條路留著當
 *     **第一嘗試**，用射頻驗證把關，能用就賺到。
 *   - 其他 secure/global 設定（例如關掉 adb 金鑰效期）仍然需要它。
 *   - 授予它會落在 `install permissions`（寫在 packages.xml），重開機不掉。
 *
 * ## 要真正擺脫助理，需要一條 shell 通道（已由 [ShellBackend] 實作）
 *
 * 唯一不需要換掉使用者助理的辦法，是拿到 shell（uid 2000）去跑
 * `cmd connectivity airplane-mode enable|disable`。取得方式有三：
 *   1. **本機 adbd** —— 採用這條：OmniADB-Host 已把 5555 埠與配對金鑰常駐化，
 *      並開出 `RelayBridgeProvider` 這個只認固定動作的窄介面。私鑰留在 Host，
 *      本 App 不自行配對、不下任意指令。
 *   2. Shizuku（本質也是用 adb 起一個 shell 服務）。
 *   3. root。
 *
 * Host 沒裝、adbd 沒活著或指令失敗時，就往下走設定 / 助理。見 [AirplaneAssistant]。
 */
object AirplaneToggle {

    private const val TAG = "AirplaneToggle"

    /** 後端識別字，給 /status 與 UI 顯示用。 */
    const val BACKEND_SHELL = "shell"         // OmniADB-Host 窄介面（選配，最優先）
    const val BACKEND_SETTINGS = "settings"   // 有權限，且實測射頻真的會動
    const val BACKEND_ASSISTANT = "assistant" // 退回數位助理
    const val BACKEND_NONE = "none"           // 三條路都不通

    /**
     * 寫入設定後等射頻變化的時間（開飛航：等蜂巢消失，通常 1–3 秒）。
     */
    private const val SETTINGS_VERIFY_ON_MS = 6_000L

    /**
     * 關飛航後等蜂巢回來的時間。
     *
     * 比 [SETTINGS_VERIFY_ON_MS] 長很多：射頻要重新掃頻、attach、取得 PDP。
     * 實測這台小米要 10–30 秒，偶爾更久。
     *
     * ★ 這個非對稱是必要的，不能為了對稱而縮短：曾經用「設定值 == 0」當
     *   關閉成功的判準，結果是設定值立刻變 0、射頻卻還關著 —— 流程一路
     *   回報成功，手機實際上沒有網路，而且因為「看起來已經關了」，連
     *   退回助理這條路都不會走。使用者看到的現象就是「進得去、回不來」。
     */
    private const val SETTINGS_VERIFY_OFF_MS = 40_000L

    fun hasPermission(context: Context): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun isAirplaneOn(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (e: Exception) {
        false
    }

    /**
     * 現在能不能切換。
     *
     * 三條路任一可用即可；全部不通時要老實回 false（呼叫端會回 412 並附上該做什麼）。
     * [ShellBackend.isAvailable] 只探測 Host 在不在，不保證 adbd 活著 —— 真正
     * 跑指令失敗時 [toggle] 會繼續往下一段降級，不會在這裡誤判。
     */
    fun canToggle(context: Context): Boolean =
        ShellBackend.isAvailable(context) || settingsUsable(context) || AirplaneAssistant.isActive

    /** 目前實際會用的後端（給 /status 顯示；真正用哪條要等 [toggle] 才知道）。 */
    fun backend(context: Context): String = when {
        ShellBackend.isAvailable(context) -> BACKEND_SHELL
        settingsUsable(context) -> BACKEND_SETTINGS
        AirplaneAssistant.isActive -> BACKEND_ASSISTANT
        else -> BACKEND_NONE
    }

    /**
     * 上一次 [toggle] **實際成功**用的是哪條路。
     *
     * 會跟 [backend] 不同：有權限但這台 ROM 寫設定不切射頻時，[backend] 說
     * "settings"，這裡會是 "assistant"。null 代表還沒成功切換過。
     */
    @Volatile var lastUsedBackend: String? = null
        private set

    /**
     * 這台機器上「寫設定」到底有沒有用。
     *
     * 用**單一**旗標而不是開／關各一個：會觀察這個設定的 ROM 是整組行為，
     * 不存在「開有效、關無效」這種組合。分兩個旗標會導致一個具體的浪費 ——
     * 開的時候已經證明沒用，關的時候還要再等一個 40 秒的驗證週期。
     * 實測那一輪輪替因此多花了 40 秒，使用者感受到的就是「回不來」。
     *
     * null = 還沒驗證過。
     */
    @Volatile private var settingsBackendWorks: Boolean? = null

    /** 記在 prefs 裡的是「在這個系統版本上不work」，換 ROM / OTA 後會重新試一次。 */
    private const val KEY_BROKEN_FP = "airplane_settings_broken_fp"

    /** 被 [keepWifiAlive] 改動前的 `airplane_mode_radios` 原值；有值代表尚未還原。 */
    private const val KEY_RADIOS_ORIG = "airplane_radios_orig"

    private fun prefs(context: Context) =
        context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    /** 這台機器值不值得先試「寫設定」這條路。 */
    private fun settingsUsable(context: Context): Boolean {
        if (!hasPermission(context)) return false
        settingsBackendWorks?.let { return it }
        val works = prefs(context).getString(KEY_BROKEN_FP, null) != android.os.Build.FINGERPRINT
        settingsBackendWorks = works
        return works
    }

    private fun markSettingsBroken(context: Context) {
        settingsBackendWorks = false
        prefs(context).edit().putString(KEY_BROKEN_FP, android.os.Build.FINGERPRINT).apply()
    }

    /**
     * 把 Wi-Fi 從「飛航模式要關閉的射頻清單」裡拿掉，讓飛航只關蜂巢。
     *
     * ## 為什麼要這樣做
     *
     * IP 輪替只需要讓 **modem 重新 attach**，而飛航模式會連 Wi-Fi 一起關掉。
     * 對遠端使用者來說，那才是真正的痛點：控制口掛在 tailnet 上，而 tailnet 走
     * Wi-Fi —— 飛航一開，遠端就失去觀測能力 5–8 秒，只能盲等。
     *
     * 系統關哪些射頻是由 `Settings.Global.AIRPLANE_MODE_RADIOS` 決定的
     * （本機預設 `cell,bluetooth,uwb,wifi,wimax`）。把它改成不含 `wifi`，
     * 飛航就只關蜂巢。
     *
     * ## 實測（POCO rodin / Android 15 / MIUI）
     *
     * ```
     * 改前  air=1  cell=0  Wifi is enabled   ← 這行就是差別
     * PC 側每 0.4 秒探測 LAN(5555) 與 tailnet(8081)，34 次全部 ok、零次中斷
     * 同一輪 IP 由 49.215.88.x → 49.215.22.x（照樣換）
     * ```
     *
     * ## 原始值要持久化
     *
     * 這是**全域系統設定**，如果只在記憶體裡記著，行程一被殺就會留在「飛航不關
     * Wi-Fi」的狀態 —— 使用者手動開飛航時會發現 Wi-Fi 沒關，行為與預期不符。
     * 所以原始值寫進 prefs，[restoreRadios] 一定會還原，[cleanupIfStale] 負責
     * 處理上次異常退出的殘留。
     *
     * @return 原始值（給 [restoreRadios] 用）；不需要或做不到時回 null
     */
    fun keepWifiAlive(context: Context): String? {
        if (!hasPermission(context)) return null
        val cr = context.contentResolver
        val orig = try {
            Settings.Global.getString(cr, Settings.Global.AIRPLANE_MODE_RADIOS)
        } catch (e: Exception) {
            null
        } ?: return null
        val parts = orig.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        // 本來就不關 Wi-Fi（有些 ROM 這樣設定）—— 不必動它
        if (!parts.contains("wifi")) return null
        val filtered = parts.filter { it != "wifi" }.joinToString(",")
        // 全部拿掉等於「飛航什麼都不關」，那不是我們要的語意
        if (filtered.isEmpty()) return null
        return try {
            prefs(context).edit().putString(KEY_RADIOS_ORIG, orig).apply()
            Settings.Global.putString(cr, Settings.Global.AIRPLANE_MODE_RADIOS, filtered)
            Log.i(TAG, "airplane radios: $orig -> $filtered (Wi-Fi 保留)")
            orig
        } catch (e: Exception) {
            Log.w(TAG, "could not filter airplane radios", e)
            null
        }
    }

    /** 還原 [keepWifiAlive] 改動的射頻清單。呼叫前務必先確認飛航已關閉。 */
    fun restoreRadios(context: Context) {
        val saved = prefs(context).getString(KEY_RADIOS_ORIG, null) ?: return
        try {
            Settings.Global.putString(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_RADIOS,
                saved
            )
            prefs(context).edit().remove(KEY_RADIOS_ORIG).apply()
            Log.i(TAG, "airplane radios restored to $saved")
        } catch (e: Exception) {
            Log.w(TAG, "could not restore airplane radios", e)
        }
    }

    /**
     * 行程啟動時收拾上次異常退出留下的殘留。
     *
     * 只有在「有殘留值、而且飛航是關的」時候才還原 —— 飛航開著代表可能正有人
     * 在輪替（或使用者自己開了飛航），這時動清單會產生難以解釋的行為。
     */
    fun cleanupIfStale(context: Context) {
        if (prefs(context).getString(KEY_RADIOS_ORIG, null) == null) return
        if (isAirplaneOn(context)) {
            Log.w(TAG, "stale airplane radios left alone: airplane mode is on")
            return
        }
        Log.w(TAG, "found stale airplane radios from a previous run; restoring")
        restoreRadios(context)
    }

    /**
     * 切換飛航模式，並**確認射頻真的跟著變**。
     *
     * 順序：OmniADB-Host shell → 寫設定 → 驗證射頻 → 沒動就退回助理 → 再驗證。
     * shell 是選配，沒裝 Host 或 adbd 沒活著時直接落到原本兩段。
     *
     * 每一段都用射頻（[hasCellular]）當判準，不用設定值 —— 設定值會被自己
     * 寫進去，拿它驗證等於自己證明自己；對端回覆「指令成功」也一樣不採信。
     *
     * @return true 代表射頻狀態確實變成 [enabled]
     */
    fun toggle(context: Context, enabled: Boolean): Boolean {
        val verifyMs = if (enabled) SETTINGS_VERIFY_ON_MS else SETTINGS_VERIFY_OFF_MS
        val trySettings = settingsUsable(context)

        // [1/3] OmniADB-Host 窄介面：免助理、免 root、走 loopback 不受飛航影響。
        // 失敗就安靜往下走，讓沒有 Host 的裝置維持原本的降級行為。
        if (ShellBackend.isAvailable(context) &&
            ShellBackend.setAirplane(context, enabled) &&
            awaitRadio(context, enabled, verifyMs)
        ) {
            lastUsedBackend = BACKEND_SHELL
            Log.i(TAG, "airplane=$enabled via OmniADB-Host shell (radio verified)")
            return true
        }

        if (trySettings) {
            writeSetting(context, enabled)
            if (awaitRadio(context, enabled, verifyMs)) {
                lastUsedBackend = BACKEND_SETTINGS
                Log.i(TAG, "airplane=$enabled via settings (radio verified)")
                return true
            }
            // 這台 ROM 不觀察這個設定 —— 開關都一樣，記下來別再試
            markSettingsBroken(context)
            Log.w(
                TAG,
                "settings write did not move the radio (cellular=${hasCellular(context)}); " +
                    "disabling settings backend and using assistant"
            )
            // 把設定值還原成「與目前射頻相反的狀態」，讓助理那條路是一次真正的
            // 狀態轉換。否則設定值已經等於目標值時，助理流程可能判定「已經是
            // 這個狀態了」而靜默結束，什麼都不做。
            writeSetting(context, !enabled)
        }

        if (!AirplaneAssistant.isActive) {
            Log.w(TAG, "no working backend for airplane=$enabled")
            return false
        }
        if (!AirplaneAssistant.requestAirplane(enabled)) return false
        if (awaitRadio(context, enabled, verifyMs)) {
            lastUsedBackend = BACKEND_ASSISTANT
            Log.i(TAG, "airplane=$enabled via assistant (radio verified)")
            return true
        }
        Log.w(TAG, "assistant could not change the radio (cellular=${hasCellular(context)})")
        return false
    }

    private fun writeSetting(context: Context, enabled: Boolean) {
        try {
            Settings.Global.putInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                if (enabled) 1 else 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "settings write failed", e)
        }
    }

    /**
     * 等射頻真的變成 [enabled] 對應的樣子。
     *
     * `enabled = true` → 等蜂巢消失；`enabled = false` → 等蜂巢回來。
     */
    private fun awaitRadio(context: Context, enabled: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasCellular(context) != enabled) return true
            Thread.sleep(300)
        }
        return hasCellular(context) != enabled
    }

    /**
     * 射頻層面是否還有行動網路 —— 客觀指標，不受設定值影響。
     *
     * 取不到 ConnectivityManager 時回 true（寧可誤判成「還有」，也不要
     * 誤判成「已關閉」而讓上層以為切換成功）。
     */
    fun hasCellular(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
        } catch (e: Exception) {
            true
        }
    }
}
