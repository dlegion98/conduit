package dev.conduit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.conduit.feature.MirrorService
import dev.conduit.feature.RingHandler
import dev.conduit.feature.CameraService

/** Handles buttons on Conduit's own notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RING -> RingHandler.stop(context)
            ACTION_STOP_MIRROR -> MirrorService.stop(context)
            ACTION_STOP_CAMERA -> CameraService.stop(context)
        }
    }

    companion object {
        const val ACTION_STOP_RING = "dev.conduit.STOP_RING"
        const val ACTION_STOP_MIRROR = "dev.conduit.STOP_MIRROR"
        const val ACTION_STOP_CAMERA = "dev.conduit.STOP_CAMERA"

        fun intent(context: Context, action: String) =
            Intent(context, ActionReceiver::class.java).setAction(action)
    }
}
