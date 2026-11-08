package app.sportclock

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The Scores tab, FotMob style: one page per day, swiped left and right, from two weeks back to two weeks ahead.
 * Each page lists every game in our competitions that day with its score, your teams first. ESPN's scoreboards supply
 * the games; our own events keep their ids, so following, alerts and the match sheet all still line up.
 */

/** A game on a day page, with each side's name and badge as ESPN has them. */
data class DayGame(val event: Event, val score: Score?, val home: String = "", val away: String = "", val homeLogo: String = "", val awayLogo: String = "")

private val LIVE = setOf("IN_PLAY", "PAUSED")

internal fun espnScore(m: EspnMatch) = Score(
    when (m.state) { "in" -> if (m.detail.contains("HT", true) || m.detail.contains("Half", true)) "PAUSED" else "IN_PLAY"; "post" -> "FINISHED"; else -> "TIMED" },
    m.homeScore, m.awayScore, null, m.detail, m.id, m.league)

/**
 * One day's games: our football events on that day, each with its score from ESPN when the boards have it, then every
 * other game on the boards. [boards] is ESPN's matches per competition code.
 */
internal fun dayGames(day: LocalDate, events: List<Event>, boards: Map<String, List<EspnMatch>>): List<DayGame> {
    val used = HashSet<String>()
    val ours = events.filter { it.sport == Sport.FOOTBALL && localDate(it.start) == day }.map { e ->
        val m = boards[e.code]?.let { matchEspn(e, it) }?.takeIf { it.id !in used }
        m?.let { used += it.id }
        DayGame(e, m?.let(::espnScore), m?.home ?: short(e.teams.getOrElse(0) { e.title }), m?.away ?: short(e.teams.getOrElse(1) { "" }),
            m?.homeSide?.logo.orEmpty(), m?.awaySide?.logo.orEmpty())
    }
    val others = boards.flatMap { (code, ms) ->
        ms.filter { it.id !in used && localDate(it.start) == day }.map { m ->
            DayGame(Event((if (cup(code) != null) "CUP|" else "ESPN|") + m.id, Sport.FOOTBALL, code, series(code)?.name ?: cup(code)?.name ?: code,
                "${m.home} v ${m.away}", "Kick-off", m.start, 120, "", "confirmed", listOf(m.home, m.away), null, null, null), espnScore(m),
                m.home, m.away, m.homeSide?.logo.orEmpty(), m.awaySide?.logo.orEmpty())
        }
    }
    return (ours + others).distinctBy { it.event.id }.sortedBy { it.event.start }
}

object DayBoards {
    private val cache = HashMap<LocalDate, Pair<Long, List<DayGame>>>()

    fun cached(day: LocalDate) = synchronized(cache) { cache[day]?.second }

    /** A finished day keeps; today is asked again every minute while a game is on. */
    internal fun maxAgeMs(day: LocalDate, today: LocalDate, live: Boolean) = when {
        live -> 60_000L
        day.isBefore(today.minusDays(1)) -> 24 * 3600_000L
        day.isAfter(today.plusDays(1)) -> 30 * 60_000L
        else -> 5 * 60_000L
    }

    suspend fun load(day: LocalDate, events: List<Event>, now: Instant): List<DayGame> {
        synchronized(cache) { cache[day] }?.let { (t, g) ->
            if (System.currentTimeMillis() - t < maxAgeMs(day, localDate(now), g.any { it.score?.status in LIVE })) return g
        }
        // a day on the phone's clock can span two of ESPN's (US Eastern) dates
        val dates = listOf(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)).map(::espnDate).distinct()
        val boards = coroutineScope {
            ESPN_COMP.entries.flatMap { (code, slug) -> dates.map { d -> async { code to runCatching { espnScoreboard(slug, d) }.getOrNull() } } }.awaitAll()
        }
        if (boards.all { it.second == null }) error("no scoreboards")
        val games = dayGames(day, events, boards.groupBy({ it.first }, { it.second.orEmpty() }).mapValues { (_, l) -> l.flatten().distinctBy { it.id } })
        synchronized(cache) { cache[day] = System.currentTimeMillis() to games }
        return games
    }
}

private val DOW = DateTimeFormatter.ofPattern("EEE", Locale.UK)

/** "Yesterday", "Today", "Tomorrow", else the weekday. */
internal fun dayTab(d: LocalDate, today: LocalDate) = when (d) {
    today.minusDays(1) -> "Yesterday"; today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> DOW.format(d)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScoresDays(st: AppState, events: List<Event>, now: Instant, scores: Scores, onOpen: (Event) -> Unit, onTeam: (String) -> Unit, startOffset: Int = 0) {
    val today = localDate(now)
    val days = remember(today) { (-14..14).map { today.plusDays(it.toLong()) } }
    val pager = rememberPagerState(initialPage = 14 + startOffset) { days.size }
    val scope = rememberCoroutineScope()
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = 12 + startOffset)
    // the strip follows the swipe, keeping the chosen day near the middle
    LaunchedEffect(pager.currentPage) { strip.animateScrollToItem((pager.currentPage - 2).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(days) { i, d ->
                val on = i == pager.currentPage
                Column(Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.lime else Color.Transparent)
                    .clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayTab(d, today), color = if (on) C.onLime else if (d == today) C.lime else C.fg, fontFamily = Inter,
                        fontWeight = if (on || d == today) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                    Text("${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}", color = if (on) C.onLime else C.fg2,
                        fontFamily = Inter, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { days[it].toString() }) { i ->
            DayPage(days[i], today, st, events, scores, onOpen, onTeam)
        }
    }
}

@Composable
private fun DayPage(day: LocalDate, today: LocalDate, st: AppState, events: List<Event>, scores: Scores, onOpen: (Event) -> Unit, onTeam: (String) -> Unit) {
    var games by remember(day) { mutableStateOf(DayBoards.cached(day)) }
    var failed by remember(day) { mutableStateOf(false) }
    LaunchedEffect(day, events) {
        while (true) {
            runCatching { DayBoards.load(day, events, Instant.now()) }
                .onSuccess { g -> games = g; failed = false; g.forEach { x -> x.score?.let { ExtraScores.map[x.event.id] = it } } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failed = true; Diag.log("day $day: $it") }
            if (day != localDate(Instant.now())) break
            delay(if (games.orEmpty().any { it.score?.status in LIVE }) 60_000 else 300_000)
        }
    }
    val now = Instant.now()
    val list = games.orEmpty()
    val yours = list.filter { followed(it.event, st.follows) }
    val rest = list.filter { it !in yours }
    val groups = rest.groupBy { it.event.code }.entries.sortedBy { compRank(it.key) }
    fun score(g: DayGame) = g.score ?: scores.matches[g.event.id]

    LazyColumn(Modifier.fillMaxSize()) {
        st.follows.filter { it.startsWith("t:") && it.substring(2) !in NFL_TEAMS }.takeIf { it.isNotEmpty() && day == today }?.let { ts ->
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ts.sorted().forEach { t -> AssistChip(onClick = { onTeam(t.substring(2)) }, label = { Text("${short(t.substring(2))} page ›", fontFamily = Inter) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = C.fg)) }
                }
            }
        }
        when {
            games == null && !failed -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) } }
            games == null -> item { Text("Couldn't reach ESPN's scoreboards. Trying again when you come back.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
            list.isEmpty() -> item { Text("No games in our competitions on ${DAY_LONG.format(day)}.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        }
        if (yours.isNotEmpty()) {
            item { SectionLabel("Your teams") }
            yours.forEach { g -> item(key = "y" + g.event.id) { MatchRow(g, score(g), now, showComp = true) { onOpen(g.event) } } }
        }
        groups.forEach { (code, l) ->
            item(key = "c$code") {
                Row(Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoChip(code, 20.dp); Spacer(Modifier.width(8.dp))
                    Text(series(code)?.name ?: cup(code)?.name ?: code, color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            item(key = "g$code") {
                Column(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(C.tile)) {
                    l.forEachIndexed { i, g ->
                        if (i > 0) Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(C.line))
                        MatchRow(g, score(g), now, inGroup = true) { onOpen(g.event) }
                    }
                }
            }
        }
        if (games != null) item {
            Text("Scores from ESPN · swipe for other days", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
        }
    }
}

/**
 * The order competitions are listed in on a day page, by strength and pull, always the same: the big five leagues
 * (Premier League first), then Europe, the next leagues, the Championship, and the domestic cups last.
 */
val COMP_ORDER = listOf("ENG", "ESP", "ITA", "GER", "FRA", "UCL", "UEL", "UECL", "POR", "NED", "EFL",
    "FAC", "LC", "CDR", "CIT", "DFB", "CDF", "TDP", "KNV")
fun compRank(code: String) = COMP_ORDER.indexOf(code).let { if (it < 0) COMP_ORDER.size else it }

private val DAY_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

/**
 * One game, FotMob style: the time or the state on the left (17:00, 67', HT, FT), then each side on its own line with
 * its badge and goals, the winner in bold. Spoiler-free hides the goals until the match sheet reveals them.
 */
@Composable
private fun MatchRow(g: DayGame, score: Score?, now: Instant, showComp: Boolean = false, inGroup: Boolean = false, onClick: () -> Unit) {
    val e = g.event
    val hidden = Spoilers.hidden(e)
    val st = score?.status
    val live = st in LIVE
    val done = st == "FINISHED"
    val h = score?.home; val a = score?.away
    val state = when {
        live && st == "PAUSED" -> "HT"
        live -> score?.detail?.takeIf { it.isNotBlank() && it.length <= 6 } ?: "Live"
        done -> score?.detail?.takeIf { it in setOf("AET", "FT-Pens", "Pens") } ?: "FT"
        st == "POSTPONED" || score?.detail?.contains("Postponed", true) == true -> "PP"
        else -> timeText(e)
    }
    val body = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
    Row((if (inGroup) Modifier else Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(C.tile)).then(body),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state, color = if (live) C.lime else if (done) C.fg2 else C.fg, fontFamily = Inter, fontWeight = if (live) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 13.sp, maxLines = 1)
            if (showComp) Box(Modifier.padding(top = 4.dp)) { LogoChip(e.code, 16.dp) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            listOf(Triple(g.home, g.homeLogo, h to a), Triple(g.away, g.awayLogo, a to h)).forEach { (name, logo, goals) ->
                val won = done && !hidden && goals.first != null && goals.second != null && goals.first!! > goals.second!!
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (logo.isNotBlank()) coil.compose.AsyncImage(logo, null, Modifier.size(18.dp)) else Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(C.tile2))
                    Spacer(Modifier.width(8.dp))
                    Text(name, Modifier.weight(1f), color = if (done && !won && !hidden) C.fg2 else C.fg, fontFamily = Inter,
                        fontWeight = if (won) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if ((live || done) && goals.first != null) Text(if (hidden) "–" else "${goals.first}", style = Digits, fontSize = 17.sp,
                        color = if (live) C.lime else if (won || !done) C.fg else C.fg2, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
