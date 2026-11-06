package com.tokyoxpa3.tailnetrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 開機（或 App 更新後）還原 relay。
 *
 * 兩個條件同時成立才會啟動：
 *  1. 主畫面的「重新開機後自動啟動」勾選框開著（[RelayService.PREF_AUTO_START]）
 *  2. 關機前 relay 仍在執行（[RelayService.PREF_RELAY_ENABLED]，按過停止就是 false）
 *
 * 條件 2 讓「使用者按了停止」不會在下次開機被推翻；條件 1 則是讓使用者能明確說
 * 「我不要它開機自己起來」—— 以前只有條件 2，規則隱含在程式裡，介面上看不到。
 *
 * 為什麼預設 true（與 Pro / Client 的預設 false 相反）：relay 只綁 Tailscale 位址、
 * 控制 API 另有權杖，不是區域網路上任人可用的開放代理；而且這是 1.2.4 之前的既有行為，
 * 改成預設關會讓現有使用者的遠端存取在重開機後默默消失。要關的人自己取消勾選。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return

        val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(RelayService.PREF_AUTO_START, true)) {
            Log.i(TAG, "自動啟動已取消勾選，略過（$action）")
            return
        }
        if (!prefs.getBoolean(RelayService.PREF_RELAY_ENABLED, false)) {
            Log.i(TAG, "關機前已按停止，略過（$action）")
            return
        }
        if (RelayService.isRunning) return

        Log.i(TAG, "自動啟動 relay（$action）")
        try {
            // 不帶 EXTRA_PORT：服務會從設定讀埠號
            context.startForegroundService(
                Intent(context, RelayService::class.java).setAction(RelayService.ACTION_START)
            )
        } catch (e: Exception) {
            Log.e(TAG, "自動啟動失敗: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "TailnetRelay"
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
