package dev.conduit.feature

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.conduit.net.LinkManager

/** Sends battery level and charging state to the Mac. */
object StatusReporter {
    private var lastSent = -1 to false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = report(intent, force = false)
    }

    fun register(context: Context) {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun sendNow(context: Context) {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { report(it, force = true) }
    }

    private fun report(intent: Intent, force: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (!force && lastSent == (pct to charging)) return
        if (LinkManager.send("status") { put("battery", pct); put("charging", charging) }) {
            lastSent = pct to charging
        }
    }
}
