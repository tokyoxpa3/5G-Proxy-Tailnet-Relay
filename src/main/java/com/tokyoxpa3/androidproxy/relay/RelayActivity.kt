package com.tokyoxpa3.androidproxy.relay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class RelayActivity : Activity() {

    private lateinit var portInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var batteryButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("relay", Context.MODE_PRIVATE)

        portInput = EditText(this).apply {
            hint = getString(R.string.hint_port)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", RelayService.DEFAULT_PORT).toString())
        }
        toggleButton = Button(this).apply { setOnClickListener { toggle() } }
        batteryButton = Button(this).apply {
            text = getString(R.string.btn_battery)
            setOnClickListener { requestBatteryExemption() }
        }
        statusText = TextView(this).apply { setPadding(0, pad, 0, pad) }
        val hint = TextView(this).apply { text = getString(R.string.setup_hint) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(portInput)
            addView(toggleButton)
            addView(batteryButton)
            addView(statusText)
            addView(hint)
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun toggle() {
        if (RelayService.isRunning) {
            startService(Intent(this, RelayService::class.java).setAction(RelayService.ACTION_STOP))
        } else {
            val port = portInput.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: RelayService.DEFAULT_PORT
            getSharedPreferences("relay", Context.MODE_PRIVATE).edit().putInt("port", port).apply()
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
        val pm = getSystemService(PowerManager::class.java)
        batteryButton.isEnabled = !pm.isIgnoringBatteryOptimizations(packageName)
        val addrs = RelayService.listenAddrs
        statusText.text = when {
            !running -> getString(R.string.status_stopped)
            addrs.isEmpty() -> getString(R.string.status_waiting)
            else -> getString(
                R.string.status_running,
                addrs.joinToString(", "),
                portInput.text.toString().toIntOrNull() ?: RelayService.DEFAULT_PORT,
                RelayService.activeConnections.get()
            )
        }
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
}
