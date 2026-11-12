package dev.conduit.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.conduit.feature.CameraService
import dev.conduit.feature.FileTransfer
import dev.conduit.feature.Launcher
import dev.conduit.feature.MirrorService
import dev.conduit.net.LinkManager
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitBlue
import kotlinx.coroutines.delay
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onOpenRemote: () -> Unit, onOpenFeatures: () -> Unit, wide: Boolean) {
    val context = LocalContext.current
    val state by LinkManager.state.collectAsStateWithLifecycle()
    val activity by LinkManager.activity.collectAsStateWithLifecycle()
    val features = rememberFeatures()
    val battery = rememberPhoneBattery()
    var linkDialog by remember { mutableStateOf(false) }
    var lens by remember { mutableStateOf(context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("lens", "back") ?: "back") }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            FileTransfer.push(context.applicationContext, uris)
            Toast.makeText(context, "Sending ${uris.size} to ${state.macName}…", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleMirror() {
        if (state.mirroring) MirrorService.stop(context) else Launcher.startMirror(context, JSONObject())
    }
    fun toggleCamera() {
        if (state.camera) CameraService.stop(context) else Launcher.startCamera(context, JSONObject().put("facing", lens))
    }
    fun flipLens() {
        lens = if (lens == "back") "front" else "back"
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("lens", lens).apply()
        if (state.camera) Launcher.startCamera(context, JSONObject().put("facing", lens))
    }

    val connected = state.connected
    val actions: List<Triple<ImageVector, Pair<String, String>, Pair<Boolean, () -> Unit>>> = listOf(
        Triple(Icons.Default.Cast, "Mirror" to if (state.mirroring) "Live on Mac" else "Show on Mac", state.mirroring to ::toggleMirror),
        Triple(Icons.Default.Videocam, "Webcam" to if (state.camera) "Live · ${lens} camera" else "Use as camera", state.camera to ::toggleCamera),
        Triple(Icons.Default.UploadFile, "Send files" to "To Downloads", false to { pickFiles.launch("*/*") }),
        Triple(Icons.Default.ContentPaste, "Clipboard" to "Send to Mac", false to {
            context.startActivity(Intent(context, ClipboardActivity::class.java))
        }),
        Triple(Icons.Default.Public, "Open link" to "In Mac browser", false to { linkDialog = true }),
        Triple(Icons.Default.TouchApp, "Remote" to "Trackpad & keys", false to onOpenRemote),
    )

    val phoneName = remember { LinkManager.deviceName().removeSuffix("'s phone").take(18) }

    val linkSection: @Composable ColumnScope.() -> Unit = {
        ConnectionHero(state, phoneName = phoneName, phoneBattery = battery)

        // Live sessions get their own banner with a clear way out.
        AnimatedVisibility(state.mirroring || state.camera, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.mirroring) LiveBanner(Icons.Default.Cast, "Screen is on your Mac", "Control it with your Mac's mouse and keyboard", "Stop") { MirrorService.stop(context) }
                if (state.camera) LiveBanner(Icons.Default.Videocam, "Camera is live", "Showing as “Conduit Camera” · ${lens} lens", "Stop", secondary = "Flip" to ::flipLens) { CameraService.stop(context) }
            }
        }

        SectionLabel("Do more with ${state.macName.ifEmpty { "your Mac" }}")
        val columns = if (wide) 3 else 2
        actions.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, text, act) ->
                    ActionTile(icon, text.first, text.second, active = act.first, enabled = connected, modifier = Modifier.weight(1f), onClick = act.second)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    val statusSection: @Composable ColumnScope.() -> Unit = {
        SectionLabel("Syncing", trailing = {
            TextButton(onClick = onOpenFeatures, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Manage") }
        })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            features.filter { it.group == "On your Mac" || it.id == "mirror" || it.id == "camera" }.forEach { f ->
                FeaturePill(f.icon, f.title, f.ready) { if (f.ready) onOpenFeatures() else f.fix?.invoke() }
            }
        }

        SectionLabel("Find")
        ConduitCard(padding = 6.dp) {
            ListItemRow(Icons.Default.LaptopMac, "Ring ${state.macName.ifEmpty { "Mac" }}", if (state.macRinging) "Playing a sound on your Mac…" else "Play a sound to find it",
                trailing = { Switch(checked = state.macRinging, onCheckedChange = { LinkManager.send("ring") { put("on", it) } }, enabled = connected) })
        }

        SectionLabel("Recent")
        ConduitCard(padding = 14.dp) {
            if (activity.isEmpty()) {
                Text("Files, links and clipboard items you send either way show up here.", style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted)
            } else {
                activity.take(8).forEach { ActivityRow(it) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (wide) {
        // Unfolded: the link and actions on the left, what's syncing and recent activity on the right.
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(14.dp))
            Text("Conduit", style = MaterialTheme.typography.headlineMedium, color = Conduit.colors.text, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(Modifier.weight(1.15f).verticalScroll(rememberScrollState()), content = linkSection)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = statusSection)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))
            Text("Conduit", style = MaterialTheme.typography.headlineMedium, color = Conduit.colors.text, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp))
            linkSection()
            statusSection()
        }
    }

    if (linkDialog) OpenLinkDialog(onDismiss = { linkDialog = false })
}

@Composable
private fun LiveBanner(icon: ImageVector, title: String, detail: String, action: String, secondary: Pair<String, () -> Unit>? = null, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(ConduitBlue).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot()
        Spacer(Modifier.width(12.dp))
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
        secondary?.let { (label, act) -> TextButton(onClick = act) { Text(label, color = Color.White) } }
        FilledTonalButton(onClick = onAction, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White)) {
            Text(action)
        }
    }
}

@Composable
private fun PulsingDot() {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(700); on = !on } }
    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = if (on) 1f else 0.35f)))
}

@Composable
fun ListItemRow(icon: ImageVector, title: String, subtitle: String?, trailing: @Composable (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = {
            Box(Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(ConduitBlue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ConduitBlue, modifier = Modifier.size(20.dp))
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun OpenLinkDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clip = remember {
        context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: ""
    }
    var url by remember { mutableStateOf(clip) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Public, null) },
        title = { Text("Open on your Mac") },
        text = {
            OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(onClick = {
                val u = if (url.startsWith("http")) url.trim() else "https://${url.trim()}"
                Launcher.openOnMac(u); onDismiss()
            }, enabled = url.isNotBlank()) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun rememberPhoneBattery(): Int {
    val context = LocalContext.current
    var level by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                level = if (l >= 0 && s > 0) l * 100 / s else -1
            }
            delay(30_000)
        }
    }
    return level
}
