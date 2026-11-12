package dev.conduit.ui

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.conduit.net.LinkManager
import dev.conduit.net.LinkState
import dev.conduit.net.PairingStore
import dev.conduit.service.LinkService
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitBlue

@Composable
fun PairScreen() {
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

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) { Column(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        // Same hero as the home screen, in its "not linked yet" state.
        ConnectionHero(LinkState(macName = "Your Mac", detail = "Not linked yet"), phoneName = "This phone", phoneBattery = -1)
        Spacer(Modifier.height(28.dp))
        Text("Link your Mac", style = MaterialTheme.typography.headlineMedium, color = Conduit.colors.text)
        Spacer(Modifier.height(8.dp))
        Text("Notifications, messages, calls, files, your screen and your camera, all on your Mac. No account, no cloud.",
            style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        ConduitCard(Modifier.fillMaxWidth(), padding = 8.dp) {
            Step(1, Icons.Default.LaptopMac, "Open Conduit on your Mac", "Click the phone icon in the menu bar")
            Step(2, Icons.Default.QrCode2, "Choose Pair Phone…", "A pairing code appears")
            Step(3, Icons.Default.QrCodeScanner, "Scan it here", "Both devices on the same Wi-Fi")
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { it.rawValue?.let(::accept) }
                    .addOnFailureListener { Toast.makeText(context, "Scanner unavailable: ${it.message}", Toast.LENGTH_LONG).show() }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("Scan pairing code")
        }
        TextButton(onClick = { showPaste = true }) { Text("Enter code instead") }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = Conduit.colors.faint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted on your local network", style = MaterialTheme.typography.labelMedium, color = Conduit.colors.faint)
        }
    } }

    if (showPaste) AlertDialog(
        onDismissRequest = { showPaste = false },
        title = { Text("Pairing code") },
        text = { OutlinedTextField(pasted, { pasted = it }, placeholder = { Text("conduit://pair?…") }) },
        confirmButton = { TextButton(onClick = { showPaste = false; accept(pasted) }) { Text("Pair") } },
        dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Step(n: Int, icon: ImageVector, title: String, detail: String) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(detail, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Box(Modifier.size(34.dp).clip(CircleShape).background(ConduitBlue), contentAlignment = Alignment.Center) {
                Text("$n", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        },
        trailingContent = { Icon(icon, null, tint = Conduit.colors.faint) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
