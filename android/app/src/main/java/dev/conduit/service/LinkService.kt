package dev.conduit.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.feature.CallHandler
import dev.conduit.feature.SmsHandler
import dev.conduit.feature.StatusReporter
import dev.conduit.net.LinkManager
import dev.conduit.ui.ClipboardActivity
import dev.conduit.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the control connection to the Mac alive. */
class LinkService : Service() {
    private var watcher: Job? = null
    private var updates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        StatusReporter.register(this)
        CallHandler.register(this)
        SmsHandler.register(this)
        LinkManager.start()
        updates = LinkManager.scope.launch {
            while (true) {
                dev.conduit.feature.Updater.check(applicationContext, notify = true)
                kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
            }
        }
        watcher = LinkManager.scope.launch {
            LinkManager.state.map { it.detail }.distinctUntilChanged().collect { detail ->
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(detail))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LinkManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        LinkManager.start()
        LinkManager.kick()
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        updates?.cancel()
        StatusReporter.unregister(this)
        CallHandler.unregister(this)
        SmsHandler.unregister(this)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val clip = PendingIntent.getActivity(
            this, 1, Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, ConduitApp.CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Conduit")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Send clipboard", clip).build())
            .build()
    }

    companion object {
        const val NOTIF_ID = 1
        private const val ACTION_STOP = "dev.conduit.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinkService::class.java).setAction(ACTION_STOP))
        }
    }
}
