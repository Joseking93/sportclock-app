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
        val photo = hero?.person?.let { runCatching { crop(context, it) }.getOrNull() }
        provideContent { Content(hero, next, photo, now) }
    }

    /** A small, pre-cropped copy of the hero photo (greyscale for fighters) that fits a widget's memory limit. */
    private fun crop(ctx: Context, p: Person): Bitmap {
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val src = BitmapFactory.decodeResource(ctx.resources, p.photo, opts)
        val side = minOf(src.width, src.height)
        val cx = (p.fx * src.width).toInt().coerceIn(side / 2, src.width - side / 2)
        val cy = (p.fy * src.height).toInt().coerceIn(side / 2, src.height - side / 2)
        val out = Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (p.kind == Kind.FIGHTER) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        Canvas(out).drawBitmap(src, Rect(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2), Rect(0, 0, 420, 420), paint)
        src.recycle()
        return out
    }

    @Composable
    private fun Content(hero: Hero?, next: List<Event>, photo: Bitmap?, now: Instant) {
        val wide = LocalSize.current.width >= 250.dp
        val lime = ColorProvider(Color(0xFFC8F53C)); val fg = ColorProvider(Color(0xFFECEEE6)); val fg2 = ColorProvider(Color(0xFFA6A99E))
        Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Color(0xFF0E100B)).clickable(actionStartActivity<MainActivity>())) {
            if (photo != null) {
                Row(GlanceModifier.fillMaxSize()) {
                    Spacer(GlanceModifier.defaultWeight())
                    Image(ImageProvider(photo), null, contentScale = androidx.glance.layout.ContentScale.Crop,
                        modifier = GlanceModifier.fillMaxHeight().width(if (wide) 170.dp else 120.dp))
                }
                Image(ImageProvider(R.drawable.widget_scrim), null, contentScale = androidx.glance.layout.ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
            }
            Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
                Text("NEXT UP", style = TextStyle(color = lime, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                if (hero == null) {
                    Text("Nothing coming up", style = TextStyle(color = fg2, fontSize = 13.sp))
                } else {
                    val e = hero.event
                    val head = if (hero.token?.startsWith("t:") == true) e.line1() else "${tokenLabel(hero.token ?: "c:${e.code}").let { if (e.code == "UFC") e.comp else it }} · ${sessName(e.session)}"
                    Text(head, maxLines = if (wide) 1 else 2, style = TextStyle(color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(timeText(e), style = TextStyle(color = lime, fontSize = if (wide) 44.sp else 38.sp, fontWeight = FontWeight.Bold))
                    Text(if (e.isTbc) "Date set · time TBC" else dayWord(e.start, now), style = TextStyle(color = fg2, fontSize = 12.sp))
                    if (wide) {
                        Spacer(GlanceModifier.defaultWeight())
                        next.forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(timeText(n), style = TextStyle(color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.width(52.dp))
                                Text(n.line1(), maxLines = 1, style = TextStyle(color = fg, fontSize = 12.sp))
                                Spacer(GlanceModifier.defaultWeight())
                                Text(dayWord(n.start, now), style = TextStyle(color = fg2, fontSize = 11.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}
