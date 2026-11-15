package app.tgkiku

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Accent per theme: (dark primary, onPrimary dark, light primary). Light onPrimary is white.
// Dark = M3 tone 80 / on tone 20, light = tone 40 (all >= 4.5:1 text contrast).
private class Accents(val dark: Long, val onDark: Long, val light: Long)

private val accents = mapOf(
    "Red" to Accents(0xFFFFB3B3, 0xFF5F0A12, 0xFFBA1A2B),
    "Blue" to Accents(0xFFA8C7FF, 0xFF002F66, 0xFF1B5FC1),
    "Purple" to Accents(0xFFD0BCFF, 0xFF381E72, 0xFF6750A4),
    "Green" to Accents(0xFF8FD694, 0xFF00390F, 0xFF2E7D32),
    "Orange" to Accents(0xFFFFB77C, 0xFF4F2500, 0xFFA04A00),
)

fun colorSchemeFor(theme: String, dark: Boolean): ColorScheme {
    val a = accents[theme] // null = Default (original Apple-Music-like look, unchanged)
    if (a == null) return if (dark)
        darkColorScheme(primary = Accent, onPrimary = Color.White, background = Color.Black, surface = Color(0xFF1C1C1E), onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0x33FFFFFF))
    else
        lightColorScheme(primary = Accent, onPrimary = Color.White, background = Color.White, surface = Color(0xFFF2F2F7), onBackground = Color.Black, onSurface = Color.Black, onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0x1F000000))
    // Neutral surfaces with a faint tint of the accent; accent itself only on primary.
    return if (dark) {
        val p = Color(a.dark)
        darkColorScheme(
            primary = p, onPrimary = Color(a.onDark),
            background = lerp(Color(0xFF0F0F10), p, 0.04f), surface = lerp(Color(0xFF1C1C1E), p, 0.05f),
            onBackground = Color(0xFFE6E6EA), onSurface = Color(0xFFE6E6EA), onSurfaceVariant = Color(0xFFA0A0A8),
            outlineVariant = Color(0x33FFFFFF),
        )
    } else {
        val p = Color(a.light)
        lightColorScheme(
            primary = p, onPrimary = Color.White,
            background = lerp(Color.White, p, 0.02f), surface = lerp(Color(0xFFF2F2F7), p, 0.05f),
            onBackground = Color(0xFF1B1B1F), onSurface = Color(0xFF1B1B1F), onSurfaceVariant = Color(0xFF63636A),
            outlineVariant = Color(0x1F000000),
        )
    }
}
