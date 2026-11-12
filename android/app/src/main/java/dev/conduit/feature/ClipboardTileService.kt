package dev.conduit.feature

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.conduit.net.LinkManager
import dev.conduit.ui.ClipboardActivity

/** Quick Settings tile: "Send clipboard" to the Mac. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (LinkManager.isConnected) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
