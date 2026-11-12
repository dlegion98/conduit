package dev.conduit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.conduit.feature.NotificationMirror
import dev.conduit.feature.RemoteControlService
import kotlinx.coroutines.delay

/** A feature of the link, with whatever the phone needs to allow for it to work. */
data class Feature(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val group: String,
    val ready: Boolean,
    val setupHint: String? = null,
    val fix: (() -> Unit)? = null,
)

private fun Context.has(vararg perms: String) = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

private fun Context.openSettings(action: String, data: Uri? = null) =
    runCatching { startActivity(Intent(action).apply { data?.let { setData(it) }; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }

/** Live list of features and their setup state; re-checks when you come back from Settings. */
@Composable
fun rememberFeatures(): List<Feature> {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }

    return remember(tick) {
        val pkg = Uri.parse("package:${context.packageName}")
        val power = context.getSystemService(PowerManager::class.java)
        val sms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS)
        val calls = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
        val notifAccess = NotificationMirror.isEnabled(context)
        listOf(
            Feature("notifications", "Notifications", "See and reply to phone notifications on your Mac", Icons.Default.Notifications, "On your Mac",
                notifAccess && context.has(Manifest.permission.POST_NOTIFICATIONS), "Allow notification access",
            ) {
                if (!context.has(Manifest.permission.POST_NOTIFICATIONS)) request.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            Feature("messages", "Messages", "Read and send texts from your Mac", Icons.Default.Sms, "On your Mac",
                context.has(*sms), "Allow SMS and contacts") { request.launch(sms) },
            Feature("calls", "Calls", "Answer, decline and place calls from your Mac", Icons.Default.Call, "On your Mac",
                context.has(*calls), "Allow phone access") { request.launch(calls) },
            Feature("media", "Now playing", "Control music and videos playing on the phone from the Mac menu bar", Icons.Default.MusicNote, "On your Mac",
                notifAccess, "Uses notification access") { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            Feature("mirror", "Screen mirroring", "Show and control this phone in a window on your Mac", Icons.Default.Cast, "Screen & camera",
                RemoteControlService.instance != null, "Turn on remote control (Accessibility)",
            ) { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            Feature("camera", "Webcam", "Use this phone's cameras as “Conduit Camera” on your Mac", Icons.Default.Videocam, "Screen & camera",
                context.has(Manifest.permission.CAMERA), "Allow camera") { request.launch(arrayOf(Manifest.permission.CAMERA)) },
            Feature("files", "Files & links", "Share anything to your Mac, and receive files in Downloads/Conduit", Icons.Default.SwapHoriz, "Share", true),
            Feature("clipboard", "Clipboard", "Copy on your Mac, paste here. Send the other way with one tap", Icons.Default.ContentPaste, "Share", true),
            Feature("remote", "Mac remote", "Trackpad, keyboard and media keys for your Mac", Icons.Default.TouchApp, "Share", true),
            Feature("background", "Stay connected", "Keep the link alive with the screen off", Icons.Default.BatteryChargingFull, "Reliability",
                power.isIgnoringBatteryOptimizations(context.packageName), "Allow background use",
            ) {
                @Suppress("BatteryLife")
                context.openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            },
        )
    }
}
