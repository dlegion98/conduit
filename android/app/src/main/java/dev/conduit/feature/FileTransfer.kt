package dev.conduit.feature

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.crypto.SecureChannel
import dev.conduit.net.Channel
import dev.conduit.net.LinkManager
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Files both ways over a dedicated encrypted channel per transfer. Received files go to Downloads/Conduit. */
object FileTransfer {
    private const val CHUNK = 256 * 1024

    /** Keep the calling activity open until the returned job finishes: share-sheet URI grants end with it. */
    fun push(context: Context, uris: List<Uri>): kotlinx.coroutines.Job =
        LinkManager.scope.launch {
            for (uri in uris) {
                val (name, size) = describe(context, uri)
                val id = UUID.randomUUID().toString()
                val notifId = id.hashCode()
                try {
                    val conn = LinkManager.openChannel(Channel.FILE)
                    conn.send("file-push") { put("id", id); put("name", name); put("size", size) }
                    var sent = 0L
                    var lastPct = -1
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            conn.sendBinary(buf, 0, n)
                            sent += n
                            val pct = if (size > 0) (sent * 100 / size).toInt() else 0
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct
                                progress(context, notifId, "Sending $name", pct)
                            }
                        }
                    } ?: error("Can't read $name")
                    conn.send("file-end") { put("id", id) }
                    val done = conn.receive()
                    conn.close()
                    if (done is SecureChannel.Frame.Json && JSONObject(done.text).optString("type") == "file-done") {
                        finish(context, notifId, "Sent to Mac", name, null)
                        LinkManager.log(dev.conduit.net.ActivityItem.Kind.FILE_OUT, "Sent $name")
                    } else error("Mac didn't confirm")
                } catch (e: Exception) {
                    Log.w("Conduit", "push failed", e)
                    finish(context, notifId, "Couldn't send $name", e.message ?: "Error", null)
                    LinkManager.log(dev.conduit.net.ActivityItem.Kind.ERROR, "Couldn't send $name")
                }
            }
        }

    fun pull(context: Context, id: String, name: String, size: Long) {
        LinkManager.scope.launch {
            val notifId = id.hashCode()
            var target: Uri? = null
            try {
                val conn = LinkManager.openChannel(Channel.FILE)
                conn.send("file-pull") { put("id", id) }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Conduit")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Can't create file")
                var got = 0L
                var lastPct = -1
                resolver.openOutputStream(target)!!.use { out ->
                    loop@ while (true) {
                        when (val f = conn.receive()) {
                            is SecureChannel.Frame.Binary -> {
                                out.write(f.data)
                                got += f.data.size
                                val pct = if (size > 0) (got * 100 / size).toInt() else 0
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    progress(context, notifId, "Receiving $name", pct)
                                }
                            }
                            is SecureChannel.Frame.Json -> {
                                val t = JSONObject(f.text).optString("type")
                                if (t == "file-end") break@loop
                                if (t == "file-error") error(JSONObject(f.text).optString("message"))
                            }
                        }
                    }
                }
                conn.close()
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val open = Intent(Intent.ACTION_VIEW).setDataAndType(target, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                finish(context, notifId, "Received from Mac", name, open)
                LinkManager.log(dev.conduit.net.ActivityItem.Kind.FILE_IN, "Received $name")
            } catch (e: Exception) {
                Log.w("Conduit", "pull failed", e)
                target?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                finish(context, notifId, "Couldn't receive $name", e.message ?: "Error", null)
            }
        }
    }

    private fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0)?.let { n -> name = n }
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        return name to size
    }

    private fun progress(context: Context, id: Int, title: String, pct: Int) {
        val n = Notification.Builder(context, ConduitApp.CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun finish(context: Context, id: Int, title: String, text: String, open: Intent?) {
        val b = Notification.Builder(context, ConduitApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        open?.let { b.setContentIntent(PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE)) }
        context.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}
