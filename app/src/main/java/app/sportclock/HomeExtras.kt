package app.sportclock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ======================= the NEXT UP card through a match ======================= */

/** Starting elevens found before kick-off, by event id, so the card can say "Lineups out" without asking twice. */
object Lineups { val ready = mutableStateMapOf<String, MatchSheet>() }

/** A football match within 75 minutes of kick-off: asks ESPN every 3 minutes until both elevens are out. */
@Composable
fun rememberLineups(e: Event, now: Instant): MatchSheet? {
    val near = e.sport == Sport.FOOTBALL && e.teams.size == 2 && !e.isTbc && ESPN_COMP.containsKey(e.code) &&
        Duration.between(now, e.start).toMinutes() in 0..75
    if (near) LaunchedEffect(e.id) {
        while (Lineups.ready[e.id] == null) {
            runCatching { Looks.espnMatch(e)?.let { m -> espnSheet(m.league, m.id) } }.getOrNull()
                ?.takeIf { it.home != null && it.away != null }?.let { Lineups.ready[e.id] = it }
            if (Lineups.ready[e.id] == null) kotlinx.coroutines.delay(180_000)
        }
    }
    return Lineups.ready[e.id]
}

/** "4-2-3-1 · Fernandes starts": your club's shape and whether your player is in the eleven (the card's label says "Lineups out"). */
fun lineupLine(sheet: MatchSheet, e: Event, token: String?, fav: Person?): String {
    val me = token?.takeIf { it.startsWith("t:") }?.substring(2)
    val side = when (me) { null -> null; e.teams.getOrNull(0) -> sheet.home; else -> sheet.away }
    val parts = mutableListOf<String>()
    side?.formation?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (side != null && fav != null) {
        val last = personKey(fav.name.substringAfterLast(' '))
        fun has(ps: List<Player>) = ps.any { personKey(it.name) == personKey(fav.name) || personKey(it.name.substringAfterLast(' ')) == last }
        when { has(side.starters) -> parts += "${fav.name.substringAfterLast(' ')} starts"; has(side.subs) -> parts += "${fav.name.substringAfterLast(' ')} on the bench" }
    }
    return parts.joinToString(" · ").ifEmpty { "Both elevens are out · tap to see" }
}

/** The YouTube search for a match's or session's highlights; opened in YouTube itself. */
fun highlightsUrl(e: Event): String {
    val q = if (e.teams.size == 2) "${e.teams[0]} vs ${e.teams[1]} highlights"
        else "${series(e.code)?.name ?: e.comp} ${e.title} ${sessName(e.session)} highlights ${e.start.atZone(zone).year}"
    return "https://www.youtube.com/results?search_query=" + Uri.encode(q)
}

/** In place of the countdown ring while it's on: the match minute ("67'", "HT"), or how long a session has run. */
@Composable
fun LiveDisc(e: Event, score: Score?, now: Instant, modifier: Modifier = Modifier) {
    val mins = Duration.between(e.start, now).toMinutes().coerceAtLeast(0)
    val frac = (mins.toFloat() / e.mins.coerceAtLeast(1)).coerceIn(0f, 1f)
    val minute = score?.detail?.takeIf { it.isNotBlank() && it.length <= 7 }
    val live = C.live
    Box(modifier.size(88.dp).clip(CircleShape).background(C.bg.copy(alpha = .7f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().padding(3.dp)) {
            drawCircle(live.copy(alpha = .25f), style = Stroke(6.dp.toPx()))
            drawArc(live, -90f, 360f * frac, false, style = Stroke(6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(minute ?: untilText(mins), style = Digits, color = C.fg, fontSize = 20.sp, lineHeight = 22.sp, maxLines = 1)
            Text(if (minute != null) "live" else "in", color = live, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 14.sp)
        }
    }
}

/** "▶ Highlights" on a finished match's card. */
@Composable
fun HighlightsPill(e: Event, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Row(modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(C.lime)
        .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(highlightsUrl(e)))) } }
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▶  Highlights", color = C.onLime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/* ======================= Your teams ======================= */

/** "1st in English Premier League" -> "1st · Premier League". */
fun shortStanding(s: String): String = s.replace(" in ", " · ").replace("English ", "").replace("Spanish ", "").replace("Italian ", "")
    .replace("German ", "").replace("French ", "").replace("LALIGA", "La Liga").replace("LaLiga", "La Liga")

/** One tile per club or country you follow: badge, league place, last five, and the next game. Tap for the team page. */
@Composable
fun YourTeams(st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>, onTeam: (String) -> Unit) {
    val teams = st.follows.filter { it.startsWith("t:") }.map { it.substring(2) }.sortedBy { short(it) }
    if (teams.isEmpty()) return
    Text("YOUR TEAMS", color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.8.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(teams, key = { it }) { TeamTile(it, events, now, scores, onTeam) }
    }
}

@Composable
private fun TeamTile(team: String, events: List<Event>, now: Instant, scores: Map<String, Score>, onTeam: (String) -> Unit) {
    val ctx = LocalContext.current
    var info by remember(team) { mutableStateOf<TeamInfo?>(null) }
    LaunchedEffect(team) {
        info = runCatching { leagueFor(team, events)?.let { lg -> espnTeamId(lg, team, ctx)?.let { teamInfo(lg, it) } } }.getOrNull()
    }
    val crest = info?.logo?.takeIf { it.isNotBlank() } ?: remember(team) { Repo.prefs(ctx).getString("logo2:$team", null) }
    LaunchedEffect(info?.logo) { info?.logo?.takeIf { it.isNotBlank() }?.let { Repo.prefs(ctx).edit().putString("logo2:$team", it).apply() } }
    val next = remember(team, events, now) { events.firstOrNull { team in it.teams && !it.over(now) } }
    Column(Modifier.width(178.dp).clip(RoundedCornerShape(16.dp)).background(C.tile).clickable { onTeam(team) }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (crest != null) AsyncImage(crest, null, Modifier.size(28.dp)) else Dot(C.fg3, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(short(team), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(info?.standing?.takeIf { it.isNotBlank() }?.let(::shortStanding) ?: " ", color = C.fg2, fontFamily = Inter, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        val form = info?.form().orEmpty()
        Box(Modifier.padding(top = 6.dp).height(22.dp)) {
            when {
                Spoilers.on -> Text("Form hidden", color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                form.isNotEmpty() -> FormChips(form.reversed())                 // oldest left, newest right
            }
        }
        val line = next?.let { e ->
            val other = short(e.teams.firstOrNull { it != team } ?: e.title)
            val va = if (e.teams.firstOrNull() == team) "v" else "at"
            val sc = scores[e.id]?.takeIf { it.home != null && it.away != null && !Spoilers.hidden(e) }
            when {
                e.inProgress(now) -> "Live${sc?.let { " ${it.home}–${it.away}" } ?: ""} $va $other"
                else -> "$va $other · ${if (e.isTbc) "TBC" else "${dayWord(e.start, now)} ${hm(e.start)}"}"
            }
        } ?: "No game listed"
        Text(line, color = if (next?.inProgress(now) == true) C.live else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/* ======================= the week strip ======================= */

private val DOW3 = DateTimeFormatter.ofPattern("EEE", Locale.UK)

/** The next seven days, with a dot per series on the days the list below has something; tap a day to jump to it. */
@Composable
fun WeekStrip(list: List<Event>, now: Instant, onDay: (LocalDate) -> Unit) {
    val today = localDate(now)
    val byDay = remember(list) { list.groupBy { groupDay(it) } }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp)) {
        (0..6).forEach { i ->
            val d = today.plusDays(i.toLong())
            val has = byDay[d].orEmpty()
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = has.isNotEmpty()) { onDay(d) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(DOW3.format(d), color = if (i == 0) C.lime else C.fg2, fontFamily = Inter, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                Text("${d.dayOfMonth}", style = Digits, fontSize = 20.sp, color = if (has.isEmpty()) C.fg3 else C.fg)
                Row(Modifier.height(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    has.map { it.code }.distinct().take(3).forEach { Dot(seriesColor(it), 5.dp); Spacer(Modifier.width(2.dp)) }
                }
            }
        }
    }
}
