package com.tokyoxpa3.tailnetrelay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class RelayActivity : Activity() {

    private lateinit var portInput: EditText
    private lateinit var controlPortInput: EditText
    private lateinit var socksUserInput: EditText
    private lateinit var socksPassInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var autoStartCheck: CheckBox
    private lateinit var batteryButton: Button
    private lateinit var rotationModeButton: Button
    private lateinit var usageButton: Button
    private lateinit var rotateButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    /** 一列可點擊複製的內容。value 是 lazy 的：Tailscale 位址、連接埠、權杖會隨時變。 */
    private data class CopyRow(val view: TextView, val label: String, val value: () -> String)
    private val copyRows = ArrayList<CopyRow>()
    private var primaryColor = Color.WHITE
    private var secondaryColor = Color.LTGRAY
    private var accentColor = Color.CYAN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)

        // 用主題的實際文字色，日/夜間模式都保持對比；不要用寫死的全白全黑。
        val ta = theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary, android.R.attr.colorAccent)
        )
        try {
            // getColor 會自行展開 ColorStateList 的預設色，且相容 minSdk 26
            // （ColorStateList.defaultColor 是 API 31 才有的方法）。
            primaryColor = ta.getColor(0, primaryColor)
            secondaryColor = ta.getColor(1, secondaryColor)
            accentColor = ta.getColor(2, accentColor)
        } finally {
            ta.recycle()
        }

        portInput = numberInput(getString(R.string.hint_port), prefs.getInt("port", RelayService.DEFAULT_PORT))
        controlPortInput = numberInput(getString(R.string.hint_control_port),
            prefs.getInt("control_port", RelayService.DEFAULT_CONTROL_PORT))
        socksUserInput = EditText(this).apply {
            hint = getString(R.string.hint_socks_user)
            setText(prefs.getString("socks_user", ""))
        }
        socksPassInput = EditText(this).apply {
            hint = getString(R.string.hint_socks_pass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("socks_pass", ""))
        }
        toggleButton = Button(this).apply { setOnClickListener { toggle() } }
        // 開機自動啟動：以前是隱含行為（關機前在跑就還原），介面上完全看不到，
        // 使用者不會知道 relay 能在開機後自己回來，也沒有辦法拒絕。
        autoStartCheck = CheckBox(this).apply {
            text = getString(R.string.label_auto_start)
            textSize = 16f
            setTextColor(primaryColor)
            setPadding(0, 0, 0, 0)
            // 預設 tint 在深色主題上偏暗，勾與不勾差別太小 → 明確指定強調色
            buttonTintList = ColorStateList.valueOf(accentColor)
            // 先設 isChecked 再掛 listener，避免初始化時多寫一次 prefs
            isChecked = prefs.getBoolean(RelayService.PREF_AUTO_START, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(RelayService.PREF_AUTO_START, checked).apply()
            }
        }
        batteryButton = Button(this).apply {
            text = getString(R.string.btn_battery)
            setOnClickListener { requestBatteryExemption() }
        }
        rotationModeButton = Button(this).apply {
            text = getString(R.string.btn_rotation_none)
            setOnClickListener { showRotationSetup() }
        }
        usageButton = Button(this).apply {
            text = getString(R.string.btn_usage_access)
            setOnClickListener { requestUsageAccess() }
        }
        rotateButton = Button(this).apply {
            text = getString(R.string.btn_rotate)
            setOnClickListener {
                saveCredentials()
                val started = RelayService.rotator?.start() ?: false
                if (!started) Toast.makeText(this@RelayActivity, R.string.toast_rotate_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        statusText = TextView(this).apply { setPadding(0, pad, 0, pad) }
        // 權杖只在下方「2. 點一下即可複製」那一段出現一次就好。先前這裡另外放了一個
        // 可直接點的權杖，同一頁出現兩份同樣的祕密，既冗長也讓「該複製哪一個」變模糊。

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(portInput)
        root.addView(controlPortInput)
        root.addView(socksUserInput)
        root.addView(socksPassInput)
        root.addView(toggleButton)
        root.addView(autoStartCheck)
        root.addView(TextView(this).apply {
            text = getString(R.string.hint_auto_start)
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, 0, 0, pad / 2)
        })
        root.addView(batteryButton)
        root.addView(rotationModeButton)
        root.addView(usageButton)
        root.addView(rotateButton)
        root.addView(statusText)
        buildHintSection(root, pad)
        setContentView(ScrollView(this).apply {
            addView(root.apply { setPadding(pad, pad, pad, pad) })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Android 9 以下：/status 的數據用量需要 subscriberId
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 2)
        }
    }

    private fun numberInput(hintText: String, value: Int) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    /**
     * 下方說明改成三段：設定 → 可複製的即用字串 → 補充說明。
     * 以前是一整塊小灰字，command / URL 得自己把 &lt;tailscale-ip&gt;、$TOKEN 換掉；
     * 現在每一行都由 [render] 帶入真實位址與權杖，點一下直接進剪貼簿。
     */
    private fun buildHintSection(root: LinearLayout, pad: Int) {
        root.addView(divider())
        root.addView(section(R.string.hint_sec_setup))
        root.addView(body(R.string.hint_setup_body))

        root.addView(section(R.string.hint_sec_copy))
        root.addView(body(R.string.hint_copy_tip))
        root.addView(copyRow(R.string.copy_token) { ControlAuth.tokenFor(this) })
        root.addView(copyRow(R.string.copy_socks) { "socks5://${tailHost()}:${socksPort()}" })
        root.addView(copyRow(R.string.copy_rotate_url) { "http://${tailHost()}:${controlPort()}/rotate?token=${token()}" })
        root.addView(copyRow(R.string.copy_status_url) { "http://${tailHost()}:${controlPort()}/status?token=${token()}" })
        root.addView(copyRow(R.string.copy_curl_rotate) {
            "curl -H \"Authorization: Bearer ${token()}\" -X POST http://${tailHost()}:${controlPort()}/rotate"
        })
        root.addView(copyRow(R.string.copy_curl_status) {
            "curl -H \"Authorization: Bearer ${token()}\" http://${tailHost()}:${controlPort()}/status"
        })

        root.addView(section(R.string.hint_sec_notes))
        root.addView(body(R.string.setup_hint))
        // 底部留白，避免最後一段貼在導覽列上
        root.addView(View(this).apply { minimumHeight = pad })
    }

    private fun section(res: Int) = TextView(this).apply {
        text = getString(res)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentColor)
        setPadding(0, (18 * resources.displayMetrics.density).toInt(), 0,
            (6 * resources.displayMetrics.density).toInt())
    }

    private fun body(res: Int) = TextView(this).apply {
        text = getString(res)
        textSize = 13f
        setTextColor(secondaryColor)
        setLineSpacing(0f, 1.25f)
        setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun divider() = View(this).apply {
        val d = resources.displayMetrics.density
        setBackgroundColor((accentColor and 0x00FFFFFF) or 0x55000000)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, d.toInt())
        ).apply { topMargin = (14 * d).toInt() }
    }

    /** 標籤 + 等寬字內容的框，整塊可點；複製的是 [value]，不是畫面上的標籤。 */
    private fun copyRow(labelRes: Int, value: () -> String): View {
        val d = resources.displayMetrics.density
        val p = (10 * d).toInt()
        val label = getString(labelRes)
        val tag = TextView(this).apply {
            text = "$label · ${getString(R.string.copy_tap)}"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(0, (8 * d).toInt(), 0, (3 * d).toInt())
        }
        val valueView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(primaryColor)
            setPadding(p, p, p, p)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                setColor((accentColor and 0x00FFFFFF) or 0x18000000)
                setStroke(maxOf(1, (d).toInt()), (accentColor and 0x00FFFFFF) or 0x66000000)
            }
            addView(valueView)
            isClickable = true
            setOnClickListener { copy(label, value()) }
        }
        copyRows.add(CopyRow(valueView, label, value))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tag)
            addView(box)
        }
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, getString(R.string.toast_copied, label), Toast.LENGTH_SHORT).show()
    }

    /** Tailscale 位址可能是 IPv6；URL / socks5 URI 需要方括號。沒連上時給占位符。 */
    private fun tailHost(): String {
        val a = RelayService.listenAddrs.firstOrNull() ?: "<tailscale-ip>"
        return if (a.contains(':') && !a.startsWith("[")) "[$a]" else a
    }

    private fun socksPort() = portInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_PORT
    private fun controlPort() = controlPortInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_CONTROL_PORT
    private fun token() = ControlAuth.tokenFor(this)

    private fun saveCredentials() {
        getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
            .putString("socks_user", socksUserInput.text.toString().trim())
            .putString("socks_pass", socksPassInput.text.toString())
            .apply()
    }

    private fun toggle() {
        if (RelayService.isRunning) {
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
                .putBoolean(RelayService.PREF_RELAY_ENABLED, false).apply()
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
        } else {
            val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: RelayService.DEFAULT_PORT
            val controlPort = controlPortInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 }
                ?: RelayService.DEFAULT_CONTROL_PORT
            if (port == controlPort) {
                Toast.makeText(this, R.string.toast_port_conflict, Toast.LENGTH_SHORT).show()
                return
            }
            saveCredentials()
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit()
                .putInt("port", port)
                .putInt("control_port", controlPort)
                .putBoolean(RelayService.PREF_RELAY_ENABLED, true)
                .apply()
            startForegroundService(
                Intent(this, RelayService::class.java)
                    .setAction(RelayService.ACTION_START)
                    .putExtra(RelayService.EXTRA_PORT, port)
            )
        }
        handler.postDelayed({ render() }, 300)
    }

    private fun render() {
        val running = RelayService.isRunning
        toggleButton.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        portInput.isEnabled = !running
        controlPortInput.isEnabled = !running
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.isEnabled = !pm.isIgnoringBatteryOptimizations(packageName)
        // 這顆是「IP 輪替方式」的狀態列：已就緒時純顯示，點了不該再有任何動作或彈窗。
        val backend = AirplaneToggle.backend(this)
        rotationModeButton.text = getString(when (backend) {
            AirplaneToggle.BACKEND_SHELL -> R.string.btn_rotation_shell
            AirplaneToggle.BACKEND_SETTINGS -> R.string.btn_rotation_settings
            AirplaneToggle.BACKEND_ASSISTANT -> R.string.btn_rotation_assistant
            else -> R.string.btn_rotation_none
        })
        rotationModeButton.isClickable = backend != AirplaneToggle.BACKEND_SHELL
        rotateButton.isEnabled = running && AirplaneToggle.canToggle(this)
        usageButton.text = getString(
            if (DeviceStatus.hasUsageAccess(this)) R.string.btn_usage_access_granted else R.string.btn_usage_access
        )
        val addrs = RelayService.listenAddrs
        val relayLine = when {
            !running -> getString(R.string.status_stopped)
            addrs.isEmpty() -> getString(R.string.status_waiting)
            else -> getString(
                R.string.status_running,
                addrs.joinToString(", "),
                portInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_PORT,
                RelayService.activeConnections.get(),
                controlPortInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_CONTROL_PORT
            )
        }
        val r = RelayService.rotator?.status
        val rotateLine = if (r == null || r.state == "idle") "" else getString(
            R.string.status_rotation, r.state, r.oldIp ?: "?", r.newIp ?: "?", r.attempts, r.error ?: ""
        )
        statusText.text = relayLine + rotateLine
        // 位址 / 連接埠 / 權杖可能變，每秒跟著狀態一起刷新
        copyRows.forEach { it.view.text = it.value() }
    }

    // Doze 下未加入白名單的 App 會被切斷網路（包含 tun0 進來的連線）
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // /status 的 data_usage 需要「使用情形存取權」（特殊權限，只能在設定頁開）
    private fun requestUsageAccess() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /**
     * 「IP 輪替方式」按鈕的動作，按後端分流。
     *
     * BACKEND_SHELL 已就緒時是純狀態顯示：不彈窗、不跳轉，避免「明明好了還叫人去設定」。
     * 未設定才給兩個真的能動作的選項（不要「取消」，誤觸直接按返回鍵即可離開）。
     */
    private fun showRotationSetup() {
        when (AirplaneToggle.backend(this)) {
            AirplaneToggle.BACKEND_SHELL -> Unit
            AirplaneToggle.BACKEND_SETTINGS -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_title_ready)
                    .setMessage(R.string.dlg_ready_settings)
                    .setPositiveButton(R.string.dlg_act_assistant) { _, _ -> openAssistantSettings() }
                    .show()
            }
            AirplaneToggle.BACKEND_ASSISTANT -> openAssistantSettings()
            else -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_title_setup)
                    .setMessage(R.string.dlg_setup_msg)
                    .setPositiveButton(R.string.dlg_act_host) { _, _ -> openHostOrExplain() }
                    .setNeutralButton(R.string.dlg_act_assistant) { _, _ -> openAssistantSettings() }
                    .show()
            }
        }
    }

    /** Host 裝了就開它的主頁（adbd 掉線時可直接按「立即檢查並修復」）；沒裝就講清楚去哪拿。 */
    private fun openHostOrExplain() {
        val launch = try {
            packageManager.getLaunchIntentForPackage(HOST_PACKAGE)
        } catch (e: Exception) {
            null
        }
        if (launch != null) {
            try {
                startActivity(launch)
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot launch OmniADB-Host", e)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_title_host)
            .setMessage(R.string.dlg_host_missing)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openAssistantSettings() {
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(action)); return } catch (e: Exception) {}
        }
    }

    companion object {
        private const val TAG = "RelayActivity"
        private const val HOST_PACKAGE = "com.omniadb.host"
    }
}
