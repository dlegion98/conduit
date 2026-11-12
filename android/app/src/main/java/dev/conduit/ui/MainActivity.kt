package dev.conduit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.conduit.feature.Launcher
import dev.conduit.feature.NotificationMirror
import dev.conduit.feature.RemoteControlService
import dev.conduit.net.LinkManager
import dev.conduit.net.LinkState
import dev.conduit.net.PairingStore
import dev.conduit.service.LinkService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ConduitTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Launcher.appInForeground = true
        if (LinkManager.pairing.load() != null) {
            LinkService.start(this)
            LinkManager.kick()
        }
    }

    override fun onPause() {
        Launcher.appInForeground = false
        super.onPause()
    }
}

private val Bg = Color(0xFF0E1116)
private val Card = Color(0xFF171B22)
private val Accent = Color(0xFF4F8CFF)
private val Muted = Color(0xFF9AA4B2)

@Composable
fun ConduitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card, onSurface = Color.White),
        content = content,
    )
}

@Composable
private fun App() {
    val state by LinkManager.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (state.paired) NavigationBar(containerColor = Card) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Link, null) }, label = { Text("Link") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.TouchApp, null) }, label = { Text("Trackpad") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Keyboard, null) }, label = { Text("Keys") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!state.paired) PairScreen()
            else BoxWithConstraints {
                // Unfolded Fold 7: status on the left, the selected tool on the right.
                if (maxWidth > 700.dp && tab != 0) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(0.42f)) { LinkScreen(state) }
                        Box(Modifier.weight(0.58f)) { ToolScreen(tab) }
                    }
                } else if (tab == 0) LinkScreen(state) else ToolScreen(tab)
            }
        }
    }
}

@Composable
private fun ToolScreen(tab: Int) {
    if (tab == 1) TrackpadScreen() else KeysScreen()
}

// ---- Pairing ---------------------------------------------------------------

@Composable
private fun PairScreen() {
    val context = LocalContext.current
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    fun accept(text: String) {
        val mac = PairingStore.parsePairingUri(text)
        if (mac == null) {
            Toast.makeText(context, "That isn't a Conduit pairing code", Toast.LENGTH_LONG).show()
        } else {
            LinkManager.pair(mac)
            LinkService.start(context)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Link, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Link your Mac", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open Conduit on your Mac, choose Pair Phone, and scan the code. Both devices need to be on the same Wi-Fi.",
            color = Muted, fontSize = 15.sp,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { it.rawValue?.let(::accept) }
                .addOnFailureListener { Toast.makeText(context, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan pairing code")
        }
        TextButton(onClick = { showPaste = true }) { Text("Enter code instead") }
    }

    if (showPaste) AlertDialog(
        onDismissRequest = { showPaste = false },
        title = { Text("Pairing code") },
        text = { OutlinedTextField(pasted, { pasted = it }, placeholder = { Text("conduit://pair?…") }) },
        confirmButton = { TextButton(onClick = { showPaste = false; accept(pasted) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
    )
}

// ---- Status + permissions --------------------------------------------------

private data class Perm(val title: String, val detail: String, val granted: Boolean, val onFix: () -> Unit)

@Composable
private fun LinkScreen(state: LinkState) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    // Re-check permissions whenever we come back from Settings.
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1500); refresh++ } }

    fun has(vararg p: String) = p.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun settings(action: String, data: Uri? = null) =
        runCatching { context.startActivity(Intent(action).apply { data?.let { setData(it) } }) }

    val pkgUri = Uri.parse("package:${context.packageName}")
    val perms = remember(refresh) {
        val power = context.getSystemService(PowerManager::class.java)
        listOf(
            Perm("Notifications", "Show transfer and connection alerts", has(Manifest.permission.POST_NOTIFICATIONS)) {
                runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            },
            Perm("Notification access", "Mirror notifications and media controls", NotificationMirror.isEnabled(context)) {
                settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            Perm("Messages", "Read and send SMS from your Mac", has(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) {
                runtime.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
            },
            Perm("Contacts", "Show names instead of numbers", has(Manifest.permission.READ_CONTACTS)) {
                runtime.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            },
            Perm("Phone calls", "See, answer and place calls from your Mac",
                has(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE)) {
                runtime.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE))
            },
            Perm("Camera", "Use this phone as your Mac's webcam", has(Manifest.permission.CAMERA)) {
                runtime.launch(arrayOf(Manifest.permission.CAMERA))
            },
            Perm("Remote control", "Control the mirrored screen from your Mac. If it's greyed out: App info → ⋮ → Allow restricted settings",
                RemoteControlService.instance != null) {
                settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            },
            Perm("Run in background", "Stay connected when the screen is off", power.isIgnoringBatteryOptimizations(context.packageName)) {
                @Suppress("BatteryLife")
                settings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Spacer(Modifier.height(12.dp))
        StatusCard(state)
        Spacer(Modifier.height(20.dp))
        Text("Setup", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Card)) {
            perms.forEachIndexed { i, p ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (p.granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        tint = if (p.granted) Color(0xFF3DDC84) else Muted,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = Color.White, fontSize = 15.sp)
                        Text(p.detail, color = Muted, fontSize = 12.sp)
                    }
                    if (!p.granted) TextButton(onClick = p.onFix) { Text("Allow") }
                }
                if (i < perms.lastIndex) HorizontalDivider(color = Bg)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { context.startActivity(Intent(context, ClipboardActivity::class.java)) }, enabled = state.connected) {
                Text("Send clipboard")
            }
            OutlinedButton(onClick = { LinkManager.unpair(); LinkService.stop(context) }) { Text("Unpair") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: add the “Send clipboard” Quick Settings tile, and use Share → Send to Mac for files and links.",
            color = Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusCard(state: LinkState) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (state.connected) Color(0xFF3DDC84) else Color(0xFFFFB020)))
            Spacer(Modifier.width(10.dp))
            Text(state.macName.ifEmpty { "Mac" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (state.connected && state.macBattery >= 0) {
                Icon(if (state.macCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull, null, tint = Muted)
                Text("${state.macBattery}%", color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(state.detail, color = Muted, fontSize = 14.sp)
        if (state.mirroring || state.camera) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.mirroring) AssistChip(onClick = {}, label = { Text("Mirroring") }, leadingIcon = { Icon(Icons.Default.ScreenShare, null) })
                if (state.camera) AssistChip(onClick = {}, label = { Text("Webcam") }, leadingIcon = { Icon(Icons.Default.Videocam, null) })
            }
        }
    }
}

// ---- Keys + media ----------------------------------------------------------

@Composable
private fun KeysScreen() {
    var buffer by remember { mutableStateOf("") }
    fun key(k: String) = LinkManager.send("mac-input") { put("kind", "key"); put("key", k) }
    fun media(k: String) = LinkManager.send("mac-input") { put("kind", "media"); put("key", k) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Type on your Mac", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = buffer,
            onValueChange = { new ->
                val old = buffer
                when {
                    new.length > old.length && new.startsWith(old) ->
                        LinkManager.send("mac-input") { put("kind", "text"); put("text", new.substring(old.length)) }
                    new.length < old.length && old.startsWith(new) -> repeat(old.length - new.length) { key("delete") }
                    else -> {
                        // Autocorrect replaced a word: delete the old text and type the new.
                        val common = old.commonPrefixWith(new).length
                        repeat(old.length - common) { key("delete") }
                        LinkManager.send("mac-input") { put("kind", "text"); put("text", new.substring(common)) }
                    }
                }
                buffer = if (new.length > 200) "" else new
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Start typing…") },
            minLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        KeyRow(listOf("esc" to "escape", "tab" to "tab", "⌫" to "delete", "⏎" to "return", "space" to "space"), ::key)
        Spacer(Modifier.height(8.dp))
        KeyRow(listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "⌘⇥" to "cmdtab"), ::key)
        Spacer(Modifier.height(8.dp))
        KeyRow(listOf("Mission Ctrl" to "missioncontrol", "Spotlight" to "spotlight", "Lock" to "lock"), ::key)

        Spacer(Modifier.height(28.dp))
        Text("Media", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MediaButton(Icons.Default.SkipPrevious) { media("previous") }
            MediaButton(Icons.Default.PlayArrow, big = true) { media("playpause") }
            MediaButton(Icons.Default.SkipNext) { media("next") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MediaButton(Icons.AutoMirrored.Filled.VolumeOff) { media("mute") }
            MediaButton(Icons.AutoMirrored.Filled.VolumeDown) { media("voldown") }
            MediaButton(Icons.AutoMirrored.Filled.VolumeUp) { media("volup") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MediaButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { key("left") }
            Text("Slides", color = Muted, modifier = Modifier.align(Alignment.CenterVertically))
            MediaButton(Icons.AutoMirrored.Filled.KeyboardArrowRight) { key("right") }
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>, onKey: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, k) ->
            FilledTonalButton(onClick = { onKey(k) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                Text(label, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, big: Boolean = false, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(if (big) 72.dp else 56.dp)) {
        Icon(icon, null, modifier = Modifier.size(if (big) 36.dp else 26.dp))
    }
}
