package app.sportclock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * A league's own page: every fixture still to come (from the data file), the results of the last two months (from
 * ESPN's month scoreboards) and the table. Opens from the Tables tab, from the league name on a match sheet, and
 * fills your tab when that is a league.
 */

/** Scores for events that aren't in the app's own score feed (a league's older results, cup games in Scores), so the match sheet can show them. */
object ExtraScores { val map: SnapshotStateMap<String, Score> = mutableStateMapOf() }

private val YM = DateTimeFormatter.ofPattern("yyyyMM")

/** A league's results for this month and last, newest first, as events with their scores. */
suspend fun leagueResults(code: String, now: Instant = Instant.now()): List<Pair<Event, Score>> = coroutineScope {
    val slug = ESPN_LEAGUE[code] ?: return@coroutineScope emptyList()
    val name = series(code)?.name ?: code
    val months = listOf(0L, 1L).map { YM.format(now.atZone(ZoneOffset.UTC).minusMonths(it)) }
    months.map { m -> async { runCatching { espnScoreboard(slug, m) }.getOrDefault(emptyList<EspnMatch>()) } }.awaitAll().flatten()
        .filter { it.state == "post" }.distinctBy { it.id }.sortedByDescending { it.start }
        .map { m ->
            Event("RES|${m.id}", Sport.FOOTBALL, code, name, "${m.home} v ${m.away}", "Kick-off", m.start, 115, "", "confirmed",
                listOf(m.home, m.away), null, null, null) to Score("FINISHED", m.homeScore, m.awayScore, null, m.detail, m.id, m.league)
        }
}

/** Fixtures, results and the table of one league, under whatever header the caller gives it. */
@Composable
fun LeagueBody(code: String, st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>,
               onOpen: (Event) -> Unit, onTeam: (String) -> Unit) {
    var seg by rememberSaveable(code) { mutableIntStateOf(0) }
    var more by rememberSaveable(code) { mutableStateOf(false) }
    val fixtures = remember(events, code, now.epochSecond / 600) { events.filter { it.code == code && !it.over(now) }.sortedBy { it.start } }
    var results by remember(code) { mutableStateOf<List<Pair<Event, Score>>?>(null) }
    LaunchedEffect(code, seg) {
        if (seg == 1 && results == null) results = runCatching { leagueResults(code) }.getOrDefault(emptyList()).also { r -> r.forEach { ExtraScores.map[it.first.id] = it.second } }
    }
    // the table names clubs ESPN's way ("AFC Bournemouth"); open the page under our own name when we know it
    val ours = remember(events, code) { events.filter { it.code == code }.flatMap { it.teams }.distinct() }
    Column(Modifier.fillMaxSize()) {
        Segments(listOf("Fixtures", "Results", "Table"), seg) { seg = it }
        Spacer(Modifier.height(8.dp))
        when (seg) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                if (fixtures.isEmpty()) item { Text("No fixtures listed yet.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
                // a whole season is hundreds of games: the next 30 first
                dayGroups(if (more) fixtures else fixtures.take(30), now, scores, onOpen)
                if (!more && fixtures.size > 30) item {
                    TextButton(onClick = { more = true }, modifier = Modifier.padding(start = 12.dp)) { Text("Show all ${fixtures.size} fixtures", color = C.lime, fontFamily = Inter) }
                }
                item { Text("Fixtures from the league's own schedule, in your time.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
            }
            1 -> LazyColumn(Modifier.fillMaxSize()) {
                val r = results
                when {
                    r == null -> items(6) { Skeleton(60.dp, Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
                    r.isEmpty() -> item { Text("Couldn't load results. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
                    else -> {
                        val today = localDate(now)
                        r.groupBy { localDate(it.first.start) }.forEach { (d, list) ->           // already newest first
                            item(key = "rh$d") { DayHeader(d, today) }
                            list.forEach { (e, s) ->
                                item(key = e.id) { EventRow(e, now, s, showDay = false, onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) }
                            }
                        }
                    }
                }
                item { Text("Results of this month and last, from ESPN.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
            }
            else -> TablesView(st, only = code, onTeam = { espn -> onTeam(ours.firstOrNull { sameTeam(it, espn) } ?: espn) })
        }
    }
}

/** The league page opened from Tables or a match sheet: a back button, the league's name and logo, then [LeagueBody]. */
@Composable
fun LeaguePage(code: String, st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>,
               onBack: () -> Unit, onOpen: (Event) -> Unit, onTeam: (String) -> Unit) = Column(Modifier.fillMaxSize().statusBarsPadding()) {
    TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoChip(code, 30.dp); Spacer(Modifier.width(12.dp))
        Column {
            Text(series(code)?.name ?: code, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            val next = events.firstOrNull { it.code == code && !it.started(now) }
            Text(next?.let { "Next: ${it.line1()} · ${dayWord(it.start, now)} ${hm(it.start)}" } ?: "Fixtures, results and the table",
                color = C.fg2, fontFamily = Inter, fontSize = 13.sp, maxLines = 1)
        }
    }
    LeagueBody(code, st, events, now, scores, onOpen, onTeam)
}

/** "Fixtures and results ›" under the Tables chips, for the league on show. */
@Composable
fun LeagueLink(code: String, onLeague: (String) -> Unit) =
    Text("${series(code)?.name ?: code} fixtures and results ›", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clickable { onLeague(code) }.padding(horizontal = 20.dp, vertical = 8.dp))
