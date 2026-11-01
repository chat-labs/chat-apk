package moe.vin.chat.ui

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val onAccent: Color,
    val mine: Color,
    val warn: Color,
    val bad: Color,
    val good: Color,
)

private val Dark = Palette(
    dark = true,
    bg = Color(0xFF0E0F11),
    surface = Color(0xFF15171A),
    raised = Color(0xFF1C1F23),
    line = Color(0xFF26292E),
    text = Color(0xFFE7E5E4),
    muted = Color(0xFF9CA3AF),
    faint = Color(0xFF6B7280),
    accent = Color(0xFF5EEAD4),
    onAccent = Color(0xFF0E0F11),
    mine = Color(0xFF123B37),
    warn = Color(0xFFF5B341),
    bad = Color(0xFFF87171),
    good = Color(0xFF4ADE80),
)

private val Light = Palette(
    dark = false,
    bg = Color(0xFFFAFAF9),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF1F1EF),
    line = Color(0xFFE4E4E1),
    text = Color(0xFF18181B),
    muted = Color(0xFF57575F),
    faint = Color(0xFF9A9AA2),
    accent = Color(0xFF0F766E),
    onAccent = Color(0xFFFFFFFF),
    mine = Color(0xFFDDF3EF),
    warn = Color(0xFFB45309),
    bad = Color(0xFFDC2626),
    good = Color(0xFF15803D),
)

val LocalPalette = staticCompositionLocalOf { Dark }

val Mono = FontFamily.Monospace

object Type {
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 12.sp, fontFamily = Mono, letterSpacing = 0.2.sp)
    val code = TextStyle(fontSize = 15.sp, fontFamily = Mono, lineHeight = 22.sp)
    val section = TextStyle(fontSize = 11.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
}

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        secondary = p.accent,
        background = p.bg,
        onBackground = p.text,
        surface = p.bg,
        onSurface = p.text,
        surfaceVariant = p.raised,
        onSurfaceVariant = p.muted,
        surfaceContainer = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainerHigh = p.raised,
        surfaceContainerHighest = p.raised,
        outline = p.line,
        outlineVariant = p.line,
        error = p.bad,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = Type.body, bodyMedium = Type.body, labelLarge = Type.small),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(10.dp),
                large = RoundedCornerShape(14.dp),
                extraLarge = RoundedCornerShape(18.dp),
            ),
            content = content,
        )
    }
}

// A peer's own colour, eased towards the text colour until it reads on the background, as the
// TUI eases it against the terminal's.
fun readable(argb: Int?, p: Palette): Color {
    val c = argb?.let { Color(it) } ?: return p.muted
    val l = c.luminance()
    return when {
        p.dark && l < 0.18f -> lerp(c, Color.White, (0.18f - l) * 2.5f)
        !p.dark && l > 0.45f -> lerp(c, Color.Black, (l - 0.45f) * 1.4f)
        else -> c
    }
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 0.8f)
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)
}
