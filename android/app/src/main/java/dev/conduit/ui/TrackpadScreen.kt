package dev.conduit.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.conduit.ui.theme.Conduit
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.conduit.net.LinkManager
import kotlin.math.min

/**
 * Phone as a Mac trackpad.
 * One finger: move · tap: click · two-finger tap: right-click · two fingers: scroll ·
 * hold, then move: drag.
 */
@Composable
fun Trackpad() {
    val view = LocalView.current

    fun send(kind: String, dx: Float = 0f, dy: Float = 0f) = LinkManager.send("mac-input") {
        put("kind", kind); put("dx", dx.toDouble()); put("dy", dy.toDouble())
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(Conduit.colors.card)
                .border(1.dp, Conduit.colors.outline, MaterialTheme.shapes.large)
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        var maxPointers = 1
                        var travelled = 0f
                        var dragging = false
                        var scrollCarry = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            maxPointers = maxOf(maxPointers, pressed.size)
                            val moving = event.changes.filter { it.pressed && it.previousPressed }
                            if (moving.isNotEmpty()) {
                                val d = moving.fold(Offset.Zero) { acc, c -> acc + (c.position - c.previousPosition) } / moving.size.toFloat()
                                travelled += d.getDistance()
                                if (pressed.size >= 2) {
                                    scrollCarry += d
                                    if (scrollCarry.getDistance() >= 2f) {
                                        send("scroll", scrollCarry.x * 1.2f, scrollCarry.y * 1.2f)
                                        scrollCarry = Offset.Zero
                                    }
                                } else if (maxPointers == 1) {
                                    val now = event.changes.first().uptimeMillis
                                    if (!dragging && travelled < slop * 2 && now - start > 450) {
                                        dragging = true
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        send("down")
                                    }
                                    // Simple acceleration: faster swipes travel further.
                                    val accel = 1.4f + min(d.getDistance() / 14f, 2.2f)
                                    send(if (dragging) "drag" else "move", d.x * accel, d.y * accel)
                                }
                            }
                            event.changes.forEach { it.consume() }
                            if (pressed.isEmpty()) {
                                val duration = event.changes.first().uptimeMillis - start
                                when {
                                    dragging -> send("up")
                                    travelled < slop && duration < 300 -> {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        send(if (maxPointers >= 2) "rightclick" else "click")
                                    }
                                }
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TouchApp, null, tint = Conduit.colors.faint, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(10.dp))
                Text("Move · tap to click · two fingers to scroll\nhold, then move, to drag", color = Conduit.colors.faint,
                    fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackButton("Left click", Modifier.weight(1f)) { send("click") }
            TrackButton("Right click", Modifier.weight(1f)) { send("rightclick") }
        }
    }
}

@Composable
private fun TrackButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = MaterialTheme.shapes.medium) {
        Text(label)
    }
}
