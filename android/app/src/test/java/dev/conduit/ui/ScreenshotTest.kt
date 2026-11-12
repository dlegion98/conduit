package dev.conduit.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.conduit.net.ActivityItem
import dev.conduit.net.LinkManager
import dev.conduit.net.LinkState
import dev.conduit.ui.theme.ConduitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the main screens to PNGs (build/screenshots) so the design can be reviewed in CI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val connected = LinkState(
        paired = true, connected = true, macName = "MacBook Pro", host = "192.168.1.20",
        detail = "Connected to MacBook Pro", macBattery = 82, macCharging = true,
    )
    private val activity = listOf(
        ActivityItem(ActivityItem.Kind.FILE_IN, "Received Q4 report.pdf"),
        ActivityItem(ActivityItem.Kind.LINK_OUT, "Opened github.com on Mac"),
        ActivityItem(ActivityItem.Kind.CLIPBOARD_IN, "Copied from Mac: 221B Baker Street"),
        ActivityItem(ActivityItem.Kind.FILE_OUT, "Sent IMG_2041.jpg"),
        ActivityItem(ActivityItem.Kind.CONNECTED, "Connected to MacBook Pro"),
    )

    private fun shot(name: String, state: LinkState, tab: Int = 0, dark: Boolean = true, fold: FoldInfo = FoldInfo()) {
        LinkManager.previewState(state, activity)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ConduitTheme(dark = dark) {
                androidx.compose.runtime.CompositionLocalProvider(LocalFold provides fold) { App(startTab = tab) }
            }
        }
        compose.mainClock.advanceTimeBy(700)
        // Draw the window directly: the animated link line never lets Compose go "idle".
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val v = compose.activity.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun homeFolded() = shot("1-home-folded", connected)

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun homeLive() = shot("2-home-live", connected.copy(mirroring = true, camera = true))

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun homeLight() = shot("3-home-light", connected, dark = false)

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun remoteFolded() = shot("4-remote-folded", connected, tab = 1)

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun featuresFolded() = shot("5-features-folded", connected, tab = 2)

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun pairing() = shot("6-pair", LinkState())

    @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    fun offline() = shot("7-home-reconnecting", connected.copy(connected = false, detail = "Can't reach MacBook Pro. Is it on the same Wi-Fi?"))

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun homeUnfolded() = shot("8-home-unfolded", connected)

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun remoteUnfolded() = shot("9-remote-unfolded", connected, tab = 1)

    // Fold 7 inner screen half-folded. xhdpi: 880dp x 800dp = 1760 x 1600 px.
    private val tabletop = FoldInfo(Posture.Tabletop, android.graphics.Rect(0, 800, 1760, 800))
    private val book = FoldInfo(Posture.Book, android.graphics.Rect(880, 0, 880, 1600))

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun remoteTabletop() = shot("10-remote-tabletop", connected, tab = 1, fold = tabletop)

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun homeTabletop() = shot("11-home-tabletop", connected, fold = tabletop)

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun remoteBook() = shot("12-remote-book", connected, tab = 1, fold = book)

    @Test @Config(qualifiers = "w880dp-h800dp-xhdpi")
    fun homeBook() = shot("13-home-book", connected, fold = book)
}
