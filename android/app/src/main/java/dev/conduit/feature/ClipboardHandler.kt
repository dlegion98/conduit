package dev.conduit.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import dev.conduit.net.LinkManager
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Mac → phone is automatic. Phone → Mac needs the app in focus (Android 10+ rule), so it
 * runs from ClipboardActivity: the notification button, the Quick Settings tile, or opening the app.
 */
object ClipboardHandler {
    @Volatile private var lastHash: String? = null

    fun applyRemote(context: Context, msg: JSONObject) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = msg.optString("text").ifEmpty { null }
        val image = msg.optString("image").ifEmpty { null }
        val clip = when {
            text != null -> {
                lastHash = hash(text)
                ClipData.newPlainText("From Mac", text)
            }
            image != null -> {
                val bytes = Base64.decode(image, Base64.NO_WRAP)
                lastHash = hash(image)
                val uri = saveImage(context, bytes) ?: return
                ClipData.newUri(context.contentResolver, "From Mac", uri)
            }
            else -> return
        }
        // Don't show "copied" previews of passwords etc. in the system overlay.
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", msg.optBoolean("sensitive"))
            }
        }
        cm.setPrimaryClip(clip)
    }

    /** Call while an activity of ours has focus. Returns a short status for a toast. */
    fun sendLocal(context: Context): String {
        if (!LinkManager.isConnected) return "Mac not connected"
        val cm = context.getSystemService(ClipboardManager::class.java)
        val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return "Clipboard is empty"
        val uri = item.uri
        if (uri != null && (context.contentResolver.getType(uri)?.startsWith("image/") == true)) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "Couldn't read image"
            if (bytes.size > 8 * 1024 * 1024) return "Image too large for clipboard — use Share instead"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            lastHash = hash(b64)
            LinkManager.send("clipboard") { put("image", b64) }
            return "Image sent to Mac"
        }
        val text = item.coerceToText(context)?.toString() ?: return "Nothing to send"
        if (text.isEmpty()) return "Clipboard is empty"
        lastHash = hash(text)
        LinkManager.send("clipboard") { put("text", text) }
        return "Clipboard sent to Mac"
    }

    private fun saveImage(context: Context, bytes: ByteArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "mac-clipboard-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Conduit")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        return uri
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
