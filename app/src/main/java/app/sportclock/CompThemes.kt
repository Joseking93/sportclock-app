package app.sportclock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * A look for each big competition: its own colours and a backdrop the app draws itself, in the spirit of the
 * competition's brand (the Champions League's star-ball, the Premier League's shards, LaLiga's sun...). Real logos stay
 * in the small logo chip as before; nothing here copies official artwork.
 *
 * A competition's page, its match sheets, the F1 tab and your tab (when it's a league) wear it. It works like the F1
 * flag colours: the palette itself is swapped while the page is on screen ([Themes.comps]), so every screen stays
 * readable, and the backdrop is drawn behind the content. A live flag wins over a competition theme.
 */

data class CompTheme(val key: String, val g0: Color, val g1: Color, val g2: Color, val accent: Color, val onAccent: Color,
                     val ink2: Color, val decal: String) {
    val palette = Palette(true, g1, g0, Color.White.copy(alpha = .08f), g0, Color.White.copy(alpha = .12f), Color.White.copy(alpha = .22f),
        Color.White, ink2, ink2.copy(alpha = .72f), accent, onAccent, Color(0xFFFF5C5C), Color.White.copy(alpha = .14f), g1, g0, accent)
}

private fun c(v: Long) = Color(v)
private val UCL_T = CompTheme("ucl", c(0xFF060A35), c(0xFF0B1660), c(0xFF2A1478), c(0xFF7FD8FF), c(0xFF0B1660), c(0xFFC8CFF5), "starball")
private val PL_T = CompTheme("pl", c(0xFF37003C), c(0xFF2A0030), c(0xFF1A001F), c(0xFF00FF85), c(0xFF37003C), c(0xFFE6C7EA), "shards")
private val LALIGA_T = CompTheme("laliga", c(0xFF1B1B1B), c(0xFF2A1210), c(0xFF3A1410), c(0xFFFF5A52), c(0xFFFFFFFF), c(0xFFF3C9C2), "sun")
private val BL_T = CompTheme("bl", c(0xFF141414), c(0xFF1D1D1D), c(0xFF2A0D0F), c(0xFFFF3B47), c(0xFFFFFFFF), c(0xFFD9D9D9), "stripes")
private val SA_T = CompTheme("sa", c(0xFF04163A), c(0xFF062A5A), c(0xFF05405C), c(0xFF3BE2C8), c(0xFF04163A), c(0xFFC2D6EE), "shield")
private val L1_T = CompTheme("l1", c(0xFF0A0F22), c(0xFF121B3C), c(0xFF16224A), c(0xFFDCFF4F), c(0xFF0A0F22), c(0xFFCDD5F0), "waves")
private val UEL_T = CompTheme("uel", c(0xFF110A04), c(0xFF1E1206), c(0xFF3A1D02), c(0xFFFF7A00), c(0xFF110A04), c(0xFFF5D6B5), "rings")
private val UECL_T = CompTheme("uecl", c(0xFF03140A), c(0xFF06200F), c(0xFF0A3A18), c(0xFF2FE36A), c(0xFF03140A), c(0xFFC6ECCF), "rings")
private val FAC_T = CompTheme("fac", c(0xFF0B1430), c(0xFF12204A), c(0xFF1B2A55), c(0xFFD8B45A), c(0xFF0B1430), c(0xFFD7DCF0), "laurel")
private val LC_T = CompTheme("lc", c(0xFF04170C), c(0xFF08261A), c(0xFF0D3A22), c(0xFF3DDC84), c(0xFF04170C), c(0xFFC9EBD6), "laurel")
private val F1_T = CompTheme("f1", c(0xFF0C0C0C), c(0xFF0E0E0E), c(0xFF121212), c(0xFFFF3B30), c(0xFFFFFFFF), c(0xFFD0D0D0), "glow")

/** Each competition's look; a domestic cup wears its country's league look. */
private val THEMES = mapOf("UCL" to UCL_T, "ENG" to PL_T, "ESP" to LALIGA_T, "CDR" to LALIGA_T, "GER" to BL_T, "DFB" to BL_T,
    "ITA" to SA_T, "CIT" to SA_T, "FRA" to L1_T, "CDF" to L1_T, "UEL" to UEL_T, "UECL" to UECL_T, "FAC" to FAC_T, "LC" to LC_T, "F1" to F1_T)

/** The look for a competition, or null when it has none or the reader turned the themes off. */
fun compTheme(code: String?): CompTheme? = if (Themes.compOn) THEMES[code] else null

internal fun themeFor(code: String): CompTheme? = THEMES[code]

/** Wears [code]'s colours while this is on screen; a sheet over a page hands the page's colours back when it closes. */
@Composable
fun CompetitionTheme(code: String?) {
    val p = compTheme(code)?.palette ?: return
    DisposableEffect(p) { Themes.comps.add(p); onDispose { Themes.comps.remove(p) } }
}

/** The competition's backdrop: its gradient and drawn pattern. Nothing while a live F1 flag has the screen. */
@Composable
fun CompBackdrop(code: String?, modifier: Modifier = Modifier) {
    val t = compTheme(code) ?: return
    if (Themes.flag != null) return
    val ctx = LocalContext.current
    val still = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    // the star-ball turns, the rings breathe; very slowly, and not at all when the phone asks for less motion
    val spin = if (still || t.decal !in setOf("starball", "rings")) null else rememberInfiniteTransition(label = "backdrop")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(if (t.decal == "starball") 120_000 else 8_000, easing = LinearEasing),
            if (t.decal == "starball") RepeatMode.Restart else RepeatMode.Reverse), label = "spin")
    Canvas(modifier) {
        drawRect(Brush.linearGradient(listOf(t.g0, t.g1, t.g2), Offset.Zero, Offset(size.width * .8f, size.height)))
        drawDecal(t.decal, spin?.value ?: 0f, t.accent)
    }
}

private fun starPath(cx: Float, cy: Float, r: Float, rot: Float) = Path().apply {
    for (i in 0 until 10) {
        val a = rot + i * PI.toFloat() / 5 - PI.toFloat() / 2; val rr = if (i % 2 == 1) r * .42f else r
        val x = cx + rr * cos(a); val y = cy + rr * sin(a)
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun DrawScope.glow(color: Color, center: Offset, r: Float, a: Float) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center, r), r, center)

/** The patterns, drawn on a 290-wide frame anchored at the top and scaled to the screen's width. */
private fun DrawScope.drawDecal(kind: String, t: Float, accent: Color) {
    val s = size.width / 290f
    withTransform({ scale(s, s, Offset.Zero) }) {
        when (kind) {
            "starball" -> {
                val ring = Brush.linearGradient(listOf(c(0xFF4FD8FF), c(0xFF6A5CFF), c(0xFFFF3FD8)), Offset(40f, 20f), Offset(380f, 360f))
                glow(c(0xFFFF3FD8), Offset(250f, 560f), 200f, .16f); glow(c(0xFF4FD8FF), Offset(0f, 120f), 170f, .12f)
                rotate(t * 360f, Offset(210f, 190f)) {
                    drawCircle(ring, 170f, Offset(210f, 190f), alpha = .36f, style = Stroke(1.4f))
                    listOf(Triple(210f, 70f, 58f) to .2f, Triple(110f, 170f, 52f) to .6f, Triple(310f, 170f, 52f) to -.3f,
                        Triple(150f, 300f, 55f) to .9f, Triple(275f, 305f, 55f) to .4f, Triple(210f, 190f, 40f) to 0f).forEach { (p, o) ->
                        drawPath(starPath(p.first, p.second, p.third, o), ring, alpha = .36f, style = Stroke(1.4f))
                    }
                }
            }
            "shards" -> {
                val cols = listOf(c(0xFF00FF85), c(0xFFE90052), c(0xFF04F5FF), c(0xFF963CFF))
                for (i in 0 until 9) {
                    val x = 150f + (i * 53) % 160; val y = 20f + i * 40
                    drawPath(Path().apply { moveTo(x, y); lineTo(x + 70, y + 30); lineTo(x + 20, y + 90); close() }, cols[i % 4], alpha = .10f + .04f * (i % 3))
                }
                drawPath(Path().apply { moveTo(200f, 0f); lineTo(290f, 0f); lineTo(290f, 140f); close() }, cols[0], alpha = .18f)
                drawPath(Path().apply { moveTo(290f, 470f); lineTo(290f, 600f); lineTo(170f, 600f); close() }, cols[1], alpha = .22f)
            }
            "sun" -> {
                val red = c(0xFFFF4B44); val o = Offset(250f, 60f)
                for (i in 0 until 18) {
                    val a = i * PI.toFloat() / 18 - .1f + PI.toFloat() / 2
                    drawLine(red, o, Offset(o.x + 700 * cos(a), o.y + 700 * sin(a)), strokeWidth = if (i % 2 == 1) 14f else 4f, alpha = .07f)
                }
                glow(red, o, 120f, .35f)
                drawCircle(c(0xFFFFB3A0), 110f, o, alpha = .25f, style = Stroke(1f))
            }
            "stripes" -> rotate(28f, Offset(145f, 300f)) {
                for (i in -8 until 16) drawRect(if (i % 5 == 0) c(0xFFD20515) else Color.White, Offset(i * 36f, -200f), Size(12f, 1100f), alpha = if (i % 5 == 0) .28f else .04f)
            }
            "shield" -> {
                val teal = c(0xFF3BE2C8)
                fun shield(k: Float) = Path().apply {
                    moveTo(220f, 30f + 30 * k); lineTo(340f - 25 * k, 70f + 32 * k); lineTo(332f - 24 * k, 220f + 5 * k)
                    quadraticTo(320f - 22 * k, 320f - 25 * k, 220f, 370f - 35 * k); quadraticTo(120f + 22 * k, 320f - 25 * k, 108f + 24 * k, 220f + 5 * k)
                    lineTo(100f + 25 * k, 70f + 32 * k); close()
                }
                drawPath(shield(0f), teal, alpha = .35f, style = Stroke(1.5f)); drawPath(shield(1f), teal, alpha = .06f)
                for (i in 0 until 6) drawLine(c(0xFFD6B25E), Offset(0f, 420f + i * 22), Offset(290f, 380f + i * 22), alpha = .12f)
            }
            "waves" -> for (i in 0 until 9) {
                val y = i * 24f
                drawPath(Path().apply { moveTo(-10f, 60 + y); cubicTo(80f, 10 + y, 160f, 110 + y, 300f, 40 + y) },
                    if (i == 4) c(0xFFDCFF4F) else Color.White, alpha = if (i == 4) .5f else .08f, style = Stroke(if (i == 4) 2f else 1f))
            }
            "rings" -> {
                glow(accent, Offset(240f, 120f), 150f, .18f)
                for (i in 1 until 8) {
                    val lit = i == 3                                    // one ring in the competition's colour, gently breathing
                    drawCircle(if (lit) accent else Color.White, i * 32f + if (lit) t * 6f else 0f, Offset(240f, 120f),
                        alpha = if (lit) .45f else .10f, style = Stroke(if (lit) 3f else 1f))
                }
            }
            "laurel" -> {
                val gold = c(0xFFD8B45A)
                for (i in 0 until 9) {
                    val a = -1.2f + i * .28f
                    listOf(1f, -1f).forEach { side ->
                        val x = 220f + side * 110 * cos(a); val y = 150f + 110 * sin(a)
                        rotate(side * (a * 57.3f + 90f), Offset(x, y)) { drawOval(gold, Offset(x - 16, y - 6), Size(32f, 12f), alpha = .22f) }
                    }
                }
                drawPath(Path().apply {
                    moveTo(200f, 95f); lineTo(240f, 95f); lineTo(240f, 115f); quadraticTo(240f, 145f, 220f, 153f); quadraticTo(200f, 145f, 200f, 115f); close()
                    moveTo(210f, 153f); lineTo(230f, 153f); lineTo(230f, 171f); lineTo(240f, 171f); lineTo(240f, 179f); lineTo(200f, 179f); lineTo(200f, 171f); lineTo(210f, 171f); close()
                }, gold, alpha = .18f)
            }
            // nothing to read around: one soft light from the corner, and the page stays black
            "glow" -> glow(accent, Offset(290f, 0f), 300f, .20f)
        }
    }
}
