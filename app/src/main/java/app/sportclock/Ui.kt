package app.sportclock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SportsMma
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/* ---------- brand ---------- */
/** The podium stopwatch, drawn from the app icon's own geometry (viewBox 18 15 68 70). */
@Composable
fun Logo(size: androidx.compose.ui.unit.Dp, color: Color = C.lime) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 68f
        translate(-18f * s, -15f * s) {
            drawCircle(color, 23.75f * s, Offset(50f * s, 55f * s), style = Stroke(6.5f * s))
            drawRoundRect(color, Offset(43.75f * s, 18.75f * s), Size(12.5f * s, 4.167f * s), CornerRadius(2.08f * s))
            drawRect(color, Offset(47f * s, 22.5f * s), Size(6f * s, 2.5f * s))
            rotate(45f, Offset(50f * s, 55f * s)) {
                drawRoundRect(color, Offset((50f - 5.5f) * s, (55f - 35f) * s), Size(11f * s, 5.5f * s), CornerRadius(2.5f * s))
            }
            val p = Path().apply {
                moveTo(33.333f * s, 60.417f * s); lineTo(33.333f * s, 50f * s); lineTo(43.75f * s, 50f * s); lineTo(43.75f * s, 39.583f * s)
                lineTo(56.25f * s, 39.583f * s); lineTo(56.25f * s, 54.167f * s); lineTo(66.667f * s, 54.167f * s); lineTo(66.667f * s, 60.417f * s); close()
            }
            drawPath(p, color)
        }
    }
}

@Composable
fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(30.dp)
        Spacer(Modifier.width(10.dp))
        Text("Sport", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.6).sp)
        Text("clock", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = (-0.6).sp)
    }
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 9.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/* ---------- filter pills ---------- */
@Composable
fun FilterPills(selected: Filter, onSelect: (Filter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter.entries.forEach { f ->
            val on = f == selected
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (on) C.lime else Color.Transparent)
                    .border(1.dp, if (on) C.lime else C.outline, RoundedCornerShape(20.dp))
                    .clickable { onSelect(f) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (f) { Filter.FOLLOWING -> Icons.Filled.Star; Filter.FOOTBALL -> Icons.Filled.SportsSoccer
                    Filter.MOTORSPORT -> Icons.Filled.SportsScore; Filter.FIGHT -> Icons.Filled.SportsMma; Filter.ALL -> null }
                if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = if (on) C.onLime else C.fg); Spacer(Modifier.width(8.dp)) }
                Text(f.label, color = if (on) C.onLime else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

/* ---------- the NEXT UP hero ---------- */
private val greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

private fun heroTexts(h: Hero, now: Instant): Triple<String, String, String> {
    val e = h.event
    val day = if (e.isTbc) (e.window?.let { "Date set · time TBC" } ?: "Time TBC") else dayWord(e.start, now)
    return when {
        h.token?.startsWith("t:") == true -> {
            val me = h.token.substring(2); val other = e.teams.firstOrNull { it != me } ?: e.title
            val homeAway = if (e.teams.firstOrNull() == me) "v" else "at"
            Triple(short(me), "$homeAway ${short(other)}", "${series(e.code)?.name ?: e.comp} · $day")
        }
        e.sport == Sport.FOOTBALL -> Triple(series(e.code)?.name ?: e.comp, e.line1(), day)
        e.code == "UFC" || e.code == "BOX" -> Triple(e.comp.ifEmpty { series(e.code)?.name ?: e.code }, sessName(e.session), "${e.title} · $day")
        else -> Triple(series(e.code)?.name ?: e.comp, sessName(e.session), "${e.line2()} · $day")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCard(h: Hero, now: Instant, pinned: Boolean, onPin: () -> Unit, onOpen: () -> Unit) {
    val e = h.event
    val (series, sess, meta) = heroTexts(h, now)
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 204.dp).clip(shape)
            .background(Brush.radialGradient(listOf(Color(0xFF171C0D), Color(0xFF0B0D09)), center = Offset(0f, 600f), radius = 900f))
            .border(1.dp, C.limeDim, shape)
            .combinedClickable(onClick = onOpen, onLongClick = onPin)
    ) {
        h.person?.let { p -> Box(Modifier.matchParentSize()) {
            Image(
                painterResource(p.photo), null,
                contentScale = ContentScale.Crop, alignment = BiasAlignment(p.fx * 2 - 1, p.fy * 2 - 1),
                colorFilter = if (p.kind == Kind.FIGHTER) greyscale else null,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.68f)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.25f to Color.Black.copy(alpha = .4f), 0.55f to Color.Black), blendMode = BlendMode.DstIn)
                    },
            )
        } }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0xE60B0D09), 0.38f to Color(0xA80B0D09), 0.6f to Color(0x260B0D09), 0.7f to Color.Transparent)))

        // the card grows with its text (large font settings), so the time is never clipped
        Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) { Icon(Icons.Filled.PushPin, null, Modifier.size(13.dp), tint = C.lime); Spacer(Modifier.width(4.dp)) }
                Text(if (pinned) "PINNED" else "NEXT UP", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.4.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoChip(e.code, 26.dp); Spacer(Modifier.width(10.dp))
                Text(series, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(sess, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
            Text(timeText(e), style = Digits, color = C.lime, fontWeight = FontWeight.ExtraBold, fontSize = 80.sp, lineHeight = 80.sp,
                modifier = Modifier.offset(x = (-3).dp))
        }

        Row(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            h.person?.let { p ->
                Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xC70A0C08)).border(1.dp, Color(0x66C8F53C), RoundedCornerShape(14.dp))
                    .padding(start = 3.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(p.avatar), null, contentScale = ContentScale.Crop, alignment = BiasAlignment(p.fx * 2 - 1, p.fy * 2 - 1),
                        modifier = Modifier.size(22.dp).clip(CircleShape))
                    Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = C.lime); Spacer(Modifier.width(3.dp))
                    Text(p.name.substringAfterLast(' '), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            if (h.token != null) Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xC70A0C08)).combinedClickable(onClick = onPin), contentAlignment = Alignment.Center) {
                Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (pinned) "Unpin" else "Pin to top", Modifier.size(17.dp), tint = if (pinned) C.lime else C.fg2)
            }
        }
        if (!e.isTbc) Ring(e.start, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/** Live countdown ring: ticks every second (2:41:37, then 41:37 in the last hour) and fills over the last two hours. */
@Composable
fun Ring(start: Instant, modifier: Modifier = Modifier) {
    var t by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(start) { while (true) { t = Instant.now(); kotlinx.coroutines.delay(1000L - System.currentTimeMillis() % 1000L) } }
    val secs = Duration.between(t, start).seconds.coerceAtLeast(0)
    val frac = (1f - secs / 7200f).coerceIn(0f, 1f)
    Box(modifier.size(88.dp).clip(CircleShape).background(Color(0xB30A0C08)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().padding(3.dp)) {
            drawCircle(C.ringTrack, style = Stroke(6.dp.toPx()))
            if (frac > 0f) drawArc(C.lime, -90f, 360f * frac, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(countdown(secs), style = Digits, color = C.fg, fontSize = if (secs >= 36000) 19.sp else 22.sp, lineHeight = 24.sp, maxLines = 1)
            Text(if (secs == 0L) "starting" else "to go", color = Color(0xFFC2C5B9), fontFamily = Inter, fontSize = 12.sp, lineHeight = 14.sp)
        }
    }
}

/** 41:37 under an hour, 2:41:37 under 4 days, otherwise whole days. */
fun countdown(secs: Long): String {
    val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
    return when {
        secs >= 96 * 3600 -> "${secs / 86400}d"
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%02d:%02d".format(m, s)
    }
}

@Composable
fun HeroPager(heroes: List<Hero>, now: Instant, pinned: String?, onPin: (Hero) -> Unit, onOpen: (Event) -> Unit, onPage: (Hero) -> Unit) {
    if (heroes.isEmpty()) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(20.dp)).background(C.tile), contentAlignment = Alignment.Center) {
            Text("Nothing coming up for what you follow", color = C.fg2, fontFamily = Inter, fontSize = 15.sp)
        }
        return
    }
    val state = rememberPagerState(pageCount = { heroes.size })
    LaunchedEffect(heroes.firstOrNull()?.token, heroes.size) { state.scrollToPage(0) }
    LaunchedEffect(state.currentPage, heroes) { heroes.getOrNull(state.currentPage)?.let(onPage) }
    HorizontalPager(state, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 10.dp) { i ->
        val h = heroes[i]
        HeroCard(h, now, pinned != null && h.token == pinned, { onPin(h) }, { onOpen(h.event) })
    }
    if (heroes.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            heroes.indices.forEach { i ->
                val on = i == state.currentPage
                Box(Modifier.padding(horizontal = 3.dp).size(if (on) 18.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (on) C.lime else C.outline))
            }
        }
    }
}

/* ---------- rows ---------- */
@Composable
fun DayHeader(d: LocalDate, today: LocalDate) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(dayTitle(d, today), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.4).sp)
        Spacer(Modifier.width(10.dp))
        Text(if (d == today || d == today.plusDays(1)) daySub(d) else daySub(d).substringAfter(' '), color = C.fg2, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
fun PillView(p: Pill) {
    val (text, fg, border, bg) = when (p) {
        Pill.Live -> listOf("LIVE", C.live, Color(0xFFE5484D), Color(0x1AFF5C5C))
        Pill.OnNow -> listOf("ON NOW", C.fg, C.outline, Color.Transparent)
        Pill.Moved -> listOf("Moved", C.fg, Color.Transparent, C.tile2)
        is Pill.In -> listOf(p.text, C.fg, C.outline, Color.Transparent)
    }
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg as Color).border(1.dp, border as Color, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (p == Pill.Live) { Dot(C.live, 7.dp); Spacer(Modifier.width(6.dp)) }
        Text(text as String, color = fg as Color, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = if (p is Pill.In || p == Pill.Moved) 0.sp else 0.6.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventRow(e: Event, now: Instant, score: Score? = null, highlight: Boolean = false, onClick: () -> Unit = {}) {
    val showScore = score != null && score.home != null && score.away != null && score.status in setOf("IN_PLAY", "PAUSED", "FINISHED")
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(C.tile)
            .combinedClickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (showScore) "${score!!.home}–${score.away}" else timeText(e), style = Digits, fontSize = 24.sp, color = if (highlight) C.lime else C.fg)
            val cue = if (showScore) when (score!!.status) { "FINISHED" -> "FT"; "PAUSED" -> "HT"; else -> score.detail.takeIf { it.isNotEmpty() && it.length <= 7 } ?: "Live" } else tonightCue(e, now)
            cue?.let { Text(it, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp) }
        }
        Box(Modifier.width(1.dp).height(36.dp).background(C.line))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoChip(e.code, 20.dp); Spacer(Modifier.width(8.dp))
                Text(e.line1(), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(e.line2(), color = C.fg2, fontFamily = Inter, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp))
        }
        val pill = if (showScore && score!!.status == "FINISHED") null else pillFor(e, now)
        pill?.let { Box(Modifier.padding(end = 12.dp)) { PillView(it) } }
    }
}

/** Events grouped under day headers, in the phone's own zone. */
fun androidx.compose.foundation.lazy.LazyListScope.dayGroups(events: List<Event>, now: Instant, scores: Map<String, Score>, onOpen: (Event) -> Unit) {
    val today = localDate(now)
    events.groupBy { localDate(it.window?.first?.takeIf { _ -> it.isTbc } ?: it.start) }.toSortedMap().forEach { (d, list) ->
        item(key = "h$d") { DayHeader(d, today) }
        list.forEach { e ->
            item(key = e.id) {
                EventRow(e, now, scores[e.id], onClick = { onOpen(e) })
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
