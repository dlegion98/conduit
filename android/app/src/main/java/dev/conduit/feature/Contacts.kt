package dev.conduit.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.LruCache
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
}
