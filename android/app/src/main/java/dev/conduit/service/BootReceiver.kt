package dev.conduit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.conduit.net.LinkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (LinkManager.pairing.load() != null) LinkService.start(context)
    }
}
