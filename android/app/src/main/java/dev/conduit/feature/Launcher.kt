package dev.conduit.feature

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.ui.CameraLaunchActivity
import dev.conduit.ui.MirrorConsentActivity
import org.json.JSONObject

/**
 * Android blocks most apps from opening screens while in the background. Two routes work:
 * the accessibility service (allowed to start activities) or a notification the user taps.
 */
object Launcher {
    @Volatile var appInForeground = false

    fun launch(context: Context, intent: Intent, fallbackTitle: String, fallbackText: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a11y = RemoteControlService.instance
        val started = runCatching {
            when {
                appInForeground -> context.startActivity(intent)
                a11y != null -> a11y.startActivity(intent)
                else -> error("background")
            }
        }.isSuccess
        if (!started) notifyTap(context, intent, fallbackTitle, fallbackText)
    }

    fun notifyTap(context: Context, intent: Intent, title: String, text: String) {
        val pi = PendingIntent.getActivity(
            context, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, ConduitApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(title.hashCode(), n)
    }

    fun openUrl(context: Context, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https", "mailto", "tel", "geo")) return
        launch(context, Intent(Intent.ACTION_VIEW, uri), "Link from your Mac", url)
        dev.conduit.net.LinkManager.log(dev.conduit.net.ActivityItem.Kind.LINK_IN, "Opened ${uri.host ?: url} from Mac")
    }

    fun dial(context: Context, number: String) {
        val canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        launch(context, intent, "Call from your Mac", "Tap to call $number")
    }

    /** Sends a link to open in the Mac's browser. */
    fun openOnMac(url: String): Boolean {
        val ok = dev.conduit.net.LinkManager.send("open-url") { put("url", url) }
        if (ok) dev.conduit.net.LinkManager.log(dev.conduit.net.ActivityItem.Kind.LINK_OUT, "Opened ${Uri.parse(url).host ?: url} on Mac")
        return ok
    }

    fun startMirror(context: Context, msg: JSONObject) {
        val intent = Intent(context, MirrorConsentActivity::class.java)
            .putExtra("maxSize", msg.optInt("maxSize", 1920))
            .putExtra("bitrate", msg.optInt("bitrate", 8_000_000))
            .putExtra("fps", msg.optInt("fps", 60))
        launch(context, intent, "Mirror to your Mac", "Tap to allow screen sharing")
    }

    fun startCamera(context: Context, msg: JSONObject) {
        val intent = Intent(context, CameraLaunchActivity::class.java)
            .putExtra("facing", msg.optString("facing", "back"))
            .putExtra("width", msg.optInt("width", 1920))
            .putExtra("height", msg.optInt("height", 1080))
            .putExtra("fps", msg.optInt("fps", 30))
        launch(context, intent, "Use as Mac webcam", "Tap to start the camera")
    }
}
