package dev.conduit.feature

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import dev.conduit.net.LinkManager
import java.io.ByteArrayOutputStream

/**
 * Sends what's playing on the phone (any app with a media session) to the Mac,
 * and handles play/pause/next/previous coming back. Needs notification access.
 */
object MediaHandler {
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastArtKey: String? = null
    private var lastSig: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = sendCurrent()
        override fun onMetadataChanged(metadata: MediaMetadata?) = sendCurrent()
        override fun onSessionDestroyed() {
            controller = null
            refresh()
        }
    }

    fun attach(context: Context) {
        val mgr = context.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(context, NotificationMirror::class.java)
        manager = mgr
        component = comp
        main.post {
            runCatching {
                mgr.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
                pick(mgr.getActiveSessions(comp))
            }
        }
    }

    fun detach() {
        main.post {
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            controller?.unregisterCallback(callback)
            controller = null
        }
    }

    private fun refresh() {
        val mgr = manager ?: return
        val comp = component ?: return
        runCatching { pick(mgr.getActiveSessions(comp)) }
    }

    /** Prefer the session that's playing; otherwise the most recent one. */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
            lastArtKey = null
        }
        sendCurrent()
    }

    /** Called on (re)connect: resend everything. */
    fun resendAll() { lastSig = null; lastArtKey = null; sendCurrent() }

    fun sendCurrent() {
        val c = controller
        if (c == null) {
            if (lastSig != "none") LinkManager.send("media") { put("active", false) }
            lastSig = "none"
            return
        }
        val md = c.metadata
        val state = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val artKey = "${c.packageName}|$title|$artist"
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val sig = "$artKey|$playing"
        if (sig == lastSig) return // position ticks and repeated callbacks: nothing new to show
        lastSig = sig
        LinkManager.send("media") {
            put("active", true)
            put("app", appLabel(c.packageName))
            put("title", title)
            put("artist", artist)
            put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            put("playing", state?.state == PlaybackState.STATE_PLAYING)
            put("positionMs", state?.position ?: 0L)
            put("durationMs", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
            // Only send artwork when the track changes, to keep updates small.
            val saver = LinkManager.context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
            if (artKey != lastArtKey && !saver) {
                val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                art?.let { put("art", jpeg(it)) }
            }
        }
        lastArtKey = artKey
    }

    fun control(action: String) {
        main.post {
            val t = controller?.transportControls ?: return@post
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = LinkManager.context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun jpeg(bmp: Bitmap): String {
        val scaled = if (bmp.width > 300) Bitmap.createScaledBitmap(bmp, 300, 300 * bmp.height / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
