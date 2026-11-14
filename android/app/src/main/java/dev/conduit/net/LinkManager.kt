package dev.conduit.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dev.conduit.crypto.SecureChannel
import dev.conduit.feature.CallHandler
import dev.conduit.feature.CameraService
import dev.conduit.feature.ClipboardHandler
import dev.conduit.feature.FileTransfer
import dev.conduit.feature.Launcher
import dev.conduit.feature.MediaHandler
import dev.conduit.feature.MirrorService
import dev.conduit.feature.NotificationMirror
import dev.conduit.feature.RemoteControlService
import dev.conduit.feature.RingHandler
import dev.conduit.feature.SmsHandler
import dev.conduit.feature.StatusReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

data class LinkState(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val macName: String = "",
    val host: String = "",
    val detail: String = "Not paired",
    val macBattery: Int = -1,
    val macCharging: Boolean = false,
    val mirroring: Boolean = false,
    val camera: Boolean = false,
    val macRinging: Boolean = false,
)

/** One line in the shared activity feed ("Sent photo.jpg to Mac", "Link from Mac"…). */
data class ActivityItem(val kind: Kind, val text: String, val time: Long = System.currentTimeMillis()) {
    enum class Kind { CONNECTED, DISCONNECTED, FILE_OUT, FILE_IN, CLIPBOARD_OUT, CLIPBOARD_IN, LINK_OUT, LINK_IN, MIRROR, CAMERA, RING, ERROR }
}

@SuppressLint("StaticFieldLeak") // holds the Application context only
object LinkManager {
    private const val TAG = "Conduit"
    private const val MIN_BACKOFF = 3_000L
    private const val MAX_BACKOFF = 5 * 60_000L
    const val SERVICE_TYPE = "_conduit._tcp."

    lateinit var context: Context
        private set
    lateinit var pairing: PairingStore
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state

    private val _activity = MutableStateFlow<List<ActivityItem>>(emptyList())
    val activity: StateFlow<List<ActivityItem>> = _activity

    fun log(kind: ActivityItem.Kind, text: String) {
        _activity.update { (listOf(ActivityItem(kind, text)) + it).take(30) }
    }

    @Volatile private var control: Connection? = null
    @Volatile private var currentHost: String? = null
    @Volatile private var currentPort: Int = 0
    private var loopJob: Job? = null
    private val wake = KChannel<Unit>(KChannel.CONFLATED)
    private var multicastLock: WifiManager.MulticastLock? = null

    fun init(appContext: Context) {
        context = appContext.applicationContext
        pairing = PairingStore(context)
        val mac = pairing.load()
        _state.value = LinkState(paired = mac != null, macName = mac?.name ?: "", detail = if (mac != null) "Waiting to connect" else "Not paired")

        // Reconnect straight away when Wi-Fi comes back.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { kick() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (local != onLocalNetwork) { onLocalNetwork = local; if (local) kick() }
            }
            override fun onLost(network: Network) { onLocalNetwork = isOnLocalNetwork() }
        })
        onLocalNetwork = isOnLocalNetwork()
    }

    val isConnected get() = control?.isOpen == true

    // ---- Lifecycle ---------------------------------------------------------

    @Synchronized
    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        control?.close()
    }

    fun pair(mac: PairedMac) {
        pairing.save(mac)
        _state.update { it.copy(paired = true, macName = mac.name, detail = "Connecting…") }
        control?.close()
        wake.trySend(Unit)
        start()
    }

    fun unpair() {
        stop()
        pairing.clear()
        _state.value = LinkState()
    }

    /** Retry now (network change, unlock, app opened) and reset the back-off. */
    fun kick() { resetBackoff = true; wake.trySend(Unit) }

    @Volatile private var resetBackoff = false
    @Volatile private var onLocalNetwork = true

    /** The Mac is only reachable over Wi-Fi/Ethernet; on mobile data we sleep instead of retrying. */
    private fun isOnLocalNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun powerSave(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    /** For screenshot tests and previews only. */
    fun previewState(state: LinkState, activity: List<ActivityItem> = emptyList()) {
        _state.value = state
        _activity.value = activity
    }

    fun setMirroring(on: Boolean) {
        if (_state.value.mirroring != on) log(ActivityItem.Kind.MIRROR, if (on) "Screen mirroring started" else "Screen mirroring stopped")
        _state.update { it.copy(mirroring = on) }
    }

    fun setCamera(on: Boolean) {
        if (_state.value.camera != on) log(ActivityItem.Kind.CAMERA, if (on) "Webcam on" else "Webcam off")
        _state.update { it.copy(camera = on) }
    }

    // ---- Sending -----------------------------------------------------------

    /** Sends on the control channel; quietly drops the message if the Mac isn't connected. */
    fun send(type: String, build: JSONObject.() -> Unit = {}): Boolean {
        val c = control ?: return false
        return try {
            c.send(type, build); true
        } catch (e: Exception) {
            Log.w(TAG, "send $type failed: ${e.message}")
            false
        }
    }

    /** Opens a side channel (mirror, camera, file) to the currently connected Mac. */
    fun openChannel(channel: Int): Connection {
        val mac = pairing.load() ?: error("Not paired")
        val host = currentHost ?: error("Mac not connected")
        return Connection.open(host, currentPort, channel, mac.key, pairing.deviceId, deviceName())
    }

    fun deviceName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    // ---- Connection loop ---------------------------------------------------

    private suspend fun runLoop() {
        var backoff = MIN_BACKOFF
        while (scope.isActive) {
            val mac = pairing.load()
            if (mac == null) {
                _state.update { it.copy(paired = false, connected = false, detail = "Not paired") }
                return
            }
            if (resetBackoff) { backoff = MIN_BACKOFF; resetBackoff = false }

            // Off Wi-Fi the Mac can't be reached: sleep until the network changes instead of polling.
            if (!onLocalNetwork) {
                _state.update { it.copy(connected = false, detail = "Waiting for Wi-Fi") }
                wake.receive()
                continue
            }

            _state.update { it.copy(detail = "Connecting to ${mac.name}…") }
            // Cheapest first: the last address that worked. Only scan the network (mDNS) if that fails.
            var conn = tryConnect(mac, mac.hosts.map { it to mac.port })
            if (conn == null) {
                _state.update { it.copy(detail = "Looking for ${mac.name}…") }
                discover(mac.id, 3500)?.let { found -> conn = tryConnect(mac, listOf(found)) }
            }

            val c = conn
            if (c != null) {
                backoff = MIN_BACKOFF
                control = c
                _state.update { it.copy(connected = true, host = currentHost ?: "", detail = "Connected to ${mac.name}") }
                log(ActivityItem.Kind.CONNECTED, "Connected to ${mac.name}")
                onConnected()
                readLoop(c)
                control = null
                onDisconnected() // before clearing state: stop() checks the mirroring/camera flags
                _state.update { it.copy(connected = false, detail = "Disconnected — reconnecting") }
                log(ActivityItem.Kind.DISCONNECTED, "Lost connection to ${mac.name}")
            } else {
                _state.update { it.copy(connected = false, detail = "Can't reach ${mac.name}. Is it awake and on the same Wi-Fi?") }
            }
            withTimeoutOrNull(backoff) { wake.receive() }
            // Back off up to 5 minutes (longer in Battery Saver); unlocking the phone or a network change retries at once.
            backoff = (backoff * 2).coerceAtMost(if (powerSave()) 15 * 60_000L else MAX_BACKOFF)
        }
    }

    private fun tryConnect(mac: PairedMac, candidates: List<Pair<String, Int>>): Connection? {
        for ((host, port) in candidates.distinct()) {
            try {
                val c = Connection.open(host, port, Channel.CONTROL, mac.key, pairing.deviceId, deviceName())
                currentHost = host
                currentPort = port
                pairing.updateHost(host, port)
                return c
            } catch (e: Exception) {
                Log.i(TAG, "connect $host:$port failed: ${e.message}")
            }
        }
        return null
    }

    private suspend fun readLoop(conn: Connection) {
        // Keepalive: one tiny packet every 90 s (3 min in Battery Saver). The Mac doesn't ping back
        // on its own, so the radio isn't woken from both ends.
        val pinger = scope.launch {
            while (isActive && conn.isOpen) {
                delay(if (powerSave()) 180_000 else 90_000)
                runCatching { conn.send("ping") }
            }
        }
        try {
            while (conn.isOpen) {
                val frame = conn.receive()
                if (frame is SecureChannel.Frame.Json) {
                    val msg = JSONObject(frame.text)
                    scope.launch(serial) {
                        try {
                            dispatch(msg)
                        } catch (e: Exception) {
                            Log.e(TAG, "handler ${msg.optString("type")} failed", e)
                            send("error") { put("feature", msg.optString("type")); put("message", e.message ?: e.toString()) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "control closed: ${e.message}")
        } finally {
            pinger.cancel()
            conn.close()
        }
    }

    private fun onConnected() {
        send("hello") {
            put("deviceName", deviceName())
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("appVersion", "1.0")
        }
        StatusReporter.sendNow(context)
        NotificationMirror.instance?.sendAllActive()
        MediaHandler.resendAll()
    }

    private fun onDisconnected() {
        MirrorService.stop(context)
        CameraService.stop(context)
    }

    private fun dispatch(msg: JSONObject) {
        when (msg.optString("type")) {
            "ping" -> send("pong")
            "pong" -> Unit
            "hello" -> _state.update { it.copy(macName = msg.optString("deviceName", it.macName)) }
            "mac-status" -> _state.update { it.copy(macBattery = msg.optInt("battery", -1), macCharging = msg.optBoolean("charging")) }
            "notification-action" -> NotificationMirror.instance?.performAction(msg.getString("key"), msg.getInt("index"), msg.optString("text").ifEmpty { null })
            "notification-dismiss" -> NotificationMirror.instance?.dismiss(msg.getString("key"))
            "clipboard" -> ClipboardHandler.applyRemote(context, msg)
            "sms-list-threads" -> SmsHandler.sendThreads(context)
            "sms-list-messages" -> SmsHandler.sendMessages(context, msg.getLong("threadId"))
            "sms-send" -> SmsHandler.sendSms(context, msg.getString("address"), msg.getString("body"))
            "sms-mark-read" -> SmsHandler.markRead(context, msg.optLong("threadId"), msg.optString("address"), msg.optString("name"))
            "contact-photos" -> SmsHandler.sendContactPhotos(context, msg.optJSONArray("addresses"))
            "call-action" -> CallHandler.action(context, msg.getString("action"))
            "call-dial" -> Launcher.dial(context, msg.getString("number"))
            "media-control" -> MediaHandler.control(msg.getString("action"))
            "open-url" -> Launcher.openUrl(context, msg.getString("url"))
            "file-offer" -> FileTransfer.pull(context, msg.getString("id"), msg.getString("name"), msg.optLong("size"))
            "ring" -> if (msg.optBoolean("on", true)) RingHandler.start(context) else RingHandler.stop(context)
            "ring-state" -> _state.update { it.copy(macRinging = msg.optBoolean("ringing")) }
            "mirror-start" -> Launcher.startMirror(context, msg)
            "mirror-stop" -> MirrorService.stop(context)
            "camera-start" -> Launcher.startCamera(context, msg)
            "camera-stop" -> CameraService.stop(context)
            "input" -> RemoteControlService.handle(msg)
            else -> Log.d(TAG, "unknown message ${msg.optString("type")}")
        }
    }

    // ---- Bonjour discovery -------------------------------------------------

    private suspend fun discover(macId: String, timeoutMs: Long): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val wifi = context.getSystemService(WifiManager::class.java)
        multicastLock = multicastLock ?: wifi?.createMulticastLock("conduit-mdns")?.apply { setReferenceCounted(false) }
        multicastLock?.acquire()
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                            if (done.compareAndSet(false, true)) cont.resume(null)
                        }
                        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                        override fun onDiscoveryStarted(serviceType: String?) {}
                        override fun onDiscoveryStopped(serviceType: String?) {}
                        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                        override fun onServiceFound(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val id = resolved.attributes["id"]?.let { String(it) }
                                    @Suppress("DEPRECATION")
                                    val host = resolved.host?.hostAddress
                                    if (id == macId && host != null && done.compareAndSet(false, true)) {
                                        cont.resume(host to resolved.port)
                                    }
                                }
                            })
                        }
                    }
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                    cont.invokeOnCancellation { done.set(true) }
                    // Stop discovery once resolved.
                    scope.launch {
                        while (!done.get()) delay(100)
                        runCatching { nsd.stopServiceDiscovery(listener) }
                    }
                }
            }
        } finally {
            multicastLock?.release()
        }
    }
}
