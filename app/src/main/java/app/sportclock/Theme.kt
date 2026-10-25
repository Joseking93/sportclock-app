package app.sportclock

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The four looks, the same tokens as the website: Night (lime) and Paddock (teal), each light and dark.
 * [bright] is the palette's dark-mode accent, for things drawn on dark asphalt (the track map) in either mode.
 */
data class Palette(
    val dark: Boolean, val bg: Color, val tile: Color, val tile2: Color, val nav: Color, val line: Color, val outline: Color,
    val fg: Color, val fg2: Color, val fg3: Color, val accent: Color, val onAccent: Color, val live: Color, val ringTrack: Color,
    val heroA: Color, val heroB: Color, val bright: Color,
)

private fun c(v: Long) = Color(v)
val NIGHT_DARK = Palette(true, c(0xFF0A0C08), c(0xFF151712), c(0xFF1C1E19), c(0xFF12140F), c(0xFF2A2D26), c(0xFF383B33),
    c(0xFFECEEE6), c(0xFFA6A99E), c(0xFF868980), c(0xFFC8F53C), c(0xFF192300), c(0xFFFF5C5C), c(0xFF2A2E22),
    c(0xFF171C0D), c(0xFF0B0D09), c(0xFFC8F53C))
val NIGHT_LIGHT = Palette(false, c(0xFFF2F4EB), c(0xFFFFFFFF), c(0xFFE6E9DC), c(0xFFFFFFFF), c(0xFFE0E3D6), c(0xFFC9CDBD),
    c(0xFF12150D), c(0xFF50583F), c(0xFF646C55), c(0xFF4A7000), c(0xFFFFFFFF), c(0xFFC81E1E), c(0xFFD9DDCC),
    c(0xFFFFFFFF), c(0xFFE9EDDD), c(0xFFC8F53C))
val PADDOCK_DARK = Palette(true, c(0xFF14171A), c(0xFF1E2226), c(0xFF2A2F34), c(0xFF181B1E), c(0xFF2C3238), c(0xFF3A4148),
    c(0xFFECEFF1), c(0xFFA7B0B8), c(0xFF8A949E), c(0xFF3DD9BC), c(0xFF05221E), c(0xFFFF6B5E), c(0xFF2C3238),
    c(0xFF1C2329), c(0xFF111417), c(0xFF3DD9BC))
val PADDOCK_LIGHT = Palette(false, c(0xFFF2F4F5), c(0xFFFFFFFF), c(0xFFE6EAED), c(0xFFFFFFFF), c(0xFFE1E5E8), c(0xFFC8CED3),
    c(0xFF12171C), c(0xFF5D6872), c(0xFF7A848D), c(0xFF08695C), c(0xFFFFFFFF), c(0xFFB42318), c(0xFFD5DBDF),
    c(0xFFFFFFFF), c(0xFFE3E9EC), c(0xFF3DD9BC))

/** The palette on screen now. Set by [SportClockTheme] from the setting and the phone's own light/dark mode. */
object Themes {
    var current by mutableStateOf(NIGHT_DARK)
    fun pick(palette: String, dark: Boolean) = when (palette) { "paddock" -> if (dark) PADDOCK_DARK else PADDOCK_LIGHT; else -> if (dark) NIGHT_DARK else NIGHT_LIGHT }
}

/** The colour names every screen uses; each one reads the current palette, so switching repaints everything. */
object C {
    val bg get() = Themes.current.bg
    val tile get() = Themes.current.tile
    val tile2 get() = Themes.current.tile2
    val nav get() = Themes.current.nav
    val line get() = Themes.current.line
    val outline get() = Themes.current.outline
    val fg get() = Themes.current.fg
    val fg2 get() = Themes.current.fg2
    val fg3 get() = Themes.current.fg3
    /** The accent. Still called lime from when Night was the only look. */
    val lime get() = Themes.current.accent
    val onLime get() = Themes.current.onAccent
    val limeDim get() = Themes.current.accent.copy(alpha = .42f)
    val live get() = Themes.current.live
    val ringTrack get() = Themes.current.ringTrack
    val bright get() = Themes.current.bright
    val dark get() = Themes.current.dark
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))
val Barlow = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold), Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold))

/** Clock digits: Barlow Condensed, tabular so times line up down the list. */
val Digits = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

@Composable
fun SportClockTheme(palette: String = "night", mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = Themes.pick(palette, dark)
    if (Themes.current != p) Themes.current = p
    val base = Typography()
    val t = Typography(
        headlineSmall = base.headlineSmall.copy(fontFamily = Inter),
        titleLarge = base.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
        bodySmall = base.bodySmall.copy(fontFamily = Inter),
        labelLarge = base.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
    )
    MaterialTheme(
        colorScheme = if (p.dark) darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondaryContainer = p.accent, onSecondaryContainer = p.onAccent,
            background = p.bg, onBackground = p.fg, surface = p.bg, onSurface = p.fg, surfaceVariant = p.tile, onSurfaceVariant = p.fg2,
            surfaceContainer = p.nav, surfaceContainerLow = p.tile, surfaceContainerHigh = p.tile2, outline = p.outline, outlineVariant = p.line,
        ) else lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondaryContainer = p.accent, onSecondaryContainer = p.onAccent,
            background = p.bg, onBackground = p.fg, surface = p.bg, onSurface = p.fg, surfaceVariant = p.tile, onSurfaceVariant = p.fg2,
            surfaceContainer = p.nav, surfaceContainerLow = p.tile, surfaceContainerHigh = p.tile2, outline = p.outline, outlineVariant = p.line,
        ),
        typography = t, content = content,
    )
}
