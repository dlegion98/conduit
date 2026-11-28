package dev.conduit.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Base64
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object Contacts {
    private val cache = LruCache<String, String>(500)
    private val photoCache = LruCache<String, String>(120)

    private fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun nameFor(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return ""
        cache.get(number)?.let { return it }
        if (!allowed(context)) return ""
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: ""
        cache.put(number, name)
        return name
    }

    /**
     * The contact's thumbnail as a small base64 JPEG (96 px, a few KB), or "" when there is none.
     * Only sent when the Mac asks, and the Mac caches the answer, so each photo crosses once.
     */
    fun photoFor(context: Context, number: String?): String {
        if (number.isNullOrBlank() || !allowed(context)) return ""
        photoCache.get(number)?.let { return it }
        val lookup = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val encoded = runCatching {
            val thumb = context.contentResolver.query(lookup, arrayOf(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: return@runCatching ""
            val src = context.contentResolver.openInputStream(Uri.parse(thumb))?.use { BitmapFactory.decodeStream(it) }
                ?: return@runCatching ""
            val scaled = Bitmap.createScaledBitmap(src, 96, 96, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrNull() ?: ""
        photoCache.put(number, encoded)
        return encoded
    }

    /**
     * Contacts whose name or number matches [query], for the Mac's New Message "To" field:
     * up to [limit] entries of {name, number, label}, one per phone number.
     */
    fun search(context: Context, query: String, limit: Int = 12): List<JSONObject> {
        val q = query.trim()
        if (q.isEmpty() || !allowed(context)) return emptyList()
        val out = ArrayList<JSONObject>()
        val numbers = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(q)),
                arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(1)?.trim().orEmpty()
                    if (number.isEmpty() || !numbers.add(number.filter { it.isDigit() || it == '+' })) continue
                    val label = Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    out += JSONObject().put("name", c.getString(0) ?: "").put("number", number).put("label", label)
                }
            }
        }
        return out
    }
}
