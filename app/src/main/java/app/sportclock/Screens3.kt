package app.sportclock

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.format.DateTimeFormatter

private val DAY_MON = DateTimeFormatter.ofPattern("d MMM")
private val DAY_MON_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy")
private fun dayMon(i: Instant) = DAY_MON.format(i.atZone(zone))

/** Loads [load] once per [key]; null while loading, and [failed] when it threw (a cancelled load is not a failure). */
@Composable
private fun <T> loaded(key: Any?, load: suspend () -> T?): Pair<T?, Boolean> {
    var v by remember(key) { mutableStateOf<T?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        runCatching { load() }.onSuccess { v = it; failed = it == null }.onFailure { if (it !is CancellationException) failed = true }
    }
    return v to failed
}

@Composable private fun Note(text: String) = Text(text, color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
@Composable private fun Loading() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit, lead: (@Composable () -> Unit)? = null) =
    FilterChip(on, onClick, label = { Text(label, fontFamily = Inter) }, leadingIcon = lead,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))

/** W / D / L as small coloured squares. */
@Composable
fun FormChips(form: List<String>) = Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    form.forEach { r ->
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(when (r) { "W" -> Color(0xFF2E7D32); "L" -> Color(0xFFB3261E); else -> C.tile2 }),
            contentAlignment = Alignment.Center) { Text(r, color = if (r == "W" || r == "L") Color.White else C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
    }
}

/** "Show score" for spoiler-free mode: the hidden thing, revealed for this session once tapped. */
@Composable
fun Reveal(key: String, content: @Composable () -> Unit) {
    if (Spoilers.on && key !in Spoilers.revealed) {
        TextButton(onClick = { Spoilers.revealed += key }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("Result hidden · tap to show", color = C.lime, fontFamily = Inter, fontSize = 14.sp)
        }
    } else content()
}

/* ======================= match sheet: form, head-to-head, buttons ======================= */

@Composable
fun MatchExtrasView(e: Event) {
    val (x, failed) = loaded(e.id) { matchExtras(e) }
    if (failed || x == null) return
    Column(Modifier.padding(top = 14.dp)) {
        if (x.homeForm.isNotEmpty() || x.awayForm.isNotEmpty()) {
            Text("Form · last five", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            listOf(x.homeName to x.homeForm, x.awayName to x.awayForm).forEach { (name, f) ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(short(name), Modifier.width(120.dp), color = C.fg, fontFamily = Inter, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FormChips(f.map { it.result }.reversed())                       // oldest left, newest right
                }
            }
        }
        if (x.meetings.isNotEmpty()) {
            Text("Head-to-head", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            if (x.series.isNotBlank()) Text(x.series, color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            x.meetings.take(5).forEach { m ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text(m.date?.let { DAY_MON_YEAR.format(it.atZone(zone)) } ?: "", Modifier.width(96.dp), color = C.fg3, fontFamily = Inter, fontSize = 13.sp)
                    Text("${short(m.home)} ${m.homeScore}–${m.awayScore} ${short(m.away)}", color = C.fg, fontFamily = Inter, fontSize = 13.sp)
                }
            }
        }
        Text("Form and head-to-head from ESPN", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Add to calendar (before it starts) and Highlights (once it has started). */
@Composable
fun DetailActions(e: Event, now: Instant) {
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!e.isTbc && !e.started(now)) OutlinedButton(onClick = {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start.toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end.toEpochMilli())
                    .putExtra(CalendarContract.Events.TITLE, if (e.sport == Sport.FOOTBALL) e.line1() else "${series(e.code)?.name ?: e.comp} · ${e.line1()}")
                    .putExtra(CalendarContract.Events.EVENT_LOCATION, e.venue)
                    .putExtra(CalendarContract.Events.DESCRIPTION, "Added from SportClock${if (e.isApprox) ". The time is approximate." else ""}"))
            }
        }) { Text("Add to calendar", color = C.lime, fontFamily = Inter) }
        if (e.started(now)) OutlinedButton(onClick = {
            val q = if (e.sport == Sport.FOOTBALL && e.teams.size == 2) "${e.teams[0]} vs ${e.teams[1]} highlights"
                    else "${series(e.code)?.name ?: e.comp} ${e.title} ${sessName(e.session)} highlights ${e.start.atZone(zone).year}"
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))) }
        }) { Text("Highlights", color = C.lime, fontFamily = Inter) }
    }
}

/** The two clubs as buttons to their team pages. */
@Composable
fun TeamLinks(e: Event, onTeam: (String) -> Unit) {
    if ((e.sport != Sport.FOOTBALL && e.sport != Sport.NFL) || e.teams.size != 2) return
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        e.teams.forEach { t -> AssistChip(onClick = { onTeam(t) }, label = { Text("${short(t)} ›", fontFamily = Inter) },
            colors = AssistChipDefaults.assistChipColors(labelColor = C.fg)) }
    }
}

/* ======================= Home: last result for your teams ======================= */

/** Full-time cards for your teams' matches that ended in the last 36 hours. */
@Composable
fun LastResults(st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>, onTeam: (String) -> Unit) {
    val mine = st.follows.filter { it.startsWith("t:") }.map { it.substring(2) }
    val recent = mine.mapNotNull { team ->
        events.lastOrNull { team in it.teams && it.over(now) && it.end.isAfter(now.minusSeconds(36 * 3600)) }?.let { team to it }
    }.distinctBy { it.second.id }
    recent.forEach { (team, e) ->
        val sc = scores[e.id]?.takeIf { it.status == "FINISHED" && it.home != null }
        if (sc != null) key(e.id) { Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile)
            .clickable { onTeam(team) }.padding(16.dp)) {
            Text("FULL TIME · ${dayWord(e.start, now)}", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
            Reveal("ft:${e.id}") {
                Text("${short(e.teams[0])} ${sc.home}–${sc.away} ${short(e.teams[1])}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.padding(top = 4.dp))
                val (sheet, _) = loaded(e.id) { sc.league?.let { lg -> sc.espnId?.let { id -> espnSheet(lg, id) } } }
                sheet?.goals?.takeIf { it.isNotEmpty() }?.let { gs ->
                    Text(gs.joinToString(" · ") { "${it.text.substringBefore(" (")} ${it.minute}" }, color = C.fg2, fontFamily = Inter, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${short(team)} page ›", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}

/* ======================= team page ======================= */

@Composable
fun TeamScreen(team: String, events: List<Event>, now: Instant, scores: Map<String, Score>, onBack: (() -> Unit)?, onOpen: (Event) -> Unit,
               header: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val league = remember(team, events) { leagueFor(team, events) }
    val (info, failed) = loaded(team) { league?.let { lg -> espnTeamId(lg, team, ctx)?.let { teamInfo(lg, it) } } }
    // the crest from the phone's copy shows at once; the page saves it the first time it loads
    val crest = info?.logo?.takeIf { it.isNotBlank() } ?: remember(team) { Repo.prefs(ctx).getString("logo:$team", null) }
    LaunchedEffect(info?.logo) { info?.logo?.takeIf { it.isNotBlank() }?.let { Repo.prefs(ctx).edit().putString("logo:$team", it).apply() } }
    val (stories, _) = loaded("news:$team") { news(setOf("t:$team")).take(5) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var all by rememberSaveable { mutableStateOf(false) }
    val next = remember(team, events, now) { events.filter { team in it.teams && !it.over(now) }.take(2) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        if (onBack != null) item { TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) } }
        header?.let { h -> item { h() } }
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (crest != null) { AsyncImage(crest, null, Modifier.size(56.dp)); Spacer(Modifier.width(14.dp)) }
                Column {
                    Text(info?.name ?: team, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    info?.standing?.takeIf { it.isNotBlank() }?.let { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 14.sp) }
                }
            }
        }
        if (info != null && info.results.isNotEmpty()) item {
            Row(Modifier.padding(start = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("League form ", color = C.fg2, fontFamily = Inter, fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                Reveal("form:$team") { FormChips(info.form().reversed()) }
            }
        }
        if (next.isNotEmpty()) {
            item { SectionLabel("Next up") }
            items(next, key = { "n" + it.id }) { e -> EventRow(e, now, scores[e.id], onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) }
        }
        when {
            failed && info == null -> item { Note("Couldn't load ${short(team)}'s results. Check your connection.") }
            info == null -> items(5) { Skeleton(48.dp, Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
            else -> {
                item { Spacer(Modifier.height(10.dp)); Segments(listOf("Results", "Fixtures"), tab) { tab = it; all = false }; Spacer(Modifier.height(6.dp)) }
                val list = if (tab == 0) info.results else info.fixtures
                items(if (all) list else list.take(8), key = { "g" + tab + it.date }) { g ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile)
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayMon(g.date), Modifier.width(58.dp), color = C.fg3, fontFamily = Inter, fontSize = 13.sp)
                        if (g.done) {
                            Box(Modifier.weight(1f)) {
                                Reveal("g:${g.date}") {
                                    Text("${short(g.home)} ${g.homeScore}–${g.awayScore} ${short(g.away)}", color = C.fg, fontFamily = Inter, fontSize = 15.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            Text("${short(g.home)} v ${short(g.away)}", Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hm(g.date), style = Digits, fontSize = 16.sp, color = C.fg2)
                        }
                    }
                }
                if (!all && list.size > 8) item { TextButton(onClick = { all = true }, modifier = Modifier.padding(start = 12.dp)) { Text("Show all ${list.size}", color = C.lime, fontFamily = Inter) } }
                if (list.isEmpty()) item { Note(if (tab == 0) "No results yet this season." else "No fixtures listed.") }
                item { Note("League games from ESPN; cup games are in the fixture list above.") }
            }
        }
        if (!stories.isNullOrEmpty()) {
            item { SectionLabel("News") }
            items(stories, key = { "s" + it.link }) { s ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile)
                    .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link))) } }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    s.image?.let { AsyncImage(it, null, Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
                    Text(s.title, color = C.fg, fontFamily = Inter, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/* ======================= racing results ======================= */

@Composable
fun RacingView() {
    var code by rememberSaveable { mutableStateOf("F1") }
    var f1 by rememberSaveable { mutableIntStateOf(1) }                 // 0 results, 1 live, 2 standings
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("F1", "INDY", "NAS", "MGP").forEach { c -> Chip(series(c)?.name ?: c, c == code, { code = c }) { LogoChip(c, 18.dp) } }
        }
        if (code == "F1") Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Results", "Live", "Standings").forEachIndexed { i, l -> Chip(l, f1 == i, { f1 = i }) }
        }
        when {
            code == "F1" && f1 == 1 -> F1LiveView()
            code == "F1" && f1 == 2 -> F1View()
            else -> WeekendView(code)
        }
    }
}

@Composable
fun WeekendView(code: String) {
    val (w, failed) = loaded(code) { if (code == "MGP") motoGp() else espnRacing(code) }
    var pick by remember(code) { mutableStateOf<Int?>(null) }
    when {
        failed && w == null -> Note("Couldn't load results. Check your connection.")
        w == null -> Loading()
        else -> {
            val chosen = pick ?: w.sessions.indexOfLast { it.state == "post" || it.state == "in" }.takeIf { it >= 0 } ?: 0
            val s = w.sessions.getOrNull(chosen)
            Column {
                Text(w.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 20.dp, top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.sessions.forEachIndexed { i, x -> Chip(x.name, i == chosen, { pick = i }) }
                }
                if (s != null) SessionRows(code, w, s)
            }
        }
    }
}

@Composable
private fun SessionRows(code: String, w: RaceWeekend, s: RaceSession) {
    val (fetched, failed) = loaded(s.id ?: "${w.name}/${s.name}") { if (s.rows == null && s.id != null && s.state == "post") motoGpRows(s.id) else s.rows }
    val rows = s.rows ?: fetched
    when {
        s.state == "cancelled" -> Note("${s.name} was cancelled.")
        s.state == "pre" -> Note("${s.name} hasn't run yet${s.start?.let { " · ${dayWord(it, Instant.now())} ${hm(it)}" } ?: ""}.")
        rows == null && !failed -> Loading()
        rows.isNullOrEmpty() -> Note("No results published for ${s.name}.")
        else -> LazyColumn {
            item { Reveal("race:$code:${w.name}:${s.name}") { Spacer(Modifier.height(0.dp)) } }
            if (!Spoilers.on || "race:$code:${w.name}:${s.name}" in Spoilers.revealed) items(rows, key = { "r" + it.pos + it.name }) { r ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile)
                    .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.pos}", Modifier.width(32.dp), style = Digits, fontSize = 19.sp, color = if (r.pos == 1) C.lime else C.fg2)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (r.team.isNotBlank()) Text(r.team, color = C.fg2, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(r.time, color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                }
            }
            item { Note(if (code == "MGP") "Results from MotoGP.com" else "Results from ESPN${if (code == "F1") "; race times from Jolpica when it has the race" else ""}") }
        }
    }
}

/* ======================= UFC card ======================= */

@Composable
fun UfcView() {
    val (card, failed) = loaded("ufc") { ufcCard() }
    when {
        failed && card == null -> Note("Couldn't load the card. Check your connection.")
        card == null -> Loading()
        else -> LazyColumn {
            item {
                Text(card.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
                card.date?.let { Text("${dayWord(it, Instant.now())} · ${hm(it)} your time", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp)) }
                Spacer(Modifier.height(8.dp))
            }
            items(card.bouts.withIndex().toList(), key = { "b" + it.index }) { (i, b) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile).padding(12.dp)) {
                    Text((if (i == 0) "MAIN EVENT · " else "") + b.weight.uppercase(), color = if (i == 0) C.lime else C.fg3, fontFamily = Inter,
                        fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp)
                    if (b.state == "post" && b.winner != null) Reveal("bout:${card.name}:$i") {
                        val (w, l) = if (b.winner == 0) b.a to b.b else b.b to b.a
                        Text("$w def. $l", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(listOf(b.method, if (b.round > 0) "R${b.round} ${b.clock}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                            color = C.lime, fontFamily = Inter, fontSize = 13.sp)
                    } else {
                        Text("${b.a} vs ${b.b}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("${b.aRecord} · ${b.bRecord}${if (b.state == "in") " · on now" else ""}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                    }
                }
            }
            item { Note("Card and results from ESPN") }
        }
    }
}
