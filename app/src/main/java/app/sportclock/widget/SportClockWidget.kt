package app.sportclock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.sportclock.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class SportClockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SportClockWidget()
}

/** NEXT UP on the home screen. It shows the start time and day, not a countdown: a widget only redraws every half hour or so. */
class SportClockWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { Repo.load(context, maxAgeMin = 90) }.getOrNull()
        val now = Instant.now()
        val follows = Repo.follows(context)
        val favs = Kind.entries.associateWith { Repo.fav(context, it) }
        val all = data?.events.orEmpty()
        val hero = heroes(all, follows, Repo.pinned(context), Filter.FOLLOWING, favs, now).firstOrNull()
        val next = all.filter { followed(it, follows) && !it.started(now) && !it.over(now) && it.id != hero?.event?.id }.take(2)
        // while a watched match is on (and spoiler-free is off), the widget shows its live score instead of the next fixture
        AlertChoices.load(context)
        val live = if (Settings.spoilerFree(context)) null else all.firstOrNull { it.inProgress(now) && Live.watched(it, follows) }
            ?.let { ev -> Live.score(context, ev)?.takeIf { it.third == "in" }?.let { ev to it } }
        // as on the app's card: a football player only when he is in one of the two squads, otherwise both clubs' badges
        val e = live?.first ?: hero?.event
        val look = if (e != null && (e.sport == Sport.FOOTBALL || e.sport == Sport.NFL) && e.teams.size == 2) Looks.load(e) else null
        val candidate = if (live != null && e?.id != hero?.event?.id) favs[Kind.PLAYER] else hero?.person
        val person = candidate?.takeIf { (e?.sport != Sport.FOOTBALL && e?.sport != Sport.NFL) || (look != null && personKey(it.name) in look.squad) }
        // Glance calls this on the main thread, where Android refuses downloads: the badges are fetched and drawn on IO
        val (wideBmp, squareBmp) = withContext(Dispatchers.IO) {
            runCatching { person?.let { faded(context, it, 1080, 480, 0.22f, 0.72f, 1f) } ?: look?.let { badges(context, it, 1080, 480, 0.30f, 0.62f, 1f) } }
                .onFailure { Diag.log("widget art: $it") }.getOrNull() to
            runCatching { person?.let { faded(context, it, 600, 600, 0.0f, 0.95f, 0.75f) } ?: look?.let { badges(context, it, 600, 600, 0.05f, 0.75f, 0.7f) } }
                .onFailure { Diag.log("widget art: $it") }.getOrNull()
        }
        Alerts.scheduleWidget(context, hero?.event?.takeIf { !it.isTbc }?.start)   // redraw when the wording next changes
        provideContent { Content(hero, next, wideBmp, squareBmp, now, live) }
    }

    /**
     * The hero photo for the whole widget, with the fade baked into the bitmap: fully transparent at [from] of the width,
     * fully opaque at [to], so it melts into the widget's background with no visible edge, as the card in the app does.
     * Decoded at full size and drawn at about the widget's own pixel size, so it stays sharp; greyscale for fighters.
     */
    private fun faded(ctx: Context, p: Person, w: Int, h: Int, from: Float, to: Float, maxAlpha: Float): Bitmap {
        val src = BitmapFactory.decodeResource(ctx.resources, p.photo)
        // largest crop of the target shape that keeps the focal point (face, cockpit) in frame
        val scale = minOf(src.width.toFloat() / w, src.height.toFloat() / h)
        val cw = (w * scale).toInt(); val ch = (h * scale).toInt()
        val left = ((p.fx * src.width) - cw * 0.62f).toInt().coerceIn(0, src.width - cw)
        val top = ((p.fy * src.height) - ch / 2f).toInt().coerceIn(0, src.height - ch)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (p.kind == Kind.FIGHTER) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        c.drawBitmap(src, Rect(left, top, left + cw, top + ch), Rect(0, 0, w, h), paint)
        src.recycle()
        val mask = Paint().apply {
            shader = android.graphics.LinearGradient(w * from, 0f, w * to, 0f,
                intArrayOf(0x00000000, (((maxAlpha * 0.45f) * 255).toInt() shl 24), ((maxAlpha * 255).toInt() shl 24)),
                floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        // a soft darkening towards the bottom, only over the photo itself, so the "next two" rows stay readable
        val shade = Paint().apply {
            shader = android.graphics.LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), 0x000E100B, 0xB30E100B.toInt(), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)
        return out
    }

    /**
     * Both clubs half and half for a match with no favourite player in it, faded in from the left like the photo.
     * Badges come from ESPN and are kept in the cache folder, so a redraw does not download them again.
     */
    private fun badges(ctx: Context, look: MatchLook, w: Int, h: Int, from: Float, to: Float, maxAlpha: Float): Bitmap {
        val (hc, ac) = sideColors(look.home, look.away)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val top = w * 0.80f; val bottom = w * 0.70f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = hc.copy(alpha = .62f).toArgb()
        c.drawPath(android.graphics.Path().apply { moveTo(0f, 0f); lineTo(top, 0f); lineTo(bottom, h.toFloat()); lineTo(0f, h.toFloat()); close() }, fill)
        fill.color = ac.copy(alpha = .62f).toArgb()
        c.drawPath(android.graphics.Path().apply { moveTo(top, 0f); lineTo(w.toFloat(), 0f); lineTo(w.toFloat(), h.toFloat()); lineTo(bottom, h.toFloat()); close() }, fill)
        val size = (minOf(w, h) * 0.30f).toInt(); val y = (h * 0.12f).toInt()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(look.home to (w * 0.66f), look.away to (w * 0.89f)).forEach { (side, cx) ->
            badge(ctx, side)?.let { b -> c.drawBitmap(b, null, Rect((cx - size / 2).toInt(), y, (cx + size / 2).toInt(), y + size), paint); b.recycle() }
        }
        val mask = Paint().apply {
            shader = android.graphics.LinearGradient(w * from, 0f, w * to, 0f, 0x00000000, ((maxAlpha * 255).toInt() shl 24), android.graphics.Shader.TileMode.CLAMP)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), mask)
        return out
    }

    private fun badge(ctx: Context, side: TeamSide): Bitmap? = runCatching {
        val f = java.io.File(java.io.File(ctx.cacheDir, "badges").apply { mkdirs() }, "${side.id}.png")
        if (!f.exists() || f.length() == 0L) {
            // into a temporary file first, so a cut-off download never leaves a broken badge behind
            val tmp = java.io.File(f.path + ".part")
            val conn = java.net.URL(side.logo).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            try { conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } } finally { conn.disconnect() }
            if (!tmp.renameTo(f)) tmp.delete()
        }
        BitmapFactory.decodeFile(f.path) ?: run { f.delete(); null }
    }.onFailure { Diag.log("widget badge ${side.name}: $it") }.getOrNull()

    @Composable
    private fun Content(hero: Hero?, next: List<Event>, wideBmp: Bitmap?, squareBmp: Bitmap?, now: Instant, live: Pair<Event, Triple<String, String, String>>? = null) {
        val wide = LocalSize.current.width >= 250.dp
        val lime = ColorProvider(Color(0xFFC8F53C)); val fg = ColorProvider(Color(0xFFECEEE6)); val fg2 = ColorProvider(Color(0xFFA6A99E))
        Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Color(0xFF0E100B)).clickable(actionStartActivity<MainActivity>())) {
            (if (wide) wideBmp else squareBmp)?.let { bmp ->
                Image(ImageProvider(bmp), null, contentScale = androidx.glance.layout.ContentScale.Crop, modifier = GlanceModifier.fillMaxSize())
            }
            Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                if (live != null) {
                    val (ev, sc) = live
                    Text("LIVE · ${sc.second}", style = TextStyle(color = lime, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text(ev.line1(), maxLines = if (wide) 1 else 2, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(sc.first, style = TextStyle(color = lime, fontSize = if (wide) 44.sp else 38.sp, fontWeight = FontWeight.Bold))
                    Text("${short(ev.teams.getOrElse(0) { "" })} v ${short(ev.teams.getOrElse(1) { "" })} · scores can lag", maxLines = 1, style = TextStyle(color = fg2, fontSize = 12.sp))
                } else if (hero == null) {
                    Text("NEXT UP", style = TextStyle(color = lime, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text("Nothing coming up", style = TextStyle(color = fg2, fontSize = 13.sp))
                } else {
                    Text("NEXT UP", style = TextStyle(color = lime, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    val e = hero.event
                    val head = if (hero.token?.startsWith("t:") == true) e.line1() else "${tokenLabel(hero.token ?: "c:${e.code}").let { if (e.code == "UFC") e.comp else it }} · ${sessName(e.session)}"
                    Text(head, maxLines = if (wide) 1 else 2, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(timeText(e), style = TextStyle(color = lime, fontSize = if (wide) 44.sp else 38.sp, fontWeight = FontWeight.Bold))
                    Text(if (e.isTbc) "Date set · time TBC" else "${dayWord(e.start, now)} · ${roughUntil(e, now)}", maxLines = 1, style = TextStyle(color = fg2, fontSize = 12.sp))
                    if (wide) {
                        Spacer(GlanceModifier.defaultWeight())
                        next.forEach { n ->
                            Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(timeText(n), style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.width(54.dp))
                                Text(n.line1(), maxLines = 1, style = TextStyle(color = fg, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
                                Text(dayWord(n.start, now), style = TextStyle(color = fg2, fontSize = 11.sp), modifier = GlanceModifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * How long to go. The widget redraws every 5 minutes in the last hour (see Alerts.scheduleWidget), so under an hour
     * it can say "in about 15 min"; further out it rounds to what stays true until the next redraw.
     */
    private fun roughUntil(e: Event, now: Instant): String {
        if (e.inProgress(now)) return "on now"
        val m = java.time.Duration.between(now, e.start).toMinutes()
        return when {
            m <= 2 -> "starting now"
            m < 60 -> "in about ${((m + 2) / 5 * 5).coerceAtLeast(5)} min"
            m < 180 -> "in about ${untilText((m + 7) / 15 * 15)}"
            m < 48 * 60 -> "in ~${(m + 30) / 60}h"
            else -> "in ${m / 1440} days"
        }
    }
}
