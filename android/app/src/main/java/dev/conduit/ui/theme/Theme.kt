package dev.conduit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Conduit blue — the same accent the Mac app uses, so both ends feel like one product. */
val ConduitBlue = Color(0xFF4F8CFF)
val LinkGreen = Color(0xFF34C77B)
val WaitAmber = Color(0xFFFFB020)

@Immutable
data class ConduitColors(
    val background: Color,
    val card: Color,
    val cardRaised: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
)

private val Dark = ConduitColors(
    background = Color(0xFF0B0E13),
    card = Color(0xFF151A22),
    cardRaised = Color(0xFF1C2330),
    outline = Color(0xFF263042),
    text = Color(0xFFF2F5FA),
    muted = Color(0xFF9AA4B2),
    faint = Color(0xFF5A6474),
)

private val Light = ConduitColors(
    background = Color(0xFFF4F6FA),
    card = Color(0xFFFFFFFF),
    cardRaised = Color(0xFFEDF1F8),
    outline = Color(0xFFDCE2EC),
    text = Color(0xFF111723),
    muted = Color(0xFF5D6778),
    faint = Color(0xFFA3ACBA),
)

val LocalConduit = staticCompositionLocalOf { Dark }

object Conduit {
    val colors: ConduitColors @Composable get() = LocalConduit.current
}

@Composable
fun ConduitTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) darkColorScheme(
        primary = ConduitBlue, onPrimary = Color.White,
        secondaryContainer = Color(0xFF1E2C47), onSecondaryContainer = Color(0xFFD6E3FF),
        background = c.background, surface = c.card, surfaceContainer = c.card,
        surfaceContainerHigh = c.cardRaised, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.outline,
    ) else lightColorScheme(
        primary = ConduitBlue, onPrimary = Color.White,
        secondaryContainer = Color(0xFFDCE7FF), onSecondaryContainer = Color(0xFF0E2A5C),
        background = c.background, surface = c.card, surfaceContainer = c.card,
        surfaceContainerHigh = c.cardRaised, onSurface = c.text, onSurfaceVariant = c.muted, outline = c.outline,
    )
    val type = Typography(
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 14.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
    val shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp),
    )
    CompositionLocalProvider(LocalConduit provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
