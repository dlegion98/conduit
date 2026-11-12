package dev.conduit.feature

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.net.Channel
import dev.conduit.net.Connection
import dev.conduit.net.LinkManager
import dev.conduit.service.ActionReceiver
import kotlinx.coroutines.launch

/**
 * Streams the phone screen to the Mac. Handles fold/unfold and rotation by
 * re-creating the encoder and resizing the virtual display (Android 14+ allows
 * only one virtual display per projection, so we resize instead of re-creating it).
 */
class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var streamer: VideoStreamer? = null
    private var conn: Connection? = null
    private val worker = HandlerThread("conduit-mirror").apply { start() }
    private val handler = Handler(worker.looper)
    private var maxSize = 1920
    private var bitrate = 8_000_000
    private var fps = 60

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (w, h) = targetSize()
            val s = streamer ?: return
            if (w != s.width || h != s.height) reconfigure()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra("data")
        maxSize = intent?.getIntExtra("maxSize", 1920) ?: 1920
        bitrate = intent?.getIntExtra("bitrate", 8_000_000) ?: 8_000_000
        fps = intent?.getIntExtra("fps", 60) ?: 60

        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (data == null || resultCode != Activity.RESULT_OK) {
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        LinkManager.scope.launch {
            try {
                val c = LinkManager.openChannel(Channel.MIRROR)
                conn = c
                handler.post { startCapture(c) }
                // Watch for the Mac closing the stream.
                while (c.isOpen) c.receive()
            } catch (e: Exception) {
                Log.i("Conduit", "mirror channel closed: ${e.message}")
            } finally {
                handler.post { stopSelf() }
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        LinkManager.setMirroring(true)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return DisplayMetrics().also { d.getRealMetrics(it) }
    }

    private fun targetSize(): Pair<Int, Int> {
        val m = realMetrics()
        return VideoStreamer.fit(m.widthPixels, m.heightPixels, maxSize)
    }

    private fun startCapture(c: Connection) {
        val mp = projection ?: return
        val (w, h) = targetSize()
        val s = VideoStreamer(c, w, h, bitrate, fps) { handler.post { stopSelf() } }
        streamer = s
        display = mp.createVirtualDisplay(
            "conduit-mirror", w, h, realMetrics().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s.inputSurface, null, handler
        )
    }

    private fun reconfigure() {
        val c = conn ?: return
        val vd = display ?: return
        val old = streamer
        val (w, h) = targetSize()
        try {
            val s = VideoStreamer(c, w, h, bitrate, fps) { handler.post { stopSelf() } }
            streamer = s
            vd.setSurface(s.inputSurface)
            vd.resize(w, h, realMetrics().densityDpi)
            old?.release()
        } catch (e: Exception) {
            Log.w("Conduit", "mirror reconfigure failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        runCatching { display?.release() }
        streamer?.release()
        conn?.close()
        runCatching { projection?.stop() }
        worker.quitSafely()
        LinkManager.setMirroring(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getBroadcast(this, 2, ActionReceiver.intent(this, ActionReceiver.ACTION_STOP_MIRROR), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ConduitApp.CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Mirroring to your Mac")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 3
        private const val ACTION_STOP = "dev.conduit.MIRROR_STOP"

        fun start(context: Context, resultCode: Int, data: Intent, maxSize: Int, bitrate: Int, fps: Int) {
            context.startForegroundService(
                Intent(context, MirrorService::class.java)
                    .putExtra("resultCode", resultCode).putExtra("data", data)
                    .putExtra("maxSize", maxSize).putExtra("bitrate", bitrate).putExtra("fps", fps)
            )
        }

        fun stop(context: Context) {
            if (!LinkManager.state.value.mirroring) return
            runCatching { context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
