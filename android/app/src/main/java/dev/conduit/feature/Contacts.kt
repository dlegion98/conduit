package dev.conduit.feature

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache

object Contacts {
    private val cache = LruCache<String, String>(500)

    fun nameFor(context: Context, number: String?): String {
        if (number.isNullOrBlank()) return ""
        cache.get(number)?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: ""
        cache.put(number, name)
        return name
    }
}
