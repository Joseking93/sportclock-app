package app.sportclock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/*
 * The football match page, after FotMob's: crests and the score with the scorers under it, then tabs —
 * Facts (timeline), Lineup (both elevens on one pitch), Stats, Commentary, Table and H2H. Swipe sideways
 * or tap a tab. Everything comes from one ESPN match summary, refreshed every minute while the match is on.
 */

private const val CARD_YELLOW = 0xFFF5CB2B
private const val CARD_RED = 0xFFE5383B
private val PITCH = Color(0xFF1B5E34)
private val PITCH_BAND = Color(0xFF1D6538)
private val PITCH_HEAD = Color(0xFF174F2C)

/** Where ESPN keeps this match: its league slug and event id, from the score we hold or by finding it on the day's board. */
private suspend fun espnRef(e: Event, score: Score?): Pair<String, String>? {
    val l = score?.league; val i = score?.espnId
    if (l != null && i != null) return l to i
    return Looks.espnMatch(e)?.let { it.league to it.id }
}

/** Two club colours that read on the page and apart from each other: a near-black or clashing kit falls back to its second colour. */
internal fun teamColors(home: MpTeam, away: MpTeam): Pair<Color, Color> {
    fun ok(v: Long?) = v != null && Color(v).luminance() in 0.035f..0.93f
    fun near(x: Color, y: Color) = Math.abs(x.red - y.red) + Math.abs(x.green - y.green) + Math.abs(x.blue - y.blue) < 0.45f
    val h = Color(listOf(home.color, home.alt).firstOrNull(::ok) ?: 0xFFC8F53C)
    val a0 = listOf(away.color, away.alt).filter(::ok).map { Color(it!!) }
    val a = a0.firstOrNull { !near(it, h) } ?: if (near(Color(0xFF9EA39A), h)) Color(0xFF3DA5FF) else Color(0xFF9EA39A)
    return h to a
}
private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

@Composable
fun MatchPage(e: Event, now: Instant, score: Score?, fetchedAt: Instant?, onTeam: (String) -> Unit, onPage: (String) -> Unit,
              startTab: String = "Facts", alertRow: @Composable () -> Unit) {
    var data by remember(e.id) { mutableStateOf<MatchData?>(null) }
    var failed by remember(e.id) { mutableStateOf(false) }
    var checked by remember(e.id) { mutableStateOf<Instant?>(null) }
    LaunchedEffect(e.id, score?.espnId) {
        while (true) {
            val ref = runCatching { espnRef(e, score) }.getOrNull()
            if (ref == null) { failed = data == null; break }
            runCatching { matchData(ref.first, ref.second) }
                .onSuccess { data = it; failed = false; checked = Instant.now() }
                .onFailure { if (it is CancellationException) throw it; if (data == null) failed = true }
            val d = data
            val soon = Duration.between(Instant.now(), e.start).toMinutes() in -10..75
            if (d != null && d.state == "post") break
            if (d?.state == "in" || soon) delay(60_000) else break
        }
    }
    val d = data
    val hidden = Spoilers.hidden(e) && e.started(now)
    val (hc, ac) = if (d != null) remember(d.home, d.away) { teamColors(d.home, d.away) } else (C.lime to C.fg2)

    MatchHeader(e, now, score, d, hidden, onTeam)
    if (!e.started(now)) alertRow()

    when {
        d == null && failed -> Column(Modifier.padding(top = 16.dp)) {
            Text("Match details aren't on ESPN yet.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp)
            if (!e.started(now)) Text("Lineups usually appear about an hour before kick-off.", color = C.fg3, fontFamily = Inter, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        d == null -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
        else -> {
            val tabs = buildList {
                add("Facts"); add("Lineup")
                if (d.stats.isNotEmpty()) add("Stats")
                if (d.commentary.isNotEmpty()) add("Commentary")
                if (d.table.isNotEmpty()) add("Table")
                if (d.meetings.isNotEmpty() || d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty()) add("H2H")
            }
            var tab by rememberSaveable(e.id) { mutableStateOf(startTab) }
            if (tab !in tabs) tab = "Facts"
            TabStrip(tabs, tab) { tab = it }
            var drag by remember { mutableFloatStateOf(0f) }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).pointerInput(tabs) {
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                    val i = tabs.indexOf(tab)
                    if (drag < -140 && i < tabs.lastIndex) tab = tabs[i + 1]
                    if (drag > 140 && i > 0) tab = tabs[i - 1]
                }) { _, dx -> drag += dx }
            }) {
                val spoiled = hidden && tab in setOf("Facts", "Stats", "Commentary", "Table")
                if (spoiled) Text("Spoiler-free is on · tap the score above to show this match.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp))
                else when (tab) {
                    "Facts" -> FactsTab(e, now, d, hc, ac)
                    "Lineup" -> LineupTab(e, now, d, hc, ac, hidden)
                    "Stats" -> StatsTab(d, hc, ac)
                    "Commentary" -> CommentaryTab(d)
                    "Table" -> TableTab(e, d, hc, ac, onPage)
                    "H2H" -> H2hTab(d, hc, ac)
                }
                Text("From ESPN${(checked ?: fetchedAt)?.let { " · checked ${hm(it)}" } ?: ""} · can lag behind the match", color = C.fg3, fontFamily = Inter,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/* ---------- header ---------- */

@Composable
private fun MatchHeader(e: Event, now: Instant, score: Score?, d: MatchData?, hidden: Boolean, onTeam: (String) -> Unit) {
    val hs = d?.home?.score ?: score?.home
    val aw = d?.away?.score ?: score?.away
    val started = e.started(now) || (d?.started == true)
    val live = d?.state == "in" || score?.status in setOf("IN_PLAY", "PAUSED")
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
        listOf(0, 1).forEach { i ->
            if (i == 1) Column(Modifier.weight(1f).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (started && hs != null && aw != null) {
                    if (hidden) Text("Show score", Modifier.clip(RoundedCornerShape(10.dp)).background(C.tile2).clickable { Spoilers.revealed += e.id }
                        .padding(horizontal = 14.dp, vertical = 10.dp), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    else Text("$hs - $aw", style = Digits, color = C.fg, fontSize = 46.sp, maxLines = 1)
                    val det = (d?.detail ?: score?.detail).orEmpty()
                    val state = when {
                        d?.state == "post" || score?.status == "FINISHED" -> when { det.isBlank() || det == "FT" -> "Full time"; det == "AET" -> "After extra time"; else -> det }
                        det == "HT" -> "Half time"
                        else -> det.ifEmpty { "Live" }
                    }
                    Text(state, color = if (live) C.lime else C.fg2, fontFamily = Inter, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                } else {
                    Text(if (e.isTbc) "TBC" else hm(e.start), style = Digits, color = C.fg, fontSize = 40.sp, maxLines = 1)
                    Text(dayWord(e.start, now), color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                }
            }
            val team = if (i == 0) d?.home else d?.away
            val name = e.teams.getOrNull(if (i == 0) 0 else 1) ?: team?.name.orEmpty()
            Column(Modifier.width(104.dp).clickable { onTeam(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                if (!team?.logo.isNullOrBlank()) AsyncImage(team!!.logo, null, Modifier.size(52.dp))
                else Box(Modifier.size(52.dp).clip(CircleShape).background(C.tile2))
                Text(short(name), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    // the scorers under each side
    if (d != null && started && !hidden && d.events.any { it.kind in GOALS }) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(d.scorers(true), Modifier.weight(1f), color = C.fg2, fontFamily = Inter, fontSize = 12.sp, textAlign = TextAlign.Center)
            Box(Modifier.padding(horizontal = 6.dp).padding(top = 1.dp)) { Ball(12.dp, C.fg3) }
            Text(d.scorers(false), Modifier.weight(1f), color = C.fg2, fontFamily = Inter, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TabStrip(tabs: List<String>, sel: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tabs.forEach { t ->
                val on = t == sel
                Column(Modifier.clickable { onPick(t) }.padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, color = if (on) C.fg else C.fg3, fontFamily = Inter, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp)
                    Box(Modifier.padding(top = 8.dp).width(if (on) 28.dp else 0.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.lime))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
}

/* ---------- small drawn marks: ball, cards, sub arrows ---------- */

@Composable
fun Ball(size: Dp, color: Color = Color(0xFFECEEE6)) = Canvas(Modifier.size(size)) {
    val r = this.size.minDimension / 2; val c = center
    drawCircle(color, r)
    val dark = Color(0xFF0A0C08)
    val p = Path()
    for (k in 0 until 5) {
        val ang = Math.toRadians(-90.0 + k * 72); val x = c.x + r * .42f * Math.cos(ang).toFloat(); val y = c.y + r * .42f * Math.sin(ang).toFloat()
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        drawLine(dark, Offset(x, y), Offset(c.x + r * .92f * Math.cos(ang).toFloat(), c.y + r * .92f * Math.sin(ang).toFloat()), r * .14f)
    }
    p.close(); drawPath(p, dark)
}
@Composable private fun CardMark(red: Boolean, w: Dp = 11.dp) = Box(Modifier.size(w, w * 1.4f).clip(RoundedCornerShape(2.dp)).background(Color(if (red) CARD_RED else CARD_YELLOW)))
@Composable
private fun SubMark(size: Dp = 16.dp) = Canvas(Modifier.size(size)) {
    val s = this.size.width; val w = s * .11f
    val out = Color(0xFFFF5C5C); val inn = Color(0xFF7FD94A)
    drawLine(out, Offset(s * .3f, s * .12f), Offset(s * .3f, s * .7f), w, StrokeCap.Round)
    drawLine(out, Offset(s * .12f, s * .52f), Offset(s * .3f, s * .72f), w, StrokeCap.Round); drawLine(out, Offset(s * .48f, s * .52f), Offset(s * .3f, s * .72f), w, StrokeCap.Round)
    drawLine(inn, Offset(s * .7f, s * .88f), Offset(s * .7f, s * .3f), w, StrokeCap.Round)
    drawLine(inn, Offset(s * .52f, s * .48f), Offset(s * .7f, s * .28f), w, StrokeCap.Round); drawLine(inn, Offset(s * .88f, s * .48f), Offset(s * .7f, s * .28f), w, StrokeCap.Round)
}
@Composable
private fun EventMark(kind: String) = when (kind) {
    "goal", "pen" -> Ball(16.dp)
    "own" -> Ball(16.dp, Color(0xFFFF8A8A))
    "penmiss" -> Box(Modifier.size(16.dp).border(1.5.dp, C.fg3, CircleShape), contentAlignment = Alignment.Center) { Text("✕", color = C.fg3, fontSize = 9.sp) }
    "yellow" -> CardMark(false)
    "red" -> CardMark(true)
    "sub" -> SubMark()
    else -> Unit
}

@Composable private fun Tile(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.tile).padding(12.dp), content = content)
@Composable private fun TileTitle(t: String) = Text(t, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = C.fg, fontFamily = Inter,
    fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)

/* ---------- Facts ---------- */

@Composable
private fun FactsTab(e: Event, now: Instant, d: MatchData, hc: Color, ac: Color) {
    if (!d.started) {
        if (d.homeSide == null) Text("Lineups usually appear about an hour before kick-off.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        if (d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty()) Tile { TileTitle("Form"); FormRows(d) }
    }
    val shown = d.events.filter { it.kind != "90" || d.state == "in" }
    if (shown.isNotEmpty()) Tile {
        shown.forEach { ev ->
            when (ev.kind) {
                "ht", "ft", "90" -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text("${if (ev.kind == "ht") "HT" else if (ev.kind == "ft") "FT" else "90'"}  ${ev.score}", Modifier.clip(RoundedCornerShape(10.dp)).background(C.tile2)
                        .padding(horizontal = 12.dp, vertical = 3.dp), style = Digits, color = C.fg2, fontSize = 13.sp, letterSpacing = .6.sp)
                }
                else -> TimelineRow(ev)
            }
        }
    } else if (d.started) Text("Nothing in the timeline yet.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
    val info = listOfNotNull(
        d.venue.takeIf { it.isNotBlank() },
        d.attendance?.let { "%,d fans".format(it) },
        d.referee.takeIf { it.isNotBlank() }?.let { "Referee: $it" },
        "${dayWord(e.start, now)} · ${hm(e.start)}, your time",
    )
    Tile { info.forEach { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) } }
}

@Composable
private fun TimelineRow(ev: MpEvent) {
    val isGoal = ev.kind in GOALS
    val main = surname(ev.player).ifEmpty { ev.player }
    val sub = when (ev.kind) { "sub" -> surname(ev.other); "goal", "pen", "own" -> ev.score + if (ev.kind == "own") " · own goal" else if (ev.kind == "pen") " · penalty" else ""
        "penmiss" -> "Penalty ${ev.other.lowercase()}"; else -> "" }
    @Composable fun words(alignEnd: Boolean) = Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(main, color = C.fg, fontFamily = Inter, fontWeight = if (isGoal) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
        if (sub.isNotEmpty()) Text(sub, color = if (ev.kind == "sub") Color(0xFFD9797A) else C.fg3, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (ev.home) { words(true); Spacer(Modifier.width(8.dp)); EventMark(ev.kind) }
        }
        Text(ev.minute, Modifier.width(48.dp), style = Digits, color = C.fg2, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!ev.home) { EventMark(ev.kind); Spacer(Modifier.width(8.dp)); words(false) }
        }
    }
}

/* ---------- Lineup ---------- */

@Composable
private fun LineupTab(e: Event, now: Instant, d: MatchData, hc: Color, ac: Color, hidden: Boolean) {
    val h = d.homeSide; val a = d.awaySide
    if (h == null || a == null) {
        // before ESPN has the real teams, a Premier League match shows Touchline Wire's predicted elevens and who's missing
        if (e.code == "ENG" && !e.started(now)) { PredictedLineups(d, hc, ac); return }
        Text(if (e.started(now)) "ESPN hasn't published the lineups for this match." else "Lineups usually appear about an hour before kick-off.",
            color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    Pitch(d.home, h, d.away, a, hc, ac, hidden)
    Spacer(Modifier.height(10.dp))
    Tile {
        TileTitle("Bench")
        Row {
            listOf(h to hc, a to ac).forEach { (s, c) ->
                Column(Modifier.weight(1f)) { s.bench.forEach { p -> BenchRow(p, c, hidden) } }
            }
        }
    }
}

/** Both elevens on one pitch: the away side attacking down from the top, the home side up from the bottom. */
@Composable
private fun Pitch(home: MpTeam, h: MpSide, away: MpTeam, a: MpSide, hc: Color, ac: Color, hidden: Boolean) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PITCH)) {
        PitchHead(away, a.formation)
        Box(Modifier.fillMaxWidth().height(624.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val band = size.height / 12
                for (k in 0 until 12) if (k % 2 == 0) drawRect(PITCH_BAND, Offset(0f, k * band), Size(size.width, band))
                val line = Color.White.copy(alpha = .22f); val st = Stroke(1.2.dp.toPx())
                val m = 8.dp.toPx(); val w = size.width - 2 * m; val hgt = size.height - 2 * m
                drawRect(line, Offset(m, m), Size(w, hgt), style = st)
                drawLine(line, Offset(m, size.height / 2), Offset(size.width - m, size.height / 2), st.width)
                drawCircle(line, w * .14f, center, style = st)
                listOf(m to 1f, size.height - m to -1f).forEach { (y0, dir) ->
                    val bw = w * .5f; val bh = hgt * .13f
                    drawRect(line, Offset(size.width / 2 - bw / 2, if (dir > 0) y0 else y0 - bh), Size(bw, bh), style = st)
                    val sw = w * .24f; val sh = hgt * .05f
                    drawRect(line, Offset(size.width / 2 - sw / 2, if (dir > 0) y0 else y0 - sh), Size(sw, sh), style = st)
                }
            }
            Column(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                // the away side attacks down the screen: keeper at the top, and its left wing on the screen's right
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceAround) {
                    a.lines.forEach { row -> PitchRow(row.reversed(), ac, hidden) }
                }
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceAround) {
                    h.lines.reversed().forEach { row -> PitchRow(row, hc, hidden) }
                }
            }
        }
        PitchHead(home, h.formation)
    }
}

@Composable
private fun PredictedLineups(d: MatchData, hc: Color, ac: Color) {
    var got by remember(d.home.name, d.away.name) { mutableStateOf<Triple<PredictedXI?, PredictedXI?, List<Absence>>?>(null) }
    var looked by remember(d.home.name, d.away.name) { mutableStateOf(false) }
    LaunchedEffect(d.home.name, d.away.name) { got = runCatching { Touchline.forMatch(d.home.name, d.away.name) }.getOrNull(); looked = true }
    val g = got
    if (!looked) { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }; return }
    if (g == null) {
        Text("Lineups usually appear about an hour before kick-off.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val (ph, pa, out) = g
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Predicted lineups", Modifier.clip(RoundedCornerShape(10.dp)).background(C.lime).padding(horizontal = 10.dp, vertical = 3.dp),
            color = C.onLime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("  The real teams replace these about an hour before kick-off.", color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
    }
    if (ph != null && pa != null) Pitch(d.home, ph.side(), d.away, pa.side(), hc, ac, false)
    else listOfNotNull(ph?.let { "${short(d.home.name)}: ${it.formation}" }, pa?.let { "${short(d.away.name)}: ${it.formation}" }).forEach {
        Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
    }
    Spacer(Modifier.height(10.dp))
    Tile {
        TileTitle("Injuries and suspensions")
        listOf(d.home to hc, d.away to ac).forEach { (t, c) ->
            val list = out.filter { twSame(it.team, t.name) }
            Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(8.dp))
                Text(short(t.name), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            if (list.isEmpty()) Text("Nobody missing", color = C.fg3, fontFamily = Inter, fontSize = 12.5.sp, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            list.forEach { x ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(x.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        val note = listOfNotNull(x.news.takeIf { it.isNotBlank() }, x.deputy.takeIf { it.isNotBlank() }?.let { "Likely replaced by $it" })
                        if (note.isNotEmpty()) Text(note.joinToString(" · "), color = C.fg3, fontFamily = Inter, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    val (label, col) = when (x.status) {
                        "suspended" -> "Suspended" to Color(0xFFE5383B)
                        "doubtful" -> "${x.chance ?: 50}% fit" to Color(0xFFF5CB2B)
                        else -> "Out" to Color(0xFFFF6B6B)
                    }
                    Text(label, Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = .18f)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = col, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                }
            }
        }
    }
    Text("Predicted lineups and injuries from Touchline Wire (touchlinewire.com)", color = C.fg3, fontFamily = Inter, fontSize = 11.sp,
        modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun PitchHead(t: MpTeam, formation: String) = Row(Modifier.fillMaxWidth().background(PITCH_HEAD).padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically) {
    if (t.logo.isNotBlank()) AsyncImage(t.logo, null, Modifier.size(20.dp))
    Text(short(t.name), Modifier.padding(start = 8.dp).weight(1f), color = Color.White, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Text(formation, style = Digits, color = Color(0xFFCFE3D4), fontSize = 15.sp)
}

@Composable
private fun PitchRow(row: List<MpPlayer>, color: Color, hidden: Boolean) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    row.forEach { p ->
        Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // the shirt in the middle, its marks at the corners around it: card top left, goal top right, minute off bottom right
            Box(Modifier.size(64.dp, 38.dp)) {
                Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(color).border(2.dp, Color.White.copy(alpha = .85f), CircleShape),
                    contentAlignment = Alignment.Center) { Text(p.number, style = Digits, color = onColor(color), fontSize = if (p.number.length > 2) 11.sp else 14.sp) }
                if (!hidden) {
                    if (p.red || p.yellow) Box(Modifier.align(Alignment.TopStart).padding(start = 9.dp)) { CardMark(p.red, 9.dp) }
                    if (p.goals > 0) Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) { Ball(14.dp) }
                    p.offAt?.let { m -> Text(m, Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0A0C08))
                        .padding(horizontal = 3.dp), color = Color(0xFFFF8A8A), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp) }
                }
            }
            Text(surname(p.name), color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BenchRow(p: MpPlayer, color: Color, hidden: Boolean) = Row(Modifier.padding(vertical = 3.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text(p.number, style = Digits, color = onColor(color), fontSize = 11.sp) }
    Text(p.name, Modifier.padding(start = 6.dp).weight(1f, fill = false), color = if (p.onAt != null) C.fg else C.fg2, fontFamily = Inter, fontSize = 12.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    if (!hidden) p.onAt?.let { m -> Spacer(Modifier.width(4.dp)); SubMark(11.dp); Text(m, color = C.lime, fontFamily = Inter, fontSize = 10.sp) }
}

/* ---------- Stats ---------- */

@Composable
private fun StatsTab(d: MatchData, hc: Color, ac: Color) {
    d.possession?.let { (ph, pa) ->
        Tile {
            Text("Ball possession", Modifier.fillMaxWidth(), color = C.fg2, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(ph.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(hc).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text("${Math.round(ph)}%", style = Digits, color = onColor(hc), fontSize = 15.sp) }
                Box(Modifier.weight(pa.toFloat().coerceAtLeast(1f)).fillMaxHeight().background(ac).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("${Math.round(pa)}%", style = Digits, color = onColor(ac), fontSize = 15.sp) }
            }
        }
    }
    Tile {
        TileTitle("Top stats")
        d.stats.forEach { s ->
            val hWins = if (s.lowerBetter) s.hv < s.av else s.hv > s.av
            val aWins = if (s.lowerBetter) s.av < s.hv else s.av > s.hv
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatPill(s.home, if (hWins) hc else null)
                Text(s.label, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontSize = 14.sp, textAlign = TextAlign.Center)
                StatPill(s.away, if (aWins) ac else null)
            }
        }
    }
}
@Composable
private fun StatPill(v: String, fill: Color?) = Text(v, Modifier.widthIn(min = 36.dp).clip(RoundedCornerShape(12.dp)).background(fill ?: Color.Transparent)
    .padding(horizontal = 9.dp, vertical = 3.dp), style = Digits, color = fill?.let(::onColor) ?: C.fg, fontSize = 15.sp, textAlign = TextAlign.Center)

/* ---------- Commentary ---------- */

@Composable
private fun CommentaryTab(d: MatchData) {
    var all by remember { mutableStateOf(false) }
    val list = if (all) d.commentary else d.commentary.take(40)
    list.forEach { c ->
        val goal = c.kind == "goal"
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (goal) C.lime.copy(alpha = .1f) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 8.dp)) {
            Text(c.minute, Modifier.width(46.dp), style = Digits, color = C.fg2, fontSize = 14.sp, maxLines = 1)
            Box(Modifier.width(22.dp).padding(top = 1.dp)) { if (c.kind.isNotEmpty()) EventMark(c.kind) }
            Text(c.text, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = if (goal) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp, lineHeight = 18.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    }
    if (!all && d.commentary.size > 40) Text("Show all ${d.commentary.size}", Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.tile2)
        .clickable { all = true }.padding(horizontal = 14.dp, vertical = 8.dp), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

/* ---------- Table ---------- */

@Composable
private fun TableTab(e: Event, d: MatchData, hc: Color, ac: Color, onPage: (String) -> Unit) {
    Tile {
        if (d.tableName.isNotBlank()) Text(d.tableName + if (e.code in ESPN_LEAGUE) "  ›" else "", Modifier.padding(bottom = 6.dp)
            .clickable(enabled = e.code in ESPN_LEAGUE) { onPage("league:${e.code}") }, color = if (e.code in ESPN_LEAGUE) C.lime else C.fg2,
            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            listOf("#" to 26, "Team" to 0, "P" to 30, "GD" to 40, "Pts" to 36).forEach { (t, w) ->
                Text(t, if (w == 0) Modifier.weight(1f) else Modifier.width(w.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
                    textAlign = if (w == 0) TextAlign.Start else TextAlign.Center)
            }
        }
        d.table.forEach { r ->
            val c = when (r.team) { d.home.name -> hc; d.away.name -> ac; else -> null }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c?.copy(alpha = .22f) ?: Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(32.dp).background(c ?: Color.Transparent))
                Row(Modifier.padding(start = 5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.rank, Modifier.width(26.dp), color = C.fg2, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(r.team, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = if (c != null) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.played, Modifier.width(30.dp), color = C.fg2, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(r.gd, Modifier.width(40.dp), color = C.fg2, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Text(r.points, Modifier.width(36.dp), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/* ---------- H2H ---------- */

@Composable
private fun H2hTab(d: MatchData, hc: Color, ac: Color) {
    if (d.meetings.isNotEmpty()) Tile {
        TileTitle("Last ${d.meetings.take(5).size} meetings")
        val ms = d.meetings.take(5)
        fun goals(m: Meeting, team: String) = (if (m.home == team) m.homeScore else m.awayScore).toIntOrNull() ?: 0
        val w = ms.count { goals(it, d.home.name) > goals(it, d.away.name) }
        val l = ms.count { goals(it, d.home.name) < goals(it, d.away.name) }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(Triple("$w", short(d.home.name), hc), Triple("${ms.size - w - l}", "Draws", C.fg), Triple("$l", short(d.away.name), ac)).forEach { (n, t, c) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n, style = Digits, color = c, fontSize = 34.sp); Text(t, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                }
            }
        }
        ms.forEach { m ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.date?.let { dayMonYear(it) } ?: "", Modifier.width(78.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                Text(short(m.home), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${m.homeScore} - ${m.awayScore}", Modifier.width(56.dp), style = Digits, color = C.fg, fontSize = 15.sp, textAlign = TextAlign.Center)
                Text(short(m.away), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty()) Tile { TileTitle("Form before this match"); FormRows(d) }
}

@Composable
private fun FormRows(d: MatchData) {
    listOf(d.home to d.homeForm, d.away to d.awayForm).forEach { (t, f) ->
        Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.logo.isNotBlank()) AsyncImage(t.logo, null, Modifier.size(20.dp))
            Text(short(t.name), Modifier.padding(start = 8.dp).weight(1f), color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FormChips(f.map { it.result }.reversed())                       // oldest left, newest right
        }
    }
}
