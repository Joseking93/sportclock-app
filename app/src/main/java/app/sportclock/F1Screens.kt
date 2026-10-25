package app.sportclock

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.sin

private val GREEN = Color(0xFF2E7D32); private val YELLOW = Color(0xFFF9A825); private val ORANGE = Color(0xFFFB8C00); private val RED = Color(0xFFD32F2F)
private fun trackColor(code: String) = when (code) { "2" -> YELLOW; "4", "6", "7" -> ORANGE; "5" -> RED; else -> GREEN }
private fun teamColor(hex: String) = hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: C.fg3
private fun tyreColor(t: String) = when (t.uppercase()) { "SOFT" -> Color(0xFFE53935); "MEDIUM" -> Color(0xFFFDD835); "HARD" -> Color(0xFFEEEEEE)
    "INTERMEDIATE" -> Color(0xFF43A047); "WET" -> Color(0xFF1E88E5); else -> C.fg3 }
private fun utcHm(utc: String) = runCatching { hm(LocalDateTime.parse(utc.removeSuffix("Z")).toInstant(ZoneOffset.UTC)) }.getOrDefault("")

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
            Segments(listOf("Timing", "Map", "Control", "Pits", "Upgrades"), tab) { tab = it }
            Spacer(Modifier.height(6.dp))
            when (tab) {
                0 -> TimingTower(s)
                1 -> TrackMapView(s)
                2 -> RaceControl(s)
                3 -> PitStops(s)
                else -> UpgradesView(s)
            }
        }
    }
}

@Composable
private fun StatusBanner(s: F1Snapshot) {
    val col = if (s.live) trackColor(s.track) else C.tile2
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = if (s.live) .9f else 1f)).padding(12.dp)) {
        Text("${s.meeting} · ${s.session}", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when {
            s.live -> "LIVE · ${trackWord(s.track)}${s.lap?.let { l -> " · Lap $l${s.totalLaps?.let { "/$it" } ?: ""}" } ?: ""}${if (s.status == "Aborted") " · session stopped" else ""}"
            s.over -> "Finished${s.start?.let { " · ${dayWord(it, Instant.now())}" } ?: ""}"
            else -> "Not started yet${s.start?.let { " · ${dayWord(it, Instant.now())} ${hm(it)}" } ?: ""}"
        }, color = Color.White, fontFamily = Inter, fontSize = 14.sp)
        s.weather?.let { w ->
            Text("Air ${w.air}° · Track ${w.track}° · ${if (w.rain) "Rain" else "Dry"} · Wind ${w.wind} m/s", color = Color.White.copy(alpha = .85f), fontFamily = Inter, fontSize = 12.sp)
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
                Text(d?.team.orEmpty(), color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    item { F1Note("Live timing from Formula 1. Gaps can lag the TV picture by a few seconds.") }
}

@Composable private fun F1Note(t: String) = Text(t, color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

@Composable
private fun RaceControl(s: F1Snapshot) = LazyColumn {
    if (s.messages.isEmpty()) item { F1Note("No race control messages yet.") }
    items(s.messages.reversed().withIndex().toList(), key = { "m" + it.index }) { (_, m) ->
        val col = when { m.flag.contains("RED") -> RED; m.flag.contains("YELLOW") -> YELLOW; m.flag == "GREEN" || m.flag == "CLEAR" -> GREEN
            m.category == "SafetyCar" -> ORANGE; m.flag == "BLUE" -> Color(0xFF1E88E5); m.flag == "CHEQUERED" -> Color.White; else -> C.fg3 }
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
private fun TrackCanvas(m: CircuitMap, flagged: Map<Int, String>, whole: Color?, modifier: Modifier) = Canvas(modifier) {
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
    drawPath(path, whole ?: Color(0xFF6B6F66), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    // a flagged marshal sector runs from its own marker to the next one along the lap
    if (whole == null && flagged.isNotEmpty() && m.sectors.isNotEmpty()) {
        fun nearest(t: TrackPoint) = m.x.indices.minBy { (m.x[it] - t.x) * (m.x[it] - t.x) + (m.y[it] - t.y) * (m.y[it] - t.y) }
        val starts = m.sectors.sortedBy { it.number }.map { it.number to nearest(it) }
        starts.forEachIndexed { i, (num, from) ->
            val flag = flagged[num] ?: return@forEachIndexed
            val to = starts[(i + 1) % starts.size].second
            val idx = if (to > from) (from..to).toList() else (from until pts.size).toList() + (0..to).toList()
            val seg = Path().apply { idx.forEachIndexed { j, k -> val o = screen(pts[k]); if (j == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(seg, if (flag == "DOUBLE YELLOW") ORANGE else YELLOW, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // start/finish: the first point of the outline
    drawCircle(Color.White, 5.dp.toPx(), screen(pts.first()))
    val paint = android.graphics.Paint().apply { color = C.fg2.toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
    // each corner number sits just off the line, pushed away from the middle of the circuit
    val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
    m.corners.forEach { c ->
        val p = rot(c.x, c.y)
        val dx = p.first - cx; val dy = p.second - cy; val len = Math.hypot(dx, dy).takeIf { it > 0 } ?: 1.0
        val o = screen(Pair(p.first + dx / len * 600, p.second + dy / len * 600))
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
private fun UpgradesView(s: F1Snapshot) {
    val ctx = LocalContext.current
    var doc by remember(s.meeting) { mutableStateOf<Pair<java.io.File, Int>?>(null) }
    var failed by remember(s.meeting) { mutableStateOf<String?>(null) }
    LaunchedEffect(s.meeting) {
        runCatching {
            val f = fiaUpgradesPdf(ctx.cacheDir, s.meeting, s.year)
            f to withContext(Dispatchers.IO) { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount } }
        }.onSuccess { doc = it }.onFailure { if (it !is CancellationException) failed = it.message ?: "not available" }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text("Upgrades · ${s.meeting}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp))
            Text("The FIA's Car Presentation Submissions: every team's new parts, why, and how they work. Pinch a page to zoom.",
                color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fiaUpgradesUrl(s.meeting, s.year)))) } },
                modifier = Modifier.padding(start = 12.dp)) { Text("Open the PDF", color = C.lime, fontFamily = Inter) }
        }
        val d = doc
        when {
            failed != null -> item { F1Note("No upgrades document for ${s.meeting} yet (${failed}). The FIA publishes it on the Thursday or Friday of a Grand Prix weekend.") }
            d == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) } }
            else -> items((1 until d.second).toList(), key = { "u$it" }) { i -> PdfPage(d.first, i) }   // page 1 is the FIA cover note
        }
        item { F1Note("Document from fia.com, shown as published.") }
    }
}
