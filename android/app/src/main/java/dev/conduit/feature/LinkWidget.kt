package dev.conduit.feature

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.conduit.R
import dev.conduit.net.LinkManager
import dev.conduit.net.LinkState
import dev.conduit.service.ActionReceiver
import dev.conduit.ui.ClipboardActivity
import dev.conduit.ui.MainActivity
import dev.conduit.ui.MirrorConsentActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Home-screen widget: link status and the Mac's battery, plus Send clipboard, Mirror and Find Mac.
 * It never wakes the phone on its own (no update period): it is redrawn only when the link
 * state changes while Conduit is already running.
 */
class LinkWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = views(context, Model.of(LinkManager.state.value))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Only what the widget shows, so unrelated state changes don't redraw it. */
    data class Model(
        val paired: Boolean,
        val connected: Boolean,
        val macName: String,
        val battery: Int,
        val charging: Boolean,
        val mirroring: Boolean,
        val ringing: Boolean,
        val waitingForWifi: Boolean,
    ) {
        companion object {
            fun of(s: LinkState) = Model(
                paired = s.paired, connected = s.connected, macName = s.macName, battery = s.macBattery,
                charging = s.macCharging, mirroring = s.mirroring, ringing = s.macRinging,
                waitingForWifi = s.detail == "Waiting for Wi-Fi",
            )
        }
    }

    companion object {
        /** Keeps every placed widget in step with the link. Called once from ConduitApp. */
        fun observe(context: Context) {
            val app = context.applicationContext
            LinkManager.scope.launch {
                LinkManager.state.map { Model.of(it) }.distinctUntilChanged().collect { refresh(app, it) }
            }
        }

        private fun refresh(context: Context, model: Model) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, LinkWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            val views = views(context, model)
            ids.forEach { runCatching { manager.updateAppWidget(it, views) } }
        }

        fun views(context: Context, m: Model): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_link)
            val name = m.macName.ifEmpty { "Mac" }
            v.setTextViewText(R.id.w_title, if (m.paired) name else "Conduit")
            v.setTextViewText(R.id.w_status, status(m))
            v.setInt(R.id.w_dot, "setColorFilter", context.getColor(if (m.connected) R.color.widget_ok else R.color.widget_off))

            val open = activity(context, 10, Intent(context, MainActivity::class.java))
            v.setOnClickPendingIntent(R.id.w_header, open)

            val text = context.getColor(R.color.widget_text)
            val dim = context.getColor(R.color.widget_off)
            val accent = context.getColor(R.color.accent)
            fun button(root: Int, icon: Int, label: Int, title: String, active: Boolean, action: PendingIntent) {
                val color = when { !m.connected -> dim; active -> accent; else -> text }
                v.setTextViewText(label, title)
                v.setTextColor(label, color)
                v.setInt(icon, "setColorFilter", color)
                // Offline, every button opens the app (which also retries the connection).
                v.setOnClickPendingIntent(root, if (m.connected) action else open)
            }
            button(R.id.w_clip, R.id.w_clip_icon, R.id.w_clip_label, "Clipboard", false,
                activity(context, 11, Intent(context, ClipboardActivity::class.java)))
            button(R.id.w_mirror, R.id.w_mirror_icon, R.id.w_mirror_label, if (m.mirroring) "Stop mirror" else "Mirror", m.mirroring,
                if (m.mirroring) broadcast(context, 12, ActionReceiver.ACTION_STOP_MIRROR)
                else activity(context, 13, Intent(context, MirrorConsentActivity::class.java)))
            button(R.id.w_find, R.id.w_find_icon, R.id.w_find_label, if (m.ringing) "Stop sound" else "Find Mac", m.ringing,
                broadcast(context, 14, if (m.ringing) ActionReceiver.ACTION_STOP_MAC_RING else ActionReceiver.ACTION_RING_MAC))
            return v
        }

        private fun status(m: Model): String = when {
            !m.paired -> "Not paired — tap to set up"
            m.connected && m.mirroring -> "Mirroring to Mac"
            m.connected && m.battery >= 0 -> "Connected · ${m.battery}%" + if (m.charging) " ⚡" else ""
            m.connected -> "Connected"
            m.waitingForWifi -> "Waiting for Wi-Fi"
            else -> "Not connected"
        }

        private fun activity(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(context, code, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun broadcast(context: Context, code: Int, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, code, ActionReceiver.intent(context, action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
