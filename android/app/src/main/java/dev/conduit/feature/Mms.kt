package dev.conduit.feature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Read-only access to MMS (picture messages and group texts) for the Mac's Messages view.
 * Text parts travel with the message list; images are only sent when the Mac asks for one
 * (`mms-image`), scaled down, so opening a conversation stays cheap.
 * Uses the same READ_SMS permission as SMS.
 */
object Mms {
    private val MMS_URI: Uri = Telephony.Mms.CONTENT_URI
    private val PART_URI: Uri = Uri.parse("content://mms/part")
    private const val ADDR_FROM = 137 // PduHeaders.FROM
    private const val ADDR_TO = 151   // PduHeaders.TO
    private const val MAX_IMAGE_SIDE = 1600

    /** One MMS row before its parts are read. `date` is in ms (the provider stores seconds). */
    data class Row(val id: Long, val threadId: Long, val date: Long, val outgoing: Boolean, val read: Boolean)

    private class Parts(val text: String, val attachments: JSONArray)

    private fun rows(context: Context, selection: String?, args: Array<String>?, limit: Int): List<Row> {
        val out = ArrayList<Row>()
        runCatching {
            context.contentResolver.query(
                MMS_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.READ),
                selection, args, "${Telephony.Mms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val box = c.getInt(3)
                    out += Row(
                        id = c.getLong(0), threadId = c.getLong(1), date = c.getLong(2) * 1000,
                        outgoing = box != Telephony.Mms.MESSAGE_BOX_INBOX,
                        read = c.getInt(4) == 1 || box != Telephony.Mms.MESSAGE_BOX_INBOX,
                    )
                }
            }
        }
        return out
    }

    /** The newest MMS per thread (newest threads first), for merging into the thread list. */
    fun latestPerThread(context: Context, maxThreads: Int = 80): List<Row> {
        val seen = LinkedHashMap<Long, Row>()
        for (r in rows(context, null, null, 1500)) {
            if (seen.size >= maxThreads) break
            if (!seen.containsKey(r.threadId)) seen[r.threadId] = r
        }
        return seen.values.toList()
    }

    /** The latest [limit] MMS of a thread as `sms-messages` entries (ids are negative so they never clash with SMS ids). */
    fun messagesFor(context: Context, threadId: Long, limit: Int = 60): List<JSONObject> =
        rows(context, "${Telephony.Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), limit).map { r ->
            val parts = parts(context, r.id)
            JSONObject()
                .put("id", -r.id)
                .put("address", if (r.outgoing) "" else address(context, r.id, false))
                .put("body", parts.text)
                .put("date", r.date)
                .put("outgoing", r.outgoing)
                .put("mms", true)
                .put("attachments", parts.attachments)
        }

    /** A short preview for the thread list ("Photo", or the message's text). */
    fun snippet(context: Context, row: Row): String {
        val p = parts(context, row.id)
        if (p.text.isNotBlank()) return p.text.take(140)
        val first = p.attachments.optJSONObject(0)?.optString("mime") ?: return "MMS"
        return when {
            first.startsWith("image/") -> "📷 Photo"
            first.startsWith("video/") -> "🎬 Video"
            first.startsWith("audio/") -> "🎤 Audio"
            first.contains("vcard", ignoreCase = true) -> "👤 Contact card"
            else -> "📎 Attachment"
        }
    }

    /** The other person's number: the sender for received messages, the first recipient for sent ones. */
    fun address(context: Context, mmsId: Long, outgoing: Boolean): String = runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            "${Telephony.Mms.Addr.TYPE} = ?", arrayOf((if (outgoing) ADDR_TO else ADDR_FROM).toString()), null
        )?.use { c ->
            var found = ""
            while (c.moveToNext()) {
                val a = c.getString(0) ?: continue
                if (a.isNotBlank() && a != "insert-address-token") { found = a; break }
            }
            found
        }
    }.getOrNull() ?: ""

    private fun parts(context: Context, mmsId: Long): Parts {
        val text = StringBuilder()
        val attachments = JSONArray()
        runCatching {
            context.contentResolver.query(
                PART_URI,
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT, Telephony.Mms.Part.NAME, Telephony.Mms.Part.FILENAME),
                "${Telephony.Mms.Part.MSG_ID} = ?", arrayOf(mmsId.toString()), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getLong(0)
                    val mime = (c.getString(1) ?: "").lowercase()
                    when {
                        mime == "application/smil" -> Unit // layout only
                        mime == "text/plain" -> {
                            val t = c.getString(2) ?: readText(context, partId)
                            if (t.isNotBlank()) { if (text.isNotEmpty()) text.append('\n'); text.append(t) }
                        }
                        else -> attachments.put(
                            JSONObject().put("part", partId).put("mime", mime)
                                .put("name", c.getString(4) ?: c.getString(3) ?: "")
                        )
                    }
                }
            }
        }
        return Parts(text.toString(), attachments)
    }

    private fun readText(context: Context, partId: Long): String = runCatching {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull() ?: ""

    /** An image part as a base64 JPEG no larger than 1600 px on its longest side, or "" if it can't be read. */
    fun image(context: Context, partId: Long): String = runCatching {
        val uri = Uri.parse("content://mms/part/$partId")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching ""
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching ""
        val longest = maxOf(decoded.width, decoded.height)
        val bitmap = if (longest > MAX_IMAGE_SIDE) {
            val f = MAX_IMAGE_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * f).toInt().coerceAtLeast(1), (decoded.height * f).toInt().coerceAtLeast(1), true)
        } else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull() ?: ""
}
