package dev.conduit.feature

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.conduit.net.LinkManager

/**
 * Tells the Mac about incoming calls and answers or declines them on request.
 * Call audio stays on the phone (or whatever headset it's connected to).
 */
object CallHandler {
    private var lastState = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
                else -> "idle"
            }
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            // Android sends the broadcast twice (with and without the number); skip the duplicate without it.
            if (state == lastState && number == null) return
            lastState = state
            LinkManager.send("call") {
                put("state", state)
                put("number", number ?: "")
                put("name", Contacts.nameFor(context, number))
            }
        }
    }

    fun register(context: Context) {
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    fun action(context: Context, action: String) {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            LinkManager.send("error") { put("feature", "calls"); put("message", "Allow phone-call permission in Conduit on your phone") }
            return
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        @Suppress("DEPRECATION")
        when (action) {
            "answer" -> telecom.acceptRingingCall()
            "decline", "end" -> telecom.endCall()
        }
    }
}
