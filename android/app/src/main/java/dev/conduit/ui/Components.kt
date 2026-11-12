package dev.conduit.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.conduit.net.ActivityItem
import dev.conduit.net.LinkState
import dev.conduit.ui.theme.Conduit
import dev.conduit.ui.theme.ConduitBlue
import dev.conduit.ui.theme.LinkGreen
import dev.conduit.ui.theme.WaitAmber
import java.text.DateFormat
import java.util.Date

/** Rounded card used everywhere, so every screen shares one surface style. */
@Composable
fun ConduitCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(Conduit.colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Conduit.colors.muted, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * The phone ⇄ Mac link. Dots flow along the line while connected; the line breaks and
 * turns amber while reconnecting. Active features (mirror, webcam) glow on the line.
 */
@Composable
fun ConnectionHero(state: LinkState, phoneName: String, phoneBattery: Int, modifier: Modifier = Modifier) {
    val lineColor by animateColorAsState(
        when {
            state.connected && (state.mirroring || state.camera) -> ConduitBlue
            state.connected -> LinkGreen
            else -> WaitAmber
        }, label = "line"
    )
    val flow = rememberInfiniteTransition(label = "flow")
    val phase by flow.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase")
    val pulse by flow.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    ConduitCard(modifier.fillMaxWidth(), padding = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceBadge(Icons.Default.PhoneAndroid, phoneName, if (phoneBattery >= 0) "$phoneBattery%" else "This phone", active = true)
            Canvas(Modifier.weight(1f).height(56.dp).padding(horizontal = 6.dp)) {
                val y = size.height / 2
                if (state.connected) {
                    drawLine(lineColor.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), strokeWidth = 6f, cap = StrokeCap.Round)
                    // Flowing packets: one stream each way, so it reads as a two-way link.
                    val n = 4
                    for (i in 0 until n) {
                        val t = (phase + i.toFloat() / n) % 1f
                        drawCircle(lineColor, radius = 5f, center = Offset(size.width * t, y - 7f))
                        drawCircle(lineColor.copy(alpha = 0.7f), radius = 4f, center = Offset(size.width * (1f - t), y + 7f))
                    }
                } else {
                    drawLine(
                        lineColor.copy(alpha = pulse), Offset(0f, y), Offset(size.width, y), strokeWidth = 5f, cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 14f)),
                    )
                }
            }
            DeviceBadge(
                Icons.Default.LaptopMac, state.macName.ifEmpty { "Mac" },
                if (state.connected && state.macBattery >= 0) "${state.macBattery}%${if (state.macCharging) " ⚡" else ""}" else if (state.connected) "Connected" else "Offline",
                active = state.connected,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(lineColor.copy(alpha = if (state.connected) 1f else pulse)))
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state.mirroring && state.camera -> "Mirroring and streaming camera to ${state.macName}"
                    state.mirroring -> "Mirroring your screen to ${state.macName}"
                    state.camera -> "${state.macName} is using your camera"
                    else -> state.detail
                },
                style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceBadge(icon: ImageVector, name: String, detail: String, active: Boolean) {
    val tint by animateColorAsState(if (active) ConduitBlue else Conduit.colors.faint, label = "badge")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.08f))))
                .border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = Conduit.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = Conduit.colors.muted, maxLines = 1)
    }
}

/** Square action button. `active` lights it up in Conduit blue (e.g. while mirroring). */
@Composable
fun ActionTile(icon: ImageVector, title: String, subtitle: String? = null, active: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) ConduitBlue else Conduit.colors.card, label = "tileBg")
    val fg = if (active) Color.White else Conduit.colors.text
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                .background(if (active) Color.White.copy(alpha = 0.2f) else ConduitBlue.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (active) Color.White else ConduitBlue, modifier = Modifier.size(21.dp)) }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = if (active) Color.White.copy(alpha = 0.8f) else Conduit.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small "Notifications ✓" pill showing which features are live. */
@Composable
fun FeaturePill(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape)
            .background(if (on) LinkGreen.copy(alpha = 0.14f) else Conduit.colors.cardRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) LinkGreen else Conduit.colors.faint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Conduit.colors.text else Conduit.colors.muted)
        if (!on) {
            Spacer(Modifier.width(6.dp))
            Text("Set up", style = MaterialTheme.typography.labelMedium, color = ConduitBlue)
        }
    }
}

fun ActivityItem.Kind.icon(): ImageVector = when (this) {
    ActivityItem.Kind.CONNECTED -> Icons.Default.Link
    ActivityItem.Kind.DISCONNECTED -> Icons.Default.LinkOff
    ActivityItem.Kind.FILE_OUT -> Icons.Default.Upload
    ActivityItem.Kind.FILE_IN -> Icons.Default.Download
    ActivityItem.Kind.CLIPBOARD_OUT, ActivityItem.Kind.CLIPBOARD_IN -> Icons.Default.ContentPaste
    ActivityItem.Kind.LINK_OUT, ActivityItem.Kind.LINK_IN -> Icons.Default.Public
    ActivityItem.Kind.MIRROR -> Icons.Default.Cast
    ActivityItem.Kind.CAMERA -> Icons.Default.Videocam
    ActivityItem.Kind.RING -> Icons.Default.NotificationsActive
    ActivityItem.Kind.ERROR -> Icons.Default.ErrorOutline
}

@Composable
fun ActivityRow(item: ActivityItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        val incoming = item.kind in setOf(ActivityItem.Kind.FILE_IN, ActivityItem.Kind.CLIPBOARD_IN, ActivityItem.Kind.LINK_IN, ActivityItem.Kind.RING)
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Conduit.colors.cardRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.kind.icon(), null, modifier = Modifier.size(17.dp),
                tint = if (item.kind == ActivityItem.Kind.ERROR) WaitAmber else if (incoming) LinkGreen else ConduitBlue)
        }
        Spacer(Modifier.width(12.dp))
        Text(item.text, style = MaterialTheme.typography.bodyMedium, color = Conduit.colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.time)), style = MaterialTheme.typography.labelMedium, color = Conduit.colors.faint)
    }
}
