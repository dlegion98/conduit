package dev.conduit.feature

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import android.util.LruCache
import dev.conduit.net.LinkManager
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * Mirrors notifications to the Mac and performs reply, action and dismiss requests
 * that come back. Also gives MediaHandler access to media sessions.
 */
class NotificationMirror : NotificationListenerService() {

    private val iconCache = LruCache<String, String>(64)
    private val iconsSent = java.util.Collections.synchronizedSet(HashSet<String>())

    override fun onListenerConnected() {
        instance = this
        MediaHandler.attach(this)
        LinkManager.start()
        sendAllActive()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        MediaHandler.detach()
    }

    private val lastSent = LruCache<String, Int>(200)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldMirror(sbn)) return
        if (dev.conduit.net.Prefs.onlyWhenLocked(this) && phoneInUse()) return
        // Apps often re-post the same notification; only send when what you'd see changed.
        val e = sbn.notification.extras
        val sig = listOf(e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT), sbn.notification.actions?.size).hashCode()
        if (lastSent.get(sbn.key) == sig) return
        lastSent.put(sbn.key, sig)
        send(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        lastSent.remove(sbn.key)
        LinkManager.send("notification-removed") { put("key", sbn.key) }
    }

    private fun phoneInUse(): Boolean {
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        val power = getSystemService(android.os.PowerManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    fun sendAllActive() {
        if (!LinkManager.isConnected) return
        iconsSent.clear() // new connection: the Mac may have restarted
        runCatching { activeNotifications }.getOrNull()?.filter(::shouldMirror)?.forEach(::send)
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return false
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        return !(title.isNullOrBlank() && text.isNullOrBlank())
    }

    private fun send(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = bestText(extras)
        val actions = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            if (actions.length() >= 3) return@forEachIndexed
            val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
            actions.put(org.json.JSONObject().put("index", i).put("title", a.title?.toString() ?: "Action").put("reply", reply))
        }
        LinkManager.send("notification") {
            put("key", sbn.key)
            put("pkg", sbn.packageName)
            put("app", appLabel(sbn.packageName))
            put("title", title.toString())
            put("text", text)
            put("time", sbn.postTime)
            put("actions", actions)
            // The Mac caches icons per app, so each icon crosses the network once per connection.
            if (iconsSent.add(sbn.packageName)) iconFor(sbn.packageName)?.let { put("icon", it) }
        }
    }

    /** Messaging apps put the latest messages in EXTRA_MESSAGES; prefer the last few lines. */
    private fun bestText(extras: Bundle): String {
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val base = extras.getCharSequence(Notification.EXTRA_TEXT)
        return when {
            !big.isNullOrBlank() -> big.toString()
            !lines.isNullOrEmpty() -> lines.takeLast(4).joinToString("\n")
            else -> base?.toString() ?: ""
        }.take(2000)
    }

    fun performAction(key: String, index: Int, text: String?) {
        val sbn = runCatching { getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return
        val action = sbn.notification.actions?.getOrNull(index) ?: return
        try {
            val inputs = action.remoteInputs
            if (text != null && inputs != null && inputs.isNotEmpty()) {
                val fill = Intent()
                val results = Bundle()
                inputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(inputs, fill, results)
                action.actionIntent.send(this, 0, fill)
            } else {
                action.actionIntent.send()
            }
        } catch (e: Exception) {
            Log.w("Conduit", "notification action failed", e)
            LinkManager.send("error") { put("feature", "notification"); put("message", "That action is no longer available") }
        }
    }

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun iconFor(pkg: String): String? {
        iconCache.get(pkg)?.let { return it }
        val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val encoded = encodePng(drawable, 96) ?: return null
        iconCache.put(pkg, encoded)
        return encoded
    }

    companion object {
        @Volatile var instance: NotificationMirror? = null
            private set

        fun encodePng(d: Drawable, size: Int): String? = runCatching {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()

        fun isEnabled(context: android.content.Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
