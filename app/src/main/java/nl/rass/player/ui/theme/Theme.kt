package nl.rass.player.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val RassColors = darkColorScheme(
    primary = Focus,
    onPrimary = Bg,
    secondary = Accent,
    onSecondary = TextPrimary,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextMuted,
    border = Focus,
    error = Accent,
)

@Composable
fun RassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RassColors, typography = RassTypography, content = content)
}
