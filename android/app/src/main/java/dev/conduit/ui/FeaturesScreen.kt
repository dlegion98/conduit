package dev.conduit.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.conduit.net.LinkManager
import dev.conduit.service.LinkService
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitBlue
import dev.conduit.ui.theme.LinkGreen

/** Every feature of the link in one place, with its setup state. Replaces a bare permissions list. */
@Composable
fun FeaturesScreen() {
    val context = LocalContext.current
    val state by LinkManager.state.collectAsStateWithLifecycle()
    val features = rememberFeatures()
    val ready = features.count { it.ready }
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        Text("Features", style = MaterialTheme.typography.headlineMedium, color = Conduit.colors.text, modifier = Modifier.padding(start = 4.dp))
        Text("$ready of ${features.size} ready", style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { ready / features.size.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = if (ready == features.size) LinkGreen else ConduitBlue,
            trackColor = Conduit.colors.cardRaised,
        )

        features.groupBy { it.group }.forEach { (group, items) ->
            SectionLabel(group)
            ConduitCard(padding = 4.dp) {
                items.forEachIndexed { i, f ->
                    ListItem(
                        headlineContent = { Text(f.title, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(if (f.ready || f.setupHint == null) f.description else f.setupHint, style = MaterialTheme.typography.bodyMedium,
                                color = if (f.ready) Conduit.colors.muted else ConduitBlue)
                        },
                        leadingContent = {
                            Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
                                .background(if (f.ready) ConduitBlue.copy(alpha = 0.12f) else Conduit.colors.cardRaised),
                                contentAlignment = Alignment.Center) {
                                Icon(f.icon, null, tint = if (f.ready) ConduitBlue else Conduit.colors.faint, modifier = Modifier.size(21.dp))
                            }
                        },
                        trailingContent = {
                            if (f.ready) Icon(Icons.Default.CheckCircle, "Ready", tint = LinkGreen)
                            else FilledTonalButton(onClick = { f.fix?.invoke() }) { Text("Set up") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (i < items.lastIndex) HorizontalDivider(Modifier.padding(start = 72.dp), color = Conduit.colors.outline.copy(alpha = 0.5f))
                }
            }
        }

        SectionLabel("Battery")
        ConduitCard(padding = 4.dp) {
            ListItemRow(Icons.Default.Eco, "Light on battery", "One tiny keepalive every 90 s, nothing while you're off Wi-Fi, and longer gaps in Battery Saver")
            ListItemRow(Icons.Default.Wifi, "Reconnects when it matters", "Straight away when you unlock the phone or rejoin Wi-Fi")
        }

        SectionLabel("Shortcuts")
        ConduitCard(padding = 4.dp) {
            ListItemRow(Icons.Default.ViewQuilt, "Quick Settings tiles", "Add “Send clipboard” and “Mirror to Mac” to your quick settings panel")
            ListItemRow(Icons.Default.Share, "Share sheet", "Choose “Send to Mac” from any app to send files, links or text")
        }

        SectionLabel("Updates")
        UpdateCard()

        SectionLabel("Paired Mac")
        ConduitCard(padding = 4.dp) {
            ListItemRow(Icons.Default.LaptopMac, state.macName.ifEmpty { "Mac" }, if (state.connected) "Connected · ${state.host}" else "Not connected")
            ListItemRow(Icons.Default.Info, "If remote control is greyed out",
                "Open App info → ⋮ → Allow restricted settings, then turn it on in Accessibility",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                })
            ListItemRow(Icons.Default.LinkOff, "Unpair", "Forget this Mac and stop syncing", onClick = { confirmUnpair = true })
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair ${state.macName}?") },
        text = { Text("You'll need to scan a new pairing code on your Mac to connect again.") },
        confirmButton = {
            TextButton(onClick = { confirmUnpair = false; LinkManager.unpair(); LinkService.stop(context) }) { Text("Unpair") }
        },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
    )
}
