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
    "Pink" to Accents(0xFFFFB0CB, 0xFF5E1133, 0xFFB5446E),
    "Teal" to Accents(0xFF7FD6CC, 0xFF003732, 0xFF00786F),
    "Yellow" to Accents(0xFFE9C46A, 0xFF402D00, 0xFF8A6500),
    "Graphite" to Accents(0xFFC4C7CC, 0xFF2E3135, 0xFF565B62),
)

fun colorSchemeFor(theme: String, dark: Boolean): ColorScheme {
    val a = accents[theme] // null = Default (original Apple-Music-like look, unchanged)
    if (a == null) return if (dark)
        darkColorScheme(primary = Accent, onPrimary = Color(0xFF0F0F12), background = Color(0xFF0F0F12), surface = Color(0xFF1D1D23), onBackground = Color(0xFFF2F2F5), onSurface = Color(0xFFF2F2F5), onSurfaceVariant = Color(0xFF9A9AA6), outlineVariant = Color(0xFF26262C))
    else
        lightColorScheme(primary = Accent, onPrimary = Color.White, background = Color.White, surface = Color(0xFFF2F2F7), onBackground = Color.Black, onSurface = Color.Black, onSurfaceVariant = Color(0xFF8E8E93), outlineVariant = Color(0x1F000000))
    // Neutral surfaces with a faint tint of the accent; accent itself only on primary.
    return if (dark) {
        val p = Color(a.dark)
        darkColorScheme(
            primary = p, onPrimary = Color(a.onDark),
            background = lerp(Color(0xFF0F0F10), p, 0.14f), surface = lerp(Color(0xFF1C1C1E), p, 0.20f),
            onBackground = Color(0xFFE6E6EA), onSurface = Color(0xFFE6E6EA), onSurfaceVariant = Color(0xFFA0A0A8),
            outlineVariant = Color(0x33FFFFFF),
        )
    } else {
        val p = Color(a.light)
        lightColorScheme(
            primary = p, onPrimary = Color.White,
            background = lerp(Color.White, p, 0.08f), surface = lerp(Color(0xFFF2F2F7), p, 0.16f),
            onBackground = Color(0xFF1B1B1F), onSurface = Color(0xFF1B1B1F), onSurfaceVariant = Color(0xFF63636A),
            outlineVariant = Color(0x1F000000),
        )
    }
}
