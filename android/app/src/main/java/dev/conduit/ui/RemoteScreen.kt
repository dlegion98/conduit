package dev.conduit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.conduit.net.LinkManager
import dev.conduit.ui.theme.Conduit

private fun key(k: String) { LinkManager.send("mac-input") { put("kind", "key"); put("key", k) } }
private fun media(k: String) { LinkManager.send("mac-input") { put("kind", "media"); put("key", k) } }

/** Phone → Mac remote: trackpad, keyboard and media keys. Side by side when the Fold is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(wide: Boolean) {
    val state by LinkManager.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val fold = LocalFold.current

    // Half-folded on a desk: a tiny laptop. Keys on the upper half, trackpad on the lower half.
    if (state.connected && fold.posture == Posture.Tabletop) {
        HingeSplit(
            Modifier.padding(horizontal = 14.dp),
            first = {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 6.dp)) {
                    LaptopModeHeader(state.macName)
                    Spacer(Modifier.height(10.dp))
                    KeysPanel()
                    Spacer(Modifier.height(12.dp))
                    MediaPanel()
                }
            },
            second = { Box(Modifier.fillMaxSize().padding(bottom = 10.dp)) { Trackpad() } },
            fallback = { RemoteScreenContent(state.connected, state.macName, wide, mode) { mode = it } },
        )
        return
    }
    // Held like a book: trackpad on one page, keys on the other.
    if (state.connected && fold.posture == Posture.Book) {
        HingeSplit(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            first = { Box(Modifier.fillMaxSize().padding(end = 4.dp)) { Trackpad() } },
            second = { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 4.dp)) { KeysPanel(); Spacer(Modifier.height(12.dp)); MediaPanel() } },
            fallback = { RemoteScreenContent(state.connected, state.macName, wide, mode) { mode = it } },
        )
        return
    }
    RemoteScreenContent(state.connected, state.macName, wide, mode) { mode = it }
}

@Composable
private fun LaptopModeHeader(macName: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Icon(Icons.Default.Laptop, null, tint = dev.conduit.ui.theme.ConduitBlue)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Laptop mode", style = MaterialTheme.typography.titleLarge, color = Conduit.colors.text)
            Text("Controlling ${macName.ifEmpty { "your Mac" }} · open the phone flat for the full remote",
                style = MaterialTheme.typography.labelMedium, color = Conduit.colors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreenContent(connected: Boolean, macName: String, wide: Boolean, mode: Int, setMode: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Remote", style = MaterialTheme.typography.headlineMedium, color = Conduit.colors.text)
                Text(if (connected) "Controlling $macName · tip: half-fold the phone on a desk for laptop mode" else "Connect to your Mac to use the remote",
                    style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted)
            }
        }
        if (!connected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Waiting for ${macName.ifEmpty { "your Mac" }}…", color = Conduit.colors.muted)
            }
            return@Column
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1.3f)) { Trackpad() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { KeysPanel(); Spacer(Modifier.height(16.dp)); MediaPanel() }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Trackpad", "Keyboard", "Media").forEachIndexed { i, label ->
                    SegmentedButton(selected = mode == i, onClick = { setMode(i) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).padding(bottom = 16.dp)) {
                when (mode) {
                    0 -> Trackpad()
                    1 -> Column(Modifier.verticalScroll(rememberScrollState())) { KeysPanel() }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) { MediaPanel() }
                }
            }
        }
    }
}

@Composable
private fun KeysPanel() {
    var buffer by remember { mutableStateOf("") }
    ConduitCard {
        Text("Type on your Mac", style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text)
        Spacer(Modifier.height(10.dp))
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
            shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(12.dp))
        KeyRow(listOf("esc" to "escape", "tab" to "tab", "space" to "space", "return" to "return"))
        Spacer(Modifier.height(8.dp))
        IconKeyRow(listOf(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft to "left", Icons.Default.KeyboardArrowUp to "up",
            Icons.Default.KeyboardArrowDown to "down", Icons.AutoMirrored.Filled.KeyboardArrowRight to "right",
            Icons.AutoMirrored.Filled.Backspace to "delete",
        ))
    }
    Spacer(Modifier.height(12.dp))
    ConduitCard(padding = 14.dp) {
        Text("Shortcuts", style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutTile(Icons.Default.Search, "Spotlight", Modifier.weight(1f)) { key("spotlight") }
            ShortcutTile(Icons.Default.Apps, "App switch", Modifier.weight(1f)) { key("cmdtab") }
            ShortcutTile(Icons.Default.Dashboard, "Mission Ctrl", Modifier.weight(1f)) { key("missioncontrol") }
            ShortcutTile(Icons.Default.Lock, "Lock", Modifier.weight(1f)) { key("lock") }
        }
    }
}

@Composable
private fun MediaPanel() {
    ConduitCard {
        Text("Media on your Mac", style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Default.SkipPrevious) { media("previous") }
            RoundButton(Icons.Default.PlayArrow, big = true) { media("playpause") }
            RoundButton(Icons.Default.SkipNext) { media("next") }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(Icons.AutoMirrored.Filled.VolumeOff) { media("mute") }
            RoundButton(Icons.AutoMirrored.Filled.VolumeDown) { media("voldown") }
            RoundButton(Icons.AutoMirrored.Filled.VolumeUp) { media("volup") }
        }
    }
    Spacer(Modifier.height(12.dp))
    ConduitCard {
        Text("Presenting", style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text)
        Text("Step through Keynote or PowerPoint slides", style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { key("left") }, modifier = Modifier.weight(1f).height(64.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null); Text("Back")
            }
            Button(onClick = { key("right") }, modifier = Modifier.weight(1.4f).height(64.dp), shape = MaterialTheme.shapes.medium) {
                Text("Next"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
private fun KeyRow(keys: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (label, k) ->
            FilledTonalButton(onClick = { key(k) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(4.dp), shape = MaterialTheme.shapes.small) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun IconKeyRow(keys: List<Pair<ImageVector, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.forEach { (icon, k) ->
            FilledTonalButton(onClick = { key(k) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(4.dp), shape = MaterialTheme.shapes.small) {
                Icon(icon, k, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ShortcutTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) { Icon(icon, null) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Conduit.colors.muted, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, big: Boolean = false, onClick: () -> Unit) {
    if (big) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(76.dp)) { Icon(icon, null, modifier = Modifier.size(38.dp)) }
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(58.dp)) { Icon(icon, null, modifier = Modifier.size(26.dp)) }
    }
}
