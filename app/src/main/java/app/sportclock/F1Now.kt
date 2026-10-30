package app.sportclock

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/*
 * What's happening right now in a live F1 session, at the top of the session's sheet and of the F1 tab's weekend
 * page: the flag that's out in big letters with its symbol, the clock or the lap, a small map with the flagged
 * sectors, race control's latest word and the running order.
 *
 * While a flag is out the whole app takes its colours: yellow for yellows and the VSC, amber for the safety car,
 * red for a red flag. That's done by swapping the palette ([Themes.flag]), so every screen stays readable.
 */

/** The latest snapshot of the live session. */
object F1Now { var snap by mutableStateOf<F1Snapshot?>(null) }

/** Every session of [e]'s Grand Prix weekend, in order. */
fun f1Weekend(e: Event, events: List<Event>) =
    events.filter { it.code == "F1" && it.title == e.title && Duration.between(it.start, e.start).abs().toDays() <= 5 }.sortedBy { it.start }

/** The session of the weekend that's on now: by the schedule, or by F1's own feed when it runs long (red flags, a late start). */
fun f1LiveSession(weekend: List<Event>, now: Instant): Event? = weekend.firstOrNull { it.inProgress(now) }
    ?: F1Now.snap?.takeIf { it.live }?.let { s -> weekend.firstOrNull { it.started(now) && s.isFor(it) && Duration.between(it.start, now).toHours() < 5 } }

/** What the session looks like right now: green, yellow, vsc, sc, red, chequered or waiting. */
fun flagKind(s: F1Snapshot?): String = when {
    s == null -> "waiting"
    s.status == "Aborted" || s.live && s.track == "5" -> "red"
    s.over -> "chequered"
    !s.live -> "waiting"
    s.track == "4" -> "sc"
    s.track == "6" || s.track == "7" -> "vsc"
    s.track == "2" -> "yellow"
    else -> "green"
}

private fun c(v: Long) = Color(v)
private val YELLOW_P = Palette(false, c(0xFFF5C518), c(0xFFF5C518), c(0xFFE2B312), c(0xFFF5C518), c(0xFFD9AA10), c(0xFFB8900C),
    c(0xFF1A1600), c(0xFF3F3500), c(0xFF5E5000), c(0xFF1A1600), c(0xFFF5C518), c(0xFFB71C1C), c(0xFFE2B312),
    c(0xFFF5C518), c(0xFFE2B312), c(0xFFFFE27A))
private val AMBER_P = Palette(false, c(0xFFF2A516), c(0xFFF2A516), c(0xFFDE9410), c(0xFFF2A516), c(0xFFD48C0C), c(0xFFB0740A),
    c(0xFF1F1200), c(0xFF443000), c(0xFF5E4300), c(0xFF1F1200), c(0xFFF2A516), c(0xFFB71C1C), c(0xFFDE9410),
    c(0xFFF2A516), c(0xFFDE9410), c(0xFFFFD27A))
private val RED_P = Palette(true, c(0xFFC62828), c(0xFFC62828), c(0xFFAD2121), c(0xFFC62828), c(0xFFA51F1F), c(0xFF8E1A1A),
    c(0xFFFFFFFF), c(0xFFFFDCDC), c(0xFFF5BDBD), c(0xFFFFFFFF), c(0xFFC62828), c(0xFFFFFFFF), c(0xFFAD2121),
    c(0xFFC62828), c(0xFFAD2121), c(0xFFFFFFFF))

/** The palette the app takes while this flag is out, or null to keep the reader's own. */
fun flagPalette(kind: String): Palette? = when (kind) { "yellow", "vsc" -> YELLOW_P; "sc" -> AMBER_P; "red" -> RED_P; else -> null }

/** Paints the app in the flag's colours while [s] is on screen, and gives the reader's palette back after. */
@Composable
fun FlagColours(s: F1Snapshot?) {
    val p = flagPalette(flagKind(s))
    SideEffect { if (Themes.flag != p) Themes.flag = p }
    DisposableEffect(Unit) { onDispose { Themes.flag = null } }
}

/** A countdown, rounded up like a TV clock: 24:32.998 left still reads 24:33. */
fun clock(d: Duration): String = ((d.toMillis() + 999) / 1000).let { s -> if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

private fun calm(m: String) = m.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("""\(([a-z]{3})\)""")) { "(${it.groupValues[1].uppercase()})" }

/** The flag's symbol, drawn in the ink colour: a waving flag, the VSC or SC board, a stop sign for red, a chequered flag. */
@Composable
private fun FlagGlyph(kind: String) {
    val ink = C.fg
    when (kind) {
        "yellow" -> Canvas(Modifier.size(54.dp)) {
            val u = size.width / 54f
            drawRoundRect(ink, Offset(10 * u, 6 * u), Size(3 * u, 44 * u), androidx.compose.ui.geometry.CornerRadius(1.5f * u))
            drawPath(Path().apply {
                moveTo(13 * u, 8 * u); quadraticTo(26 * u, 2 * u, 34 * u, 8 * u); quadraticTo(42 * u, 14 * u, 46 * u, 9 * u)
                lineTo(46 * u, 30 * u); quadraticTo(38 * u, 24 * u, 30 * u, 29 * u); quadraticTo(22 * u, 34 * u, 13 * u, 30 * u); close()
            }, ink)
        }
        "vsc", "sc" -> Box(Modifier.border(3.dp, ink, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 2.dp)) {
            Text(if (kind == "vsc") "VSC" else "SC", color = ink, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, letterSpacing = 3.sp)
        }
        "red" -> Box(Modifier.size(52.dp).border(3.dp, ink, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(ink))
        }
        "chequered" -> Canvas(Modifier.size(54.dp, 36.dp)) {
            val q = size.width / 6
            for (i in 0 until 4) for (j in 0 until 6) if ((i + j) % 2 == 0) drawRect(ink, Offset(j * q, i * q), Size(q, q))
            drawRect(ink, Offset.Zero, size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

@Composable
fun F1NowPanel(session: Event, onPage: (String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(Instant.now()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(session.id) {
        while (true) {
            runCatching { f1Snapshot() }.onSuccess { F1Now.snap = it; failed = false }
                .onFailure { if (it is CancellationException) throw it; failed = true; Diag.log("f1 now: $it") }
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick = Instant.now() } }     // the session clock counts down between snapshots
    val s = F1Now.snap?.takeIf { it.isFor(session) }
    val kind = flagKind(s)
    FlagColours(s)
    var map by remember(s?.circuitKey) { mutableStateOf<CircuitMap?>(null) }
    LaunchedEffect(s?.circuitKey) { val k = s?.circuitKey ?: return@LaunchedEffect; map = runCatching { circuitMap(ctx.cacheDir, k, s.year) }.getOrNull() }
    val pulse = rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(1f, .25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")

    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clickable { onPage("f1live") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val on = s?.live == true
            Box(Modifier.size(9.dp).alpha(if (on) a else 1f).clip(CircleShape).background(if (kind == "chequered") Color.Transparent else if (on) C.lime else C.fg3))
            Spacer(Modifier.width(8.dp))
            Text("${when (kind) { "chequered" -> "FINISHED"; "waiting" -> "ON NOW"; else -> "LIVE" }} · ${sessName(session.session)}", color = C.fg,
                fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (kind in setOf("yellow", "vsc", "sc", "red", "chequered")) Box(Modifier.padding(top = 14.dp)) { FlagGlyph(kind) }
        Text(when {
            s == null && failed -> "No live timing"
            s == null -> "Connecting…"
            kind == "red" -> "Red flag"
            kind == "chequered" -> "Chequered flag"
            kind == "waiting" -> "Waiting for the start"
            else -> trackWord(s.track)
        }, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, modifier = Modifier.padding(top = 8.dp))
        // what the flag is for: the flagged sectors, else race control's latest word (an incident, a penalty, a deleted lap)
        val flagged = remember(s?.messages) { s?.let { flaggedSectors(it.messages) }.orEmpty() }
        val last = s?.messages?.lastOrNull { it.message.isNotBlank() }
        Text(when {
            s == null && failed -> "Couldn't reach F1's timing feed. Trying again every 15 seconds."
            s == null -> "Formula 1's own timing feed, every 15 seconds."
            kind == "red" -> "Session suspended${if (s.clockRunning) "" else " · clock stopped"}"
            kind == "vsc" -> "Hold your delta · no overtaking"
            kind == "sc" -> "Field bunching up behind the safety car"
            flagged.isNotEmpty() -> "Yellow in " + flagged.entries.joinToString { "sector ${it.key}${if (it.value == "DOUBLE YELLOW") " (double)" else ""}" } +
                (last?.let { " · ${calm(it.message)}" } ?: "")
            last != null -> calm(last.message)
            else -> ""
        }, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        // two tiles: the clock or the lap, and the track with any flagged sectors
        Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(C.tile2).padding(horizontal = 12.dp, vertical = 10.dp)) {
                val lap = s?.takeIf { it.racing && it.lap != null }
                val left = s?.left(tick)
                val best = s?.lines?.firstOrNull { it.pos == 1 }?.best
                Text(when { lap != null -> "Lap"; kind == "chequered" -> "Fastest"; kind == "red" && left != null -> "Clock stopped"; left != null -> "Time left"; else -> "Session" },
                    color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                Text(when {
                    lap != null -> "${lap.lap}${lap.totalLaps?.let { "/$it" } ?: ""}"
                    kind == "chequered" && !best.isNullOrBlank() -> best
                    left != null -> clock(left)
                    else -> sessName(session.session)
                }, style = Digits, color = C.fg, fontSize = 28.sp, maxLines = 1)
            }
            Box(Modifier.weight(1f).fillMaxHeight().heightIn(min = 72.dp).clip(RoundedCornerShape(14.dp)).background(C.tile2)) {
                map?.let { m ->
                    val whole = if (kind in setOf("vsc", "sc", "red")) (if (kind == "red") RED else YELLOW) else null
                    TrackCanvas(m, flagged, whole, Modifier.fillMaxSize().padding(2.dp))
                }
            }
        }
    }

    if (s == null) return
    val key = "f1live:${s.meeting}:${s.session}"
    if (Spoilers.on && key !in Spoilers.revealed) {
        TextButton(onClick = { Spoilers.revealed += key }, contentPadding = PaddingValues(0.dp)) { Text("Running order hidden (spoiler-free) · tap to show", color = C.lime, fontFamily = Inter) }
    } else if (s.lines.isNotEmpty()) {
        var all by remember(session.id) { mutableStateOf(false) }
        Text("Running order", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
        (if (all) s.lines else s.lines.take(10)).forEach { l -> NowRow(s, l) }
        if (!all && s.lines.size > 10) TextButton(onClick = { all = true }, contentPadding = PaddingValues(0.dp)) { Text("Show all ${s.lines.size}", color = C.lime, fontFamily = Inter) }
    }
    val recent = s.messages.filter { it.message.isNotBlank() }.takeLast(4).reversed()
    if (recent.isNotEmpty()) {
        Text("Race control", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        recent.forEach { m ->
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                Text(utcHm(m.utc), Modifier.width(48.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                Text(calm(m.message), color = C.fg, fontFamily = Inter, fontSize = 13.sp)
            }
        }
    }
    OutlinedButton(onClick = { onPage("f1live") }, modifier = Modifier.padding(top = 10.dp)) {
        Text("Full live timing: map, radio, strategy ›", color = C.lime, fontFamily = Inter)
    }
}

@Composable
private fun NowRow(s: F1Snapshot, l: F1Line) {
    val d = s.drivers[l.number]
    Row(Modifier.padding(top = 3.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.tile2).height(40.dp).padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(teamColor(d?.colour.orEmpty())))
        Text(if (l.pos == 99) "–" else "${l.pos}", Modifier.width(34.dp).padding(start = 8.dp), style = Digits, fontSize = 17.sp, color = if (l.pos == 1) C.lime else C.fg)
        Text(s.tla(l.number), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val tag = when { l.retired -> "OUT"; l.stopped -> "STOPPED"; l.inPit -> "PIT"; l.knockedOut -> "KO"; else -> null }
        tag?.let { Text(it, color = if (it == "PIT") C.lime else RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp)) }
        if (l.tyre.isNotBlank()) Box(Modifier.size(18.dp).clip(CircleShape).background(tyreColor(l.tyre)), contentAlignment = Alignment.Center) {
            Text(l.tyre.take(1), color = Color.Black, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        }
        Text(when { l.pos == 1 && s.racing -> "Leader"; l.pos == 1 -> l.best; s.racing -> l.interval.ifBlank { l.gap }; else -> l.gap.ifBlank { l.best } },
            Modifier.width(84.dp), color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, textAlign = TextAlign.End)
    }
}
