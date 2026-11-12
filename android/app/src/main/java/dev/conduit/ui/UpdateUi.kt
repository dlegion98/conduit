package dev.conduit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.conduit.feature.Updater
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitBlue
import dev.conduit.ui.theme.LinkGreen
import dev.conduit.ui.theme.WaitAmber

/** Shown at the top of Home only when there's an update to act on. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val state by Updater.state.collectAsStateWithLifecycle()
    val show = state is Updater.State.Available || state is Updater.State.Downloading || state is Updater.State.Installing
    AnimatedVisibility(show, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(), modifier = modifier) {
        ConduitCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), padding = 16.dp) { UpdateContent(state, compact = true) }
    }
}

/** Full "Updates" card for the Features tab. */
@Composable
fun UpdateCard() {
    val state by Updater.state.collectAsStateWithLifecycle()
    ConduitCard(Modifier.fillMaxWidth(), padding = 16.dp) { UpdateContent(state, compact = false) }
}

@Composable
private fun UpdateContent(state: Updater.State, compact: Boolean) {
    val context = LocalContext.current
    val current = remember { Updater.currentVersion(context) }
    var needsPermission by remember { mutableStateOf(false) }

    fun install() {
        if (!Updater.canInstall(context)) { needsPermission = true; return }
        Updater.install(context)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (state) {
            is Updater.State.Available, is Updater.State.Downloading, Updater.State.Installing -> Icons.Default.SystemUpdate to ConduitBlue
            is Updater.State.Failed -> Icons.Default.ErrorOutline to WaitAmber
            is Updater.State.UpToDate -> Icons.Default.CheckCircle to LinkGreen
            else -> Icons.Default.Update to ConduitBlue
        }
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (state) {
                    is Updater.State.Available -> "Conduit ${state.version} is ready"
                    is Updater.State.Downloading -> "Downloading ${state.version}…"
                    Updater.State.Installing -> "Installing…"
                    is Updater.State.UpToDate -> "You're up to date"
                    Updater.State.Checking -> "Checking for updates…"
                    is Updater.State.Failed -> "Update problem"
                    Updater.State.Idle -> "Conduit $current"
                },
                style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text,
            )
            Text(
                when (state) {
                    is Updater.State.Available -> state.notes.ifBlank { "Installs in place. Your pairing and settings are kept." }
                    is Updater.State.Downloading -> "${(state.progress * 100).toInt()}%"
                    Updater.State.Installing -> "Conduit will restart on the new version"
                    is Updater.State.Failed -> state.message
                    else -> "Version $current · updates install from inside the app"
                },
                style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted, maxLines = if (compact) 2 else 4,
            )
        }
        Spacer(Modifier.width(10.dp))
        when (state) {
            is Updater.State.Available -> Button(onClick = ::install) { Text("Update") }
            Updater.State.Checking, Updater.State.Installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            is Updater.State.Downloading -> {}
            else -> if (!compact) OutlinedButton(onClick = { Updater.check(context, force = true) }) { Text("Check") }
        }
    }
    if (state is Updater.State.Downloading) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
    }

    if (needsPermission) AlertDialog(
        onDismissRequest = { needsPermission = false },
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = { Text("Allow Conduit to install updates") },
        text = { Text("One time only: turn on “Allow from this source” for Conduit, then come back and tap Update.") },
        confirmButton = { Button(onClick = { needsPermission = false; Updater.openInstallPermission(context) }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { needsPermission = false }) { Text("Not now") } },
    )
}

