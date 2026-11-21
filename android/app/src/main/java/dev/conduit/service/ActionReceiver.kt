package dev.conduit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.conduit.feature.MirrorService
import dev.conduit.feature.RingHandler
import dev.conduit.feature.CameraService
import dev.conduit.net.LinkManager

/** Handles buttons on Conduit's own notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RING -> RingHandler.stop(context)
            ACTION_STOP_MIRROR -> MirrorService.stop(context)
            ACTION_STOP_CAMERA -> CameraService.stop(context)
            ACTION_RING_MAC -> LinkManager.send("ring") { put("on", true) }
            ACTION_STOP_MAC_RING -> LinkManager.send("ring") { put("on", false) }
        }
    }

    companion object {
        const val ACTION_STOP_RING = "dev.conduit.STOP_RING"
        const val ACTION_STOP_MIRROR = "dev.conduit.STOP_MIRROR"
        const val ACTION_STOP_CAMERA = "dev.conduit.STOP_CAMERA"
        const val ACTION_RING_MAC = "dev.conduit.RING_MAC"
        const val ACTION_STOP_MAC_RING = "dev.conduit.STOP_MAC_RING"

        fun intent(context: Context, action: String) =
            Intent(context, ActionReceiver::class.java).setAction(action)
    }
}
