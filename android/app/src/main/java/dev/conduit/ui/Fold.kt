package dev.conduit.ui

import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

/** How a foldable is being held. */
enum class Posture {
    /** Closed (cover screen), fully open, or not a foldable. */
    Normal,
    /** Half-folded with a horizontal hinge, e.g. standing on a desk like a tiny laptop. */
    Tabletop,
    /** Half-folded with a vertical hinge, held like a book. */
    Book,
}

/** Posture plus the hinge's bounds in window pixels (null when there is no separating hinge). */
@Immutable
data class FoldInfo(val posture: Posture = Posture.Normal, val hinge: Rect? = null)

val LocalFold = staticCompositionLocalOf { FoldInfo() }

fun WindowLayoutInfo.toFoldInfo(): FoldInfo {
    val fold = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldInfo()
    val posture = when {
        fold.state != FoldingFeature.State.HALF_OPENED -> Posture.Normal
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.Tabletop
        else -> Posture.Book
    }
    // Only lay out around the hinge when it actually splits the screen.
    return FoldInfo(posture, if (fold.isSeparating || posture != Posture.Normal) Rect(fold.bounds) else null)
}

/**
 * Splits [first] and [second] exactly at the hinge: top/bottom in tabletop, left/right in book posture.
 * Falls back to [fallback] when there's no hinge crossing this composable.
 */
@Composable
fun HingeSplit(
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    fallback: @Composable () -> Unit,
) {
    val fold = LocalFold.current
    val hinge = fold.hinge
    var origin by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val density = LocalDensity.current

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow() }) {
        val o = origin
        if (hinge == null || fold.posture == Posture.Normal || o == null) {
            fallback()
            return@Box
        }
        with(density) {
            if (fold.posture == Posture.Tabletop) {
                val split = (hinge.top - o.top).coerceIn(0f, o.height)
                val gap = hinge.height().toFloat()
                if (split <= 0f || split >= o.height) { fallback(); return@Box }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(split.toDp())) { first() }
                    Spacer(Modifier.height(gap.toDp().coerceAtLeast(12.dp)))
                    Box(Modifier.fillMaxWidth().weight(1f)) { second() }
                }
            } else {
                val split = (hinge.left - o.left).coerceIn(0f, o.width)
                val gap = hinge.width().toFloat()
                if (split <= 0f || split >= o.width) { fallback(); return@Box }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxHeight().width(split.toDp())) { first() }
                    Spacer(Modifier.width(gap.toDp().coerceAtLeast(12.dp)))
                    Box(Modifier.fillMaxHeight().weight(1f)) { second() }
                }
            }
        }
    }
}
