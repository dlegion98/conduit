package dev.conduit.feature

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.net.LinkManager
import dev.conduit.service.ActionReceiver
import dev.conduit.ui.RingActivity

/** "Find my phone": rings at full alarm volume, even on silent, until stopped. */
object RingHandler {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private const val NOTIF_ID = 77

    @Synchronized
    fun start(context: Context) {
        if (player != null) return
        val audio = context.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        )
        val stop = PendingIntent.getBroadcast(context, 0, ActionReceiver.intent(context, ActionReceiver.ACTION_STOP_RING), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 0, Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, ConduitApp.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Your Mac is looking for this phone")
            .setContentText("Tap Stop to silence")
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        Launcher.launch(context, Intent(context, RingActivity::class.java), "Find my phone", "Tap to stop")
        LinkManager.send("ring-state") { put("ringing", true) }
    }

    @Synchronized
    fun stop(context: Context) {
        val p = player ?: return
        runCatching { p.stop(); p.release() }
        player = null
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        if (savedVolume >= 0) {
            context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        }
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        LinkManager.send("ring-state") { put("ringing", false) }
    }

    val isRinging get() = player != null
}
