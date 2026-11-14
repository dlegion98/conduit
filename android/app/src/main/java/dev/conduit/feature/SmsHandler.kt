package dev.conduit.feature

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import dev.conduit.net.LinkManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

/**
 * SMS threads and messages for the Mac's Messages view, sending, and new-message pushes.
 * RCS chats in Google Messages aren't in the SMS database; those still arrive as
 * mirrored notifications, and you can reply to them from the Mac.
 */
object SmsHandler {
    private const val ACTION_SENT = "dev.conduit.SMS_SENT"
    private var lastSeenDate = 0L
    private var observer: ContentObserver? = null

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ok = resultCode == Activity.RESULT_OK
            LinkManager.send("sms-sent") {
                put("address", intent.getStringExtra("address") ?: "")
                put("ok", ok)
                if (!ok) put("error", "Send failed (code $resultCode)")
            }
        }
    }

    private fun allowed(context: Context, perm: String) =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun register(context: Context) {
        context.registerReceiver(sentReceiver, IntentFilter(ACTION_SENT), Context.RECEIVER_NOT_EXPORTED)
        if (!allowed(context, Manifest.permission.READ_SMS)) return
        lastSeenDate = latestInbox(context)?.optLong("date") ?: System.currentTimeMillis()
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                pending?.let(handler::removeCallbacks)
                pending = Runnable { LinkManager.scope.launch { checkNew(context) } }.also { handler.postDelayed(it, 600) }
            }
        }.also { context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, it) }
    }

    fun unregister(context: Context) {
        runCatching { context.unregisterReceiver(sentReceiver) }
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun checkNew(context: Context) {
        val latest = latestInbox(context) ?: return
        val date = latest.optLong("date")
        if (date <= lastSeenDate) return
        lastSeenDate = date
        LinkManager.send("sms-received") {
            put("threadId", latest.getLong("threadId"))
            put("address", latest.getString("address"))
            put("name", Contacts.nameFor(context, latest.getString("address")))
            put("body", latest.getString("body"))
            put("date", date)
        }
    }

    private fun latestInbox(context: Context): JSONObject? = runCatching {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            if (!c.moveToFirst()) null
            else JSONObject().put("threadId", c.getLong(0)).put("address", c.getString(1) ?: "")
                .put("body", c.getString(2) ?: "").put("date", c.getLong(3))
        }
    }.getOrNull()

    fun sendThreads(context: Context) {
        if (!allowed(context, Manifest.permission.READ_SMS)) {
            LinkManager.send("error") { put("feature", "sms"); put("message", "Allow SMS permission in Conduit on your phone") }
            return
        }
        val seen = LinkedHashMap<Long, JSONObject>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            var rows = 0
            while (c.moveToNext() && seen.size < 80 && rows++ < 5000) {
                val thread = c.getLong(0)
                if (seen.containsKey(thread)) continue
                val address = c.getString(1) ?: ""
                seen[thread] = JSONObject()
                    .put("threadId", thread)
                    .put("address", address)
                    .put("name", Contacts.nameFor(context, address))
                    .put("snippet", (c.getString(2) ?: "").take(140))
                    .put("date", c.getLong(3))
                    .put("read", c.getInt(4) == 1 || c.getInt(5) != Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        LinkManager.send("sms-threads") { put("threads", JSONArray(seen.values.toList())) }
    }

    fun sendMessages(context: Context, threadId: Long) {
        if (!allowed(context, Manifest.permission.READ_SMS)) return
        val list = ArrayList<JSONObject>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            while (c.moveToNext() && list.size < 200) {
                list += JSONObject()
                    .put("id", c.getLong(0))
                    .put("address", c.getString(1) ?: "")
                    .put("body", c.getString(2) ?: "")
                    .put("date", c.getLong(3))
                    .put("outgoing", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        list.reverse()
        LinkManager.send("sms-messages") {
            put("threadId", threadId)
            put("messages", JSONArray(list))
        }
    }

    /** Answers the Mac's `contact-photos` request: one small JPEG per address ("" when there's no photo). */
    fun sendContactPhotos(context: Context, addresses: JSONArray?) {
        if (addresses == null) return
        val photos = JSONArray()
        for (i in 0 until minOf(addresses.length(), 40)) {
            val address = addresses.optString(i)
            if (address.isBlank()) continue
            photos.put(JSONObject().put("address", address).put("photo", Contacts.photoFor(context, address)))
        }
        LinkManager.send("contact-photos") { put("photos", photos) }
    }

    /**
     * A thread was read on the Mac. Android only lets the default SMS app change the read flag,
     * so try that quietly, then clear the conversation's notification from the messaging app
     * (which also clears the mirrored copy on the Mac).
     */
    fun markRead(context: Context, threadId: Long, address: String, name: String) {
        runCatching {
            val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
            context.contentResolver.update(
                Telephony.Sms.Inbox.CONTENT_URI, values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString())
            )
        }
        val packages = setOfNotNull(
            runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull(),
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
        )
        val names = listOf(name, address, Contacts.nameFor(context, address)).filter { it.isNotBlank() }
        NotificationMirror.instance?.dismissConversation(packages, names)
    }

    fun sendSms(context: Context, address: String, body: String) {
        if (!allowed(context, Manifest.permission.SEND_SMS)) {
            LinkManager.send("sms-sent") { put("address", address); put("ok", false); put("error", "Allow SMS permission in Conduit on your phone") }
            return
        }
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        val sentIntent = PendingIntent.getBroadcast(
            context, address.hashCode(),
            Intent(ACTION_SENT).setPackage(context.packageName).putExtra("address", address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sent = ArrayList<PendingIntent?>(parts.size).apply {
            repeat(parts.size) { i -> add(if (i == parts.size - 1) sentIntent else null) }
        }
        sms.sendMultipartTextMessage(address, null, parts, sent, null)
    }
}

