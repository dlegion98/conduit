package dev.conduit.feature

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.conduit.net.LinkManager
import dev.conduit.ui.MirrorConsentActivity

/** Quick Settings toggle: start or stop mirroring this screen to the Mac. */
class MirrorTileService : TileService() {
    override fun onStartListening() = refresh()

    private fun refresh() {
        val s = LinkManager.state.value
        qsTile?.apply {
            state = when {
                !s.connected -> Tile.STATE_UNAVAILABLE
                s.mirroring -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            subtitle = if (s.connected) s.macName else "Not connected"
            updateTile()
        }
    }

    override fun onClick() {
        if (LinkManager.state.value.mirroring) {
            MirrorService.stop(this); refresh(); return
        }
        val intent = Intent(this, MirrorConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
