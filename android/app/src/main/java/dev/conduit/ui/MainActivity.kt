package dev.conduit.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch
import dev.conduit.feature.Launcher
import dev.conduit.net.LinkManager
import dev.conduit.service.LinkService
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitTheme

class MainActivity : ComponentActivity() {
    private var fold by mutableStateOf(FoldInfo())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Follow the hinge: tabletop / book posture changes re-lay out the screens live.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity)
                    .collect { fold = it.toFoldInfo() }
            }
        }
        setContent { ConduitTheme { CompositionLocalProvider(LocalFold provides fold) { App() } } }
    }

    override fun onResume() {
        super.onResume()
        Launcher.appInForeground = true
        dev.conduit.feature.Updater.check(this)
        if (LinkManager.pairing.load() != null) {
            runCatching { LinkService.start(this) }
            LinkManager.kick()
        }
    }

    override fun onPause() {
        Launcher.appInForeground = false
        super.onPause()
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Remote("Remote", Icons.Outlined.TouchApp, Icons.Filled.TouchApp),
    Features("Features", Icons.Outlined.Tune, Icons.Filled.Tune),
}

@Composable
internal fun App(startTab: Int = 0) {
    val state by LinkManager.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.entries[startTab]) }

    Surface(color = Conduit.colors.background, modifier = Modifier.fillMaxSize()) {
        if (!state.paired) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { PairScreen() }
            return@Surface
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Unfolded Fold (or a tablet): navigation rail + wider layouts.
            val wide = maxWidth >= 600.dp
            val content: @Composable (Modifier) -> Unit = { mod ->
                AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab", modifier = mod) { t ->
                    when (t) {
                        Tab.Home -> HomeScreen(onOpenRemote = { tab = Tab.Remote }, onOpenFeatures = { tab = Tab.Features }, wide = wide)
                        Tab.Remote -> RemoteScreen(wide = wide)
                        Tab.Features -> FeaturesScreen()
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    NavigationRail(containerColor = Conduit.colors.background, modifier = Modifier.padding(top = 12.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { tab = t },
                                icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                    content(Modifier.weight(1f).fillMaxHeight().widthIn(max = 1100.dp))
                }
            } else {
                Scaffold(
                    containerColor = Conduit.colors.background,
                    bottomBar = {
                        NavigationBar(containerColor = Conduit.colors.card, tonalElevation = 0.dp) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { Icon(if (tab == t) t.selectedIcon else t.icon, null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { pad -> content(Modifier.padding(pad).fillMaxSize()) }
            }
        }
    }
}
