package dev.conduit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.conduit.net.LinkManager
import dev.conduit.service.LinkService

class ConduitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_LINK, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Keeps the link to your Mac running"
                    setShowBadge(false)
                },
                NotificationChannel(CH_ALERTS, "From your Mac", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Files, links and requests sent from your Mac"
                },
                NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setBypassDnd(true)
                },
            )
        )
        LinkManager.init(this)
        if (LinkManager.pairing.load() != null) {
            // Starting a foreground service can be refused if the process woke up in the
            // background (for example, when Android binds the notification listener).
            runCatching { LinkService.start(this) }.onFailure { LinkManager.start() }
        }
    }

    companion object {
        const val CH_LINK = "link"
        const val CH_ALERTS = "alerts"
        const val CH_RING = "ring"
    }
}
