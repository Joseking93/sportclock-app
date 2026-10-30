package app.sportclock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.sin

internal val GREEN = Color(0xFF2E7D32); internal val YELLOW = Color(0xFFF9A825); internal val ORANGE = Color(0xFFFB8C00); internal val RED = Color(0xFFD32F2F)
internal fun trackColor(code: String) = when (code) { "2" -> YELLOW; "4", "6", "7" -> ORANGE; "5" -> RED; else -> GREEN }
internal fun teamColor(hex: String) = hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: C.fg3
internal fun tyreColor(t: String) = when (t.uppercase()) { "SOFT" -> Color(0xFFE53935); "MEDIUM" -> Color(0xFFFDD835); "HARD" -> Color(0xFFEEEEEE)
    "INTERMEDIATE" -> Color(0xFF43A047); "WET" -> Color(0xFF1E88E5); else -> C.fg3 }
internal fun utcHm(utc: String) = runCatching { hm(LocalDateTime.parse(utc.removeSuffix("Z")).toInstant(ZoneOffset.UTC)) }.getOrDefault("")

/**
 * F1 Live: the live or latest session from F1's own timing feed. While on screen it takes a fresh snapshot every
 * 15 seconds during a session and every minute otherwise.
 */
@Composable
fun F1LiveView() {
    var snap by remember { mutableStateOf<F1Snapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { f1Snapshot() }.onSuccess { snap = it; failed = false }.onFailure { if (it is CancellationException) throw it; failed = snap == null; Diag.log("f1 live: $it") }
            delay(if (snap?.live == true) 15_000 else 60_000)
        }
    }
    val s = snap
    FlagColours(s)
    when {
        s == null && failed -> Text("Couldn't reach F1 live timing. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
        s == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
        Spoilers.on && "f1live:${s.meeting}:${s.session}" !in Spoilers.revealed -> Column(Modifier.padding(20.dp)) {
            Text("${s.meeting} · ${s.session}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold)
            TextButton(onClick = { Spoilers.revealed += "f1live:${s.meeting}:${s.session}" }, contentPadding = PaddingValues(0.dp)) {
                Text("Live timing hidden (spoiler-free) · tap to show", color = C.lime, fontFamily = Inter)
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            StatusBanner(s)
            Spacer(Modifier.height(8.dp))
            ChipTabs(LIVE_TABS, tab) { tab = it }
            Spacer(Modifier.height(6.dp))
            when (LIVE_TABS[tab.coerceIn(LIVE_TABS.indices)]) {
                "Timing" -> TimingTower(s)
                "Map" -> TrackMapView(s)
                "Radio" -> RadioView(s)
                "Strategy" -> StrategyView(s)
                "Laps" -> LapChartView(s)
                "Speeds" -> SpeedsView(s)
                "Control" -> RaceControl(s)
                "Pits" -> PitStops(s)
                else -> UpgradesView(s)
            }
        }
    }
}

@Composable
private fun StatusBanner(s: F1Snapshot) {
    val col = if (s.live) trackColor(s.track) else C.tile2
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = if (s.live) .9f else 1f)).padding(12.dp)) {
        val ink = if (s.live) Color.White else C.fg                     // white on a flag colour, the theme's text on a plain card
        Text("${s.meeting} · ${s.session}", color = ink, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when {
            s.live -> "LIVE · ${trackWord(s.track)}${s.lap?.let { l -> " · Lap $l${s.totalLaps?.let { "/$it" } ?: ""}" } ?: s.left(Instant.now())?.let { " · ${clock(it)} left" } ?: ""}${if (s.status == "Aborted") " · session stopped" else ""}"
            s.over -> "Finished${s.start?.let { " · ${dayWord(it, Instant.now())}" } ?: ""}"
            else -> "Not started yet${s.start?.let { " · ${dayWord(it, Instant.now())} ${hm(it)}" } ?: ""}"
        }, color = ink, fontFamily = Inter, fontSize = 14.sp)
        s.weather?.let { w ->
            Text("Air ${w.air}° · Track ${w.track}° · ${if (w.rain) "Rain" else "Dry"} · Wind ${w.wind} m/s", color = ink.copy(alpha = .85f), fontFamily = Inter, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TimingTower(s: F1Snapshot) = LazyColumn {
    items(s.lines, key = { "t" + it.number }) { l ->
        val d = s.drivers[l.number]
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile)
            .padding(end = 10.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(teamColor(d?.colour.orEmpty())))
            Text(if (l.pos == 99) "–" else "${l.pos}", Modifier.width(36.dp).padding(start = 8.dp), style = Digits, fontSize = 19.sp, color = if (l.pos == 1) C.lime else C.fg)
            Column(Modifier.weight(1f)) {
                Text(s.tla(l.number), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val sec = s.sectors[l.number].orEmpty()
                if (s.live && sec.any { it.value.isNotBlank() || it.segments.isNotEmpty() }) SectorBars(sec)
                else Text(d?.team.orEmpty(), color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val tag = when { l.retired -> "OUT"; l.stopped -> "STOPPED"; l.inPit -> "PIT"; l.knockedOut -> "KO"; else -> null }
            tag?.let { Text(it, color = if (it == "PIT") C.lime else RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) }
            if (l.tyre.isNotBlank()) Box(Modifier.size(20.dp).clip(CircleShape).background(tyreColor(l.tyre)), contentAlignment = Alignment.Center) {
                Text(l.tyre.take(1), color = Color.Black, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Column(Modifier.width(86.dp), horizontalAlignment = Alignment.End) {
                Text(when { l.pos == 1 && s.racing -> "Leader"; l.pos == 1 -> l.best; else -> l.gap.ifBlank { l.best } }, color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 1)
                Text(if (s.racing) listOfNotNull(l.interval.takeIf { it.isNotBlank() && l.pos != 1 }, "${l.pits} stop${if (l.pits == 1) "" else "s"}").joinToString(" · ")
                     else "${l.laps} laps", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
    item { F1Note("Live timing from Formula 1. Gaps can lag the TV picture by a few seconds. Sector bars: purple is the session's best, green the driver's own best.") }
}

@Composable private fun F1Note(t: String) = Text(t, color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

@Composable
private fun RaceControl(s: F1Snapshot) = LazyColumn {
    if (s.messages.isEmpty()) item { F1Note("No race control messages yet.") }
    items(s.messages.reversed().withIndex().toList(), key = { "m" + it.index }) { (_, m) ->
        val col = when { m.flag.contains("RED") -> RED; m.flag.contains("YELLOW") -> YELLOW; m.flag == "GREEN" || m.flag == "CLEAR" -> GREEN
            m.category == "SafetyCar" -> ORANGE; m.flag == "BLUE" -> Color(0xFF1E88E5); m.flag == "CHEQUERED" -> C.fg; else -> C.fg3 }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile).padding(10.dp),
            verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(col))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.message, color = C.fg, fontFamily = Inter, fontSize = 14.sp)
                Text(listOfNotNull(utcHm(m.utc).takeIf { it.isNotBlank() }, m.lap?.let { "lap $it" }).joinToString(" · "), color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
            }
        }
    }
    item { F1Note("Race control messages from Formula 1, newest first.") }
}

@Composable
private fun PitStops(s: F1Snapshot) {
    val livePits = s.pits.sortedByDescending { it.lap.toIntOrNull() ?: 0 }
    var history by remember { mutableStateOf<Pair<String, List<PitStop>>?>(null) }
    if (livePits.isEmpty()) LaunchedEffect(Unit) { history = runCatching { jolpicaPitStops() }.getOrNull() }
    LazyColumn {
        if (livePits.isNotEmpty()) {
            item { SectionLabel("Pit lane times · ${s.session}") }
            items(livePits, key = { "p" + it.number + it.lap }) { p ->
                PitRow(s.tla(p.number), p.lap, "", p.duration, "pit lane")
            }
            item { F1Note("Time from pit entry to pit exit, from Formula 1.") }
        } else {
            val h = history
            when {
                h == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) } }
                h.second.isEmpty() -> item { F1Note("No pit stops recorded yet.") }
                else -> {
                    item { SectionLabel("Pit stops · ${h.first}") }
                    items(h.second.sortedBy { it.duration.toDoubleOrNull() ?: 99.0 }, key = { "j" + it.driver + it.stop }) { p ->
                        PitRow(p.driver, p.lap, p.stop, p.duration, "pit lane")
                    }
                    item { F1Note("Last race, fastest first, from Jolpica. During a session this shows live pit lane times.") }
                }
            }
        }
    }
}

@Composable
private fun PitRow(who: String, lap: String, stop: String, duration: String, what: String) =
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(who, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(listOfNotNull("lap $lap", stop.takeIf { it.isNotBlank() }?.let { "stop $it" }).joinToString(" · "), color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(if (duration.isBlank()) "–" else "${duration}s", style = Digits, fontSize = 17.sp, color = C.lime)
    }

/* ---------- track map ---------- */

@Composable
private fun TrackMapView(s: F1Snapshot) {
    val ctx = LocalContext.current
    var map by remember(s.circuitKey) { mutableStateOf<CircuitMap?>(null) }
    var failed by remember(s.circuitKey) { mutableStateOf(false) }
    LaunchedEffect(s.circuitKey) {
        val k = s.circuitKey ?: run { failed = true; return@LaunchedEffect }
        runCatching { circuitMap(ctx.cacheDir, k, s.year) }.onSuccess { map = it }.onFailure { if (it !is CancellationException) failed = true }
    }
    val flagged = remember(s.messages) { flaggedSectors(s.messages) }
    val m = map
    Column {
        when {
            failed -> F1Note("No track map for this circuit.")
            m == null -> Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
            else -> {
                val whole = if (s.live && s.track in setOf("4", "5", "6", "7")) trackColor(s.track) else null
                TrackCanvas(m, flagged, whole, Modifier.padding(16.dp).fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(16.dp)).background(C.tile))
                Text(when {
                    whole != null -> "${trackWord(s.track)}: the whole track is neutralised."
                    flagged.isNotEmpty() -> "Flags now: " + flagged.entries.joinToString { "sector ${it.key} ${it.value.lowercase()}" }
                    s.live -> "Green: no sectors under a flag."
                    else -> "${m.name} · ${m.corners.size} corners"
                }, color = C.fg, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
                F1Note("Map from MultiViewer's circuit data. Numbers are corners; coloured stretches are marshal sectors under a flag. Car positions are F1 TV only.")
            }
        }
    }
}

/** The circuit drawn the FastF1 way: y up, turned by the circuit's own rotation, corners numbered just off the line. */
@Composable
fun TrackCanvas(m: CircuitMap, flagged: Map<Int, String>, whole: Color?, modifier: Modifier) = Canvas(modifier) {
    if (m.x.size < 2) return@Canvas
    val a = Math.toRadians(m.rotation)
    fun rot(x: Double, y: Double) = Pair(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
    val pts = m.x.indices.map { rot(m.x[it], m.y[it]) }
    val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }; val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
    val pad = 28.dp.toPx()
    val scale = minOf((size.width - 2 * pad) / (maxX - minX), (size.height - 2 * pad) / (maxY - minY)).toFloat()
    val ox = (size.width - (maxX - minX).toFloat() * scale) / 2; val oy = (size.height - (maxY - minY).toFloat() * scale) / 2
    fun screen(p: Pair<Double, Double>) = Offset(ox + ((p.first - minX).toFloat() * scale), oy + ((maxY - p.second).toFloat() * scale))
    val path = Path().apply { pts.forEachIndexed { i, p -> val o = screen(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
    // a race graphic: soft glow, dark tarmac band with a light edge, and a racing line down the middle
    val line = whole ?: C.bright                                        // the bright accent reads on dark asphalt in light mode too
    val round = { w: Float -> Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round) }
    drawPath(path, line.copy(alpha = .10f), style = round(22.dp.toPx()))
    drawPath(path, Color(0xFF4A4E44), style = round(13.dp.toPx()))
    drawPath(path, Color(0xFF22251F), style = round(10.dp.toPx()))
    drawPath(path, line.copy(alpha = if (whole != null) 1f else .9f), style = round(2.6.dp.toPx()))
    // a flagged marshal sector runs from its own marker to the next one along the lap
    if (whole == null && flagged.isNotEmpty() && m.sectors.isNotEmpty()) {
        fun nearest(t: TrackPoint) = m.x.indices.minBy { (m.x[it] - t.x) * (m.x[it] - t.x) + (m.y[it] - t.y) * (m.y[it] - t.y) }
        val starts = m.sectors.sortedBy { it.number }.map { it.number to nearest(it) }
        starts.forEachIndexed { i, (num, from) ->
            val flag = flagged[num] ?: return@forEachIndexed
            val to = starts[(i + 1) % starts.size].second
            val idx = if (to > from) (from..to).toList() else (from until pts.size).toList() + (0..to).toList()
            val seg = Path().apply { idx.forEachIndexed { j, k -> val o = screen(pts[k]); if (j == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(seg, if (flag == "DOUBLE YELLOW") ORANGE else YELLOW, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // start/finish: a chequered bar across the track at the first point, and an arrow a little way on showing the direction of the lap
    val s0 = screen(pts.first()); val s1 = screen(pts[minOf(6, pts.size - 1)])
    val dir = Offset(s1.x - s0.x, s1.y - s0.y).let { d -> val l = kotlin.math.hypot(d.x, d.y).takeIf { it > 0f } ?: 1f; Offset(d.x / l, d.y / l) }
    val across = Offset(-dir.y, dir.x)
    val half = 8.dp.toPx(); val sq = 2 * half / 6
    for (i in 0 until 6) for (j in 0 until 2) {
        val c0 = Offset(s0.x + across.x * (-half + i * sq) + dir.x * (j - 1) * sq, s0.y + across.y * (-half + i * sq) + dir.y * (j - 1) * sq)
        val quad = Path().apply {
            moveTo(c0.x, c0.y); lineTo(c0.x + across.x * sq, c0.y + across.y * sq)
            lineTo(c0.x + across.x * sq + dir.x * sq, c0.y + across.y * sq + dir.y * sq); lineTo(c0.x + dir.x * sq, c0.y + dir.y * sq); close()
        }
        drawPath(quad, if ((i + j) % 2 == 0) Color.White else Color.Black)
    }
    val tip = screen(pts[minOf(pts.size / 30 + 8, pts.size - 1)]); val back = screen(pts[minOf(pts.size / 30 + 2, pts.size - 1)])
    val ad = Offset(tip.x - back.x, tip.y - back.y).let { d -> val l = kotlin.math.hypot(d.x, d.y).takeIf { it > 0f } ?: 1f; Offset(d.x / l, d.y / l) }
    val an = Offset(-ad.y, ad.x); val ah = 6.dp.toPx()
    val arrowAt = Offset(tip.x + an.x * 13.dp.toPx(), tip.y + an.y * 13.dp.toPx())
    drawPath(Path().apply {
        moveTo(arrowAt.x + ad.x * ah, arrowAt.y + ad.y * ah)
        lineTo(arrowAt.x - ad.x * ah + an.x * ah * .8f, arrowAt.y - ad.y * ah + an.y * ah * .8f)
        lineTo(arrowAt.x - ad.x * ah - an.x * ah * .8f, arrowAt.y - ad.y * ah - an.y * ah * .8f); close()
    }, C.bright)
    val paint = android.graphics.Paint().apply { color = C.fg.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD }
    // each corner number sits just off the line, pushed away from the middle of the circuit
    val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
    m.corners.forEach { c ->
        val p = rot(c.x, c.y)
        val dx = p.first - cx; val dy = p.second - cy; val len = Math.hypot(dx, dy).takeIf { it > 0 } ?: 1.0
        val o = screen(Pair(p.first + dx / len * 700, p.second + dy / len * 700))
        drawCircle(C.tile, 8.dp.toPx(), o)                                // each corner number in a small badge
        drawCircle(C.lime.copy(alpha = .55f), 8.dp.toPx(), o, style = Stroke(1.dp.toPx()))
        drawContext.canvas.nativeCanvas.drawText("${c.number}", o.x, o.y + paint.textSize / 3, paint)
    }
}

/* ---------- upgrades ---------- */

private val pdfLock = kotlinx.coroutines.sync.Mutex()

/** Renders one page when it scrolls into view, so a 19-page document never sits in memory all at once. */
private suspend fun renderPage(f: java.io.File, i: Int): Bitmap = pdfLock.withLockAndIo {
    PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
        r.openPage(i).use { p ->
            val w = 1080; val h = (w.toFloat() * p.height / p.width).toInt()
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }
}
private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockAndIo(block: () -> T): T = withContext(Dispatchers.IO) { lock(); try { block() } finally { unlock() } }

@Composable
private fun PdfPage(f: java.io.File, i: Int) {
    var b by remember(f, i) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(f, i) { b = runCatching { renderPage(f, i) }.getOrNull() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val bmp = b
    if (bmp == null) Box(Modifier.fillMaxWidth().aspectRatio(0.7f).padding(8.dp).background(C.tile))
    else Image(bmp.asImageBitmap(), null, Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)
        .clip(RoundedCornerShape(6.dp))
        .pointerInput(Unit) { detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(1f, 4f); pan = if (zoom == 1f) Offset.Zero else pan + p } }
        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y))
}

@Composable
private fun UpgradesView(s: F1Snapshot) = UpgradesDoc(s.meeting, s.year)

/** The FIA's upgrades document for a Grand Prix, page by page. */
@Composable
fun UpgradesDoc(meeting: String, year: Int) {
    val ctx = LocalContext.current
    var doc by remember(meeting) { mutableStateOf<Pair<java.io.File, Int>?>(null) }
    var failed by remember(meeting) { mutableStateOf<String?>(null) }
    LaunchedEffect(meeting) {
        runCatching {
            val f = fiaUpgradesPdf(ctx.cacheDir, meeting, year)
            f to withContext(Dispatchers.IO) { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount } }
        }.onSuccess { doc = it }.onFailure { if (it !is CancellationException) failed = it.message ?: "not available" }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Upgrades · ${meeting}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
            Text("The FIA's Car Presentation Submissions: every team's new parts, why, and how they work. Pinch a page to zoom.",
                color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fiaUpgradesUrl(meeting, year)))) } },
                modifier = Modifier.padding(start = 12.dp)) { Text("Open the PDF", color = C.lime, fontFamily = Inter) }
        }
        val d = doc
        when {
            failed != null -> item { F1Note("No upgrades document for ${meeting} yet (${failed}). The FIA publishes it on the Thursday or Friday of a Grand Prix weekend.") }
            d == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) } }
            else -> items((1 until d.second).toList(), key = { "u$it" }) { i -> PdfPage(d.first, i) }   // page 1 is the FIA cover note
        }
        item { F1Note("Document from fia.com, shown as published.") }
    }
}

/* ---------- the F1 weekend page (the sheet for any F1 session) ---------- */

/** A grey block that pulses while something is loading, so it's clear there is more coming. */
@Composable
fun Skeleton(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val a by pulse.animateFloat(0.35f, 0.75f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(C.tile2.copy(alpha = a)))
}

@Composable
private fun Heading(t: String) = Text(t, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))

/** The whole Grand Prix weekend for any F1 session: schedule, results so far, the track, last year and the forecast. */
@Composable
fun F1WeekendView(e: Event, events: List<Event>, now: Instant, onPage: (String) -> Unit, trackFirst: Boolean = false) {
    val ctx = LocalContext.current
    val weekend = remember(e, events) { events.filter { it.code == "F1" && it.title == e.title && Duration.between(it.start, e.start).abs().toDays() <= 5 }.sortedBy { it.start } }
    var info by remember(e.title) { mutableStateOf<GpInfo?>(null) }
    var last by remember(e.title) { mutableStateOf<LastYear?>(null) }
    var results by remember(e.title) { mutableStateOf<List<RaceSession>?>(null) }
    var map by remember(e.title) { mutableStateOf<CircuitMap?>(null) }
    var mapDone by remember(e.title) { mutableStateOf(false) }
    var weather by remember(e.title) { mutableStateOf<Map<Instant, Forecast>>(emptyMap()) }
    var open by remember(e.title) { mutableStateOf<String?>(null) }
    // everything loads at once; each part appears as soon as its own source answers
    LaunchedEffect(e.title) {
        kotlinx.coroutines.coroutineScope {
            val g = async { runCatching { gpInfo(e) }.getOrNull() }
            launch { map = runCatching { circuitKey(e)?.let { circuitMap(ctx.cacheDir, it, e.start.atZone(ZoneOffset.UTC).year) } }.getOrNull(); mapDone = true }
            launch { results = runCatching { weekendResults(e, g.await()) }.getOrDefault(emptyList()) }
            launch {
                val gi = g.await(); info = gi
                if (gi != null) {
                    launch { weather = runCatching { forecast(gi.lat, gi.lon, weekend.filter { !it.isTbc }.map { it.start }) }.getOrDefault(emptyMap()) }
                    launch { last = runCatching { lastYear(gi) }.getOrNull() }
                }
            }
        }
    }

    @Composable
    fun Track() {
        Heading("The track")
        val m = map
        when {
            m != null -> {
                TrackCanvas(m, emptyMap(), null, Modifier.padding(top = 6.dp).fillMaxWidth().height(250.dp).clip(RoundedCornerShape(14.dp)).background(C.bg))
                Text("${m.corners.size} corners${info?.let { " · ${it.circuit}" } ?: ""}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            !mapDone -> Skeleton(250.dp, Modifier.padding(top = 6.dp))
            else -> Text(info?.circuit ?: "No track map for this circuit.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
        }
        last?.let { l ->
            Text("${l.year}: ${l.winner} won for ${l.team}, ${l.laps} laps in ${l.time}" + (l.fastest?.let { ". Fastest lap: $it" } ?: ""),
                color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    Column(Modifier.padding(top = 12.dp)) {
        info?.let { g -> Text("Round ${g.round} · ${g.circuit} · ${g.locality}, ${g.country}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp) }
            ?: Skeleton(16.dp, Modifier.fillMaxWidth(0.7f))
        if (trackFirst) Track()

        Heading("This weekend")
        weekend.forEach { s ->
            val f = weather[s.start]
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sessName(s.session), Modifier.width(104.dp), color = if (s.id == e.id) C.lime else C.fg, fontFamily = Inter,
                    fontWeight = if (s.id == e.id) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                Text(if (s.isTbc) "TBC" else "${dayWord(s.start, now)} ${hm(s.start)}", Modifier.width(96.dp), color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                Text(when {
                    s.inProgress(now) -> "LIVE"
                    s.over(now) -> "done"
                    f != null -> "${f.temp.toInt()}° · ${f.rain}% rain"
                    else -> ""
                }, color = if (s.inProgress(now)) C.lime else C.fg3, fontFamily = Inter, fontSize = 12.sp)
            }
        }
        weather[weekend.lastOrNull { it.session == "Race" }?.start]?.let { f ->
            Text("Race forecast: ${f.temp.toInt()}°C, ${weatherWord(f.code)}, ${f.rain}% chance of rain, wind ${f.wind.toInt()} km/h", color = C.fg2, fontFamily = Inter,
                fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        val anyDone = weekend.any { it.over(now) || it.inProgress(now) }
        val done = results.orEmpty().filter { it.state == "post" && !it.rows.isNullOrEmpty() }
        if (anyDone) {
            Heading("Results so far")
            if (results == null) repeat(2) { Skeleton(58.dp, Modifier.padding(top = 6.dp)) }
            else if (done.isEmpty()) Text("No results published yet.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            done.forEach { r ->
                val rows = r.rows.orEmpty()
                Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile2)
                    .clickable { open = if (open == r.name) null else r.name }.padding(10.dp)) {
                    Text(r.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Reveal("wk:${e.title}:${r.name}") {
                        val lead = when { r.name.contains("Qualifying") -> "Pole: "; r.name.contains("Practice") -> "Fastest: "; else -> "Winner: " }
                        Text(lead + rows.first().name + (rows.first().team.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), color = C.fg, fontFamily = Inter, fontSize = 13.sp)
                        Text(rows.take(3).joinToString("  ·  ") { "${it.pos} ${it.name.substringAfterLast(' ')}" }, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                        if (open == r.name) rows.take(10).forEach { x ->
                            Row(Modifier.padding(top = 3.dp)) {
                                Text("${x.pos}", Modifier.width(26.dp), color = C.fg3, fontFamily = Inter, fontSize = 13.sp)
                                Text(x.name, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontSize = 13.sp)
                                Text(x.time, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                            }
                        } else Text("Tap for the top 10", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                    }
                }
            }
        }

        if (!trackFirst) Track()

        // race week extras for the Grand Prix that's on now or next: title fight, stewards, news
        if (remember(e.title, events) { currentGp(events, now)?.title == e.title }) F1RaceWeek(onPage)

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPage("f1live") }) { Text("Live timing", color = C.lime, fontFamily = Inter) }
            OutlinedButton(onClick = { onPage("upgrades:${e.start.atZone(ZoneOffset.UTC).year}:${e.title}") }) { Text("Upgrades", color = C.lime, fontFamily = Inter) }
        }
        info?.wiki?.takeIf { it.isNotBlank() }?.let { url ->
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, contentPadding = PaddingValues(0.dp)) {
                Text("About the circuit on Wikipedia", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        Text("Calendar and history from Jolpica, results from ESPN or Jolpica, forecast from Open-Meteo, map from MultiViewer.",
            color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/* ---------- the F1 tab ---------- */

/** The race of the Grand Prix that is on now, or the next one. */
fun currentGp(events: List<Event>, now: Instant): Event? = events
    .filter { it.code == "F1" && it.session == "Race" && it.end.plusSeconds(12 * 3600).isAfter(now) }
    .minByOrNull { it.start }

@Composable
fun F1Hub(events: List<Event>, now: Instant, onOpen: (Event) -> Unit, onPage: (String) -> Unit, onChange: () -> Unit = {}) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val race = remember(events, now.epochSecond / 600) { currentGp(events, now) }
    Column(Modifier.fillMaxSize()) {
        HubHeader("Formula 1", race?.let { "Next: ${it.title}" } ?: "Season ${now.atZone(ZoneOffset.UTC).year}", "F1", onChange)
        Segments(listOf("Weekend", "Live", "Standings", "Season"), seg) { seg = it }
        Spacer(Modifier.height(8.dp))
        when (seg) {
            0 -> if (race == null) Text("No Grand Prix coming up.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
                 else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                     WeekendHeader(race, events, now, onOpen)
                     f1LiveSession(f1Weekend(race, events), now)?.let { F1NowPanel(it, onPage) }
                     F1WeekendView(race, events, now, onPage, trackFirst = true)
                 }
            1 -> F1LiveView()
            2 -> F1View()
            else -> SeasonView(events, now, onOpen)
        }
    }
}

/** Big title, dates and what's next this weekend, with a countdown. */
@Composable
private fun WeekendHeader(race: Event, events: List<Event>, now: Instant, onOpen: (Event) -> Unit) {
    val weekend = events.filter { it.code == "F1" && it.title == race.title && Duration.between(it.start, race.start).abs().toDays() <= 5 }.sortedBy { it.start }
    val next = weekend.firstOrNull { it.inProgress(now) } ?: weekend.firstOrNull { !it.started(now) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.tile).clickable { next?.let(onOpen) }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoChip("F1", 24.dp); Spacer(Modifier.width(10.dp))
            Text(race.title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        weekend.firstOrNull()?.let { f ->
            Text("${daySub(localDate(f.start))} – ${daySub(localDate(race.start))}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (next != null) {
            Text(if (next.inProgress(now)) "${sessName(next.session)} is on now" else "Next: ${sessName(next.session)} · ${dayWord(next.start, now)} ${hm(next.start)}",
                color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
            if (!next.started(now) && !next.isTbc) Text("in ${untilText(Duration.between(now, next.start).toMinutes().coerceAtLeast(1))}",
                color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SeasonView(events: List<Event>, now: Instant, onOpen: (Event) -> Unit) {
    val year = now.atZone(ZoneOffset.UTC).year
    var list by remember { mutableStateOf<List<SeasonRace>?>(null) }
    LaunchedEffect(year) { list = runCatching { season(year) }.getOrDefault(emptyList()) }
    val l = list
    LazyColumn(Modifier.fillMaxSize()) {
        if (l == null) items(6) { Skeleton(60.dp, Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
        else if (l.isEmpty()) item { Text("Couldn't load the calendar. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        else items(l, key = { "r" + it.round }) { r ->
            val ev = events.firstOrNull { it.code == "F1" && it.session == "Race" && r.start != null && Duration.between(it.start, r.start).abs().toDays() <= 2 }
            val past = r.start?.isBefore(now) == true
            Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile)
                .clickable(enabled = ev != null) { ev?.let(onOpen) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r.round}", Modifier.width(30.dp), style = Digits, fontSize = 19.sp, color = if (ev != null && !past) C.lime else C.fg3)
                Column(Modifier.weight(1f)) {
                    Text(r.name.replace("Grand Prix", "GP"), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${r.start?.let { daySub(localDate(it)) } ?: ""} · ${r.locality}", color = C.fg2, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (r.winner != null) Box { Reveal("season:$year:${r.round}") {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(r.winner.substringAfterLast(' '), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(r.winnerTeam.orEmpty(), color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                    }
                } }
            }
        }
        item { Text("Calendar and winners from Jolpica. Tap a race for its weekend page.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
    }
}

/** Full-screen pages the weekend sheet opens: F1 Live, the news and the upgrades document. */
@Composable
fun F1Page(page: String, onBack: () -> Unit) = Column(Modifier.fillMaxSize().statusBarsPadding()) {
    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
    if (page == "f1live") F1LiveView()
    else if (page == "f1news") F1NewsScreen()
    else page.removePrefix("upgrades:").split(":", limit = 2).let { parts -> UpgradesDoc(parts.getOrElse(1) { "" }, parts[0].toIntOrNull() ?: 2026) }
}
