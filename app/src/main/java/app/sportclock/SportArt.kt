package app.sportclock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * Drawn art for the NEXT UP card and the widget when there is no photo or badge to show: the UFC octagon, a boxing
 * ring, a NASCAR tri-oval, an IndyCar oval, and a trophy for MotoGP and anything else. One set of drawing code serves
 * the app (a Canvas) and the widget (a bitmap via CanvasDrawScope). Colours come from the theme's bright accent.
 */

/** The scene for this series, drawn in the right-hand part of the area, centred where a photo's subject would be. */
fun DrawScope.sportArt(code: String, accent: Color, ink: Color) {
    val cx = size.width * 0.74f; val cy = size.height * 0.5f
    val r = min(size.width * 0.24f, size.height * 0.38f)
    val glow = { w: Float -> Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    when (code) {
        "UFC" -> octagon(cx, cy, r, accent, ink, glow)
        "BOX" -> ring(cx, cy, r, accent, ink, glow)
        "NAS" -> oval(cx, cy, r, accent, ink, glow, triOval = true)
        "INDY" -> oval(cx, cy, r, accent, ink, glow, triOval = false)
        else -> trophy(cx, cy, r, accent, ink, glow)
    }
}

private fun poly(cx: Float, cy: Float, r: Float, n: Int, turn: Double) = Path().apply {
    for (i in 0 until n) {
        val a = turn + 2 * PI * i / n
        val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** The octagon from above: canvas floor, chain-link fence, eight padded posts and the centre mark. */
private fun DrawScope.octagon(cx: Float, cy: Float, r: Float, accent: Color, ink: Color, glow: (Float) -> Stroke) {
    val turn = PI / 8
    val outer = poly(cx, cy, r, 8, turn); val inner = poly(cx, cy, r * 0.9f, 8, turn)
    drawPath(outer, ink.copy(alpha = .35f))
    clipPath(inner) {
        val step = r / 7
        var t = -2 * r
        while (t < 2 * r) {                                             // the fence mesh: two sets of diagonals
            drawLine(accent.copy(alpha = .10f), Offset(cx + t - r, cy - r), Offset(cx + t + r, cy + r), 1.5f)
            drawLine(accent.copy(alpha = .10f), Offset(cx + t + r, cy - r), Offset(cx + t - r, cy + r), 1.5f)
            t += step
        }
    }
    drawPath(outer, accent.copy(alpha = .14f), style = glow(r * 0.16f))
    drawPath(outer, accent, style = glow(r * 0.035f))
    drawPath(inner, accent.copy(alpha = .55f), style = glow(r * 0.015f))
    for (i in 0 until 8) {
        val a = turn + 2 * PI * i / 8
        drawCircle(accent, r * 0.045f, Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()))
    }
    drawCircle(accent.copy(alpha = .35f), r * 0.22f, Offset(cx, cy), style = glow(r * 0.02f))
    drawCircle(accent.copy(alpha = .18f), r * 0.07f, Offset(cx, cy))
}

/** A boxing ring from above, turned on its corner: canvas, three ropes, red and blue corners and two neutral ones. */
private fun DrawScope.ring(cx: Float, cy: Float, r: Float, accent: Color, ink: Color, glow: (Float) -> Stroke) {
    val diamond = { k: Float -> poly(cx, cy, r * k, 4, -PI / 2) }
    drawPath(diamond(1.02f), ink.copy(alpha = .35f))
    drawPath(diamond(0.86f), accent.copy(alpha = .08f))
    listOf(1.0f, 0.93f, 0.86f).forEachIndexed { i, k ->
        drawPath(diamond(k), if (i == 1) accent else accent.copy(alpha = .6f), style = glow(r * (if (i == 1) 0.03f else 0.018f)))
    }
    drawPath(diamond(1.0f), accent.copy(alpha = .12f), style = glow(r * 0.14f))
    val corners = listOf(Color(0xFFE5484D), Color(0xFFF2F2F2), Color(0xFF3E7BFA), Color(0xFFF2F2F2))
    for (i in 0 until 4) {
        val a = -PI / 2 + PI / 2 * i
        val o = Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        drawCircle(corners[i], r * 0.07f, o)
        drawCircle(ink.copy(alpha = .5f), r * 0.07f, o, style = Stroke(r * 0.012f))
    }
    drawCircle(accent.copy(alpha = .25f), r * 0.16f, Offset(cx, cy), style = glow(r * 0.015f))
}

/** An oval (IndyCar) or a tri-oval with its kinked front stretch (NASCAR): tarmac band, racing line, chequered start. */
private fun DrawScope.oval(cx: Float, cy: Float, r: Float, accent: Color, ink: Color, glow: (Float) -> Stroke, triOval: Boolean) {
    val w = r * 1.3f; val h = r * 0.72f
    val path = Path().apply {
        val rad = h
        moveTo(cx - w + rad, cy - h)
        lineTo(cx + w - rad, cy - h)
        arcTo(androidx.compose.ui.geometry.Rect(cx + w - 2 * rad, cy - h, cx + w, cy + h), -90f, 180f, false)
        if (triOval) { lineTo(cx, cy + h * 1.22f); lineTo(cx - w + rad, cy + h) }       // the dog-leg of a tri-oval
        else lineTo(cx - w + rad, cy + h)
        arcTo(androidx.compose.ui.geometry.Rect(cx - w, cy - h, cx - w + 2 * rad, cy + h), 90f, 180f, false)
        close()
    }
    drawPath(path, accent.copy(alpha = .10f), style = glow(r * 0.42f))
    drawPath(path, Color(0xFF4A4E44), style = glow(r * 0.26f))
    drawPath(path, Color(0xFF22251F), style = glow(r * 0.21f))
    drawPath(path, accent, style = glow(r * 0.035f))
    // start/finish on the front stretch
    val sx = cx; val sy = if (triOval) cy + h * 1.22f else cy + h
    val sq = r * 0.035f
    for (i in -3 until 3) for (j in 0 until 2) {
        drawRect(if ((i + j) % 2 == 0) Color.White else Color.Black, Offset(sx - sq + j * sq, sy + i * sq), Size(sq, sq))
    }
    drawCircle(accent.copy(alpha = .18f), r * 0.1f, Offset(cx, cy - h * 0.1f))
}

/** A trophy: cup, handles, stem and plinth, with a few speed lines behind it. */
private fun DrawScope.trophy(cx: Float, cy: Float, r: Float, accent: Color, ink: Color, glow: (Float) -> Stroke) {
    for (i in 0 until 4) {                                              // speed lines
        val y = cy - r * 0.5f + i * r * 0.32f
        drawLine(accent.copy(alpha = .14f + i * .03f), Offset(cx - r * 2.0f, y), Offset(cx - r * (0.9f - i * 0.08f), y), r * 0.03f, StrokeCap.Round)
    }
    val top = cy - r * 0.78f; val cupW = r * 0.62f
    val cup = Path().apply {
        moveTo(cx - cupW, top)
        lineTo(cx + cupW, top)
        cubicTo(cx + cupW, top + r * 0.75f, cx + r * 0.25f, top + r * 0.95f, cx + r * 0.12f, top + r * 1.02f)
        lineTo(cx - r * 0.12f, top + r * 1.02f)
        cubicTo(cx - r * 0.25f, top + r * 0.95f, cx - cupW, top + r * 0.75f, cx - cupW, top)
        close()
    }
    drawPath(cup, Brush.verticalGradient(listOf(accent.copy(alpha = .55f), accent.copy(alpha = .18f)), top, top + r))
    drawPath(cup, accent.copy(alpha = .14f), style = glow(r * 0.14f))
    drawPath(cup, accent, style = glow(r * 0.035f))
    listOf(-1f, 1f).forEach { s ->                                      // handles
        drawArc(accent, if (s < 0) 90f else -90f, 180f, false, Offset(cx + s * cupW - r * 0.24f, top + r * 0.08f), Size(r * 0.48f, r * 0.5f),
            style = glow(r * 0.035f))
    }
    drawRect(accent, Offset(cx - r * 0.07f, top + r * 1.02f), Size(r * 0.14f, r * 0.3f))
    drawRoundRect(accent, Offset(cx - r * 0.42f, top + r * 1.32f), Size(r * 0.84f, r * 0.13f), androidx.compose.ui.geometry.CornerRadius(r * 0.04f))
    drawRoundRect(ink.copy(alpha = .5f), Offset(cx - r * 0.55f, top + r * 1.45f), Size(r * 1.1f, r * 0.17f), androidx.compose.ui.geometry.CornerRadius(r * 0.04f))
    drawRoundRect(accent.copy(alpha = .6f), Offset(cx - r * 0.55f, top + r * 1.45f), Size(r * 1.1f, r * 0.17f), androidx.compose.ui.geometry.CornerRadius(r * 0.04f),
        style = Stroke(r * 0.02f))
    drawCircle(Color.White.copy(alpha = .5f), r * 0.05f, Offset(cx - cupW * 0.45f, top + r * 0.22f))   // a highlight on the cup
}

/** Free-licensed photos from Wikimedia Commons (credited in About), used when card art is set to Photos. */
fun sportPhoto(code: String): Int? = when (code) {
    "MGP" -> R.drawable.art_mgp; "NAS" -> R.drawable.art_nas; "INDY" -> R.drawable.art_indy; "UFC" -> R.drawable.art_ufc; "BOX" -> R.drawable.art_box
    else -> null
}

/** The card art for a sport: its photo when photos are chosen and there is one, otherwise its drawn scene. */
@Composable
fun SportCardArt(code: String, modifier: Modifier = Modifier) {
    val photo = sportPhoto(code).takeIf { Themes.cardArt == "photos" }
    if (photo == null) SportArtBackdrop(code, modifier)
    else androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(photo), null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.3f to Color.Black.copy(alpha = .45f), 0.6f to Color.Black), blendMode = BlendMode.DstIn)
            })
}

/** The art behind the NEXT UP card, faded in from the left like the photos. */
@Composable
fun SportArtBackdrop(code: String, modifier: Modifier = Modifier) {
    val accent = Themes.current.bright; val ink = Color(0xFF0A0C08)
    Canvas(modifier
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.32f to Color.Black.copy(alpha = .5f), 0.55f to Color.Black), blendMode = BlendMode.DstIn)
        }) { sportArt(code, accent, ink) }
}

/** The same art as a bitmap for the widget, with the fade baked in like the photo version. */
fun sportArtBitmap(code: String, w: Int, h: Int, from: Float, to: Float, maxAlpha: Float, accent: Color): android.graphics.Bitmap {
    val img = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(2.5f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
        sportArt(code, accent, Color(0xFF0A0C08))
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = maxAlpha), startX = w * from, endX = w * to), blendMode = BlendMode.DstIn)
    }
    return img.asAndroidBitmap()
}
