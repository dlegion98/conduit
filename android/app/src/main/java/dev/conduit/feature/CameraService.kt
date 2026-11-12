package dev.conduit.feature

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Size
import dev.conduit.ConduitApp
import dev.conduit.R
import dev.conduit.net.Channel
import dev.conduit.net.Connection
import dev.conduit.net.LinkManager
import dev.conduit.service.ActionReceiver
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** Streams a phone camera to the Mac, where the Conduit Camera extension shows it to any app. */
class CameraService : Service() {
    private val worker = HandlerThread("conduit-camera").apply { start() }
    private val handler = Handler(worker.looper)
    private val executor = Executor { handler.post(it) }
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamer: VideoStreamer? = null
    private var conn: Connection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        val facing = intent?.getStringExtra("facing") ?: "back"
        val width = intent?.getIntExtra("width", 1920) ?: 1920
        val height = intent?.getIntExtra("height", 1080) ?: 1080
        val fps = intent?.getIntExtra("fps", 30) ?: 30

        // A second camera-start (e.g. switching lenses) restarts the capture.
        handler.post { closeCamera() }
        LinkManager.scope.launch {
            try {
                conn?.close()
                val c = LinkManager.openChannel(Channel.CAMERA)
                conn = c
                handler.post { openCamera(c, facing, width, height, fps) }
                while (c.isOpen) c.receive()
            } catch (e: Exception) {
                Log.i("Conduit", "camera channel closed: ${e.message}")
            } finally {
                handler.post { if (conn == null || conn?.isOpen == false) stopSelf() }
            }
        }
        LinkManager.setCamera(true)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(c: Connection, facing: String, w: Int, h: Int, fps: Int) {
        val cm = getSystemService(CameraManager::class.java)
        val wanted = if (facing == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: cm.cameraIdList.first()
        val chars = cm.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(MediaCodec::class.java)
            .filter { it.width <= 1920 && it.height <= 1080 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - w * h) + if (it.width * 9 == it.height * 16) 0 else 100_000 }
            ?: Size(1280, 720)
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val range = ranges.firstOrNull { it.lower == fps && it.upper == fps } ?: ranges.firstOrNull { it.upper == fps } ?: Range(15, 30)

        val s = VideoStreamer(c, size.width, size.height, 6_000_000, fps) { handler.post { stopSelf() } }
        streamer = s

        cm.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(s.inputSurface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(sess: CameraCaptureSession) {
                            session = sess
                            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(s.inputSurface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                            }.build()
                            sess.setRepeatingRequest(req, null, handler)
                        }
                        override fun onConfigureFailed(sess: CameraCaptureSession) { stopSelf() }
                    }
                )
                device.createCaptureSession(config)
            }
            override fun onDisconnected(device: CameraDevice) { device.close(); stopSelf() }
            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                LinkManager.send("error") { put("feature", "camera"); put("message", "Camera error $error — another app may be using it") }
                stopSelf()
            }
        })
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        streamer?.release()
        session = null; camera = null; streamer = null
    }

    override fun onDestroy() {
        closeCamera()
        conn?.close()
        worker.quitSafely()
        LinkManager.setCamera(false)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getBroadcast(this, 3, ActionReceiver.intent(this, ActionReceiver.ACTION_STOP_CAMERA), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, ConduitApp.CH_LINK)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Camera in use by your Mac")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4
        private const val ACTION_STOP = "dev.conduit.CAMERA_STOP"

        fun start(context: Context, facing: String, width: Int, height: Int, fps: Int) {
            context.startForegroundService(
                Intent(context, CameraService::class.java)
                    .putExtra("facing", facing).putExtra("width", width).putExtra("height", height).putExtra("fps", fps)
            )
        }

        fun stop(context: Context) {
            if (!LinkManager.state.value.camera) return
            runCatching { context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
