package app.sportclock

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* Night palette, the same tokens as the mock-ups (jose/sc.css) */
object C {
    val bg = Color(0xFF0A0C08)
    val tile = Color(0xFF151712)
    val tile2 = Color(0xFF1C1E19)
    val nav = Color(0xFF12140F)
    val line = Color(0xFF2A2D26)
    val outline = Color(0xFF383B33)
    val fg = Color(0xFFECEEE6)
    val fg2 = Color(0xFFA6A99E)
    val fg3 = Color(0xFF868980)
    val lime = Color(0xFFC8F53C)
    val onLime = Color(0xFF192300)
    val limeDim = Color(0x6BC8F53C)
    val live = Color(0xFFFF5C5C)
    val ringTrack = Color(0xFF2A2E22)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))
val Barlow = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold), Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold))

/** Clock digits: Barlow Condensed, tabular so times line up down the list. */
val Digits = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

@Composable
fun SportClockTheme(content: @Composable () -> Unit) {
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
        colorScheme = darkColorScheme(
            primary = C.lime, onPrimary = C.onLime, secondaryContainer = C.lime, onSecondaryContainer = C.onLime,
            background = C.bg, onBackground = C.fg, surface = C.bg, onSurface = C.fg, surfaceVariant = C.tile, onSurfaceVariant = C.fg2,
            surfaceContainer = C.nav, outline = C.outline, outlineVariant = C.line,
        ),
        typography = t, content = content,
    )
}
