package app.sportclock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/*
 * A league's Overview, like F1 Home: where to watch it in Kenya, this round's fixtures in your time, the top of the
 * table with your clubs in it, the top scorers and assists (ESPN), the latest highlights from the league's own YouTube
 * channel (opened in YouTube), and BBC Sport's news for it.
 */

/* ---------- where to watch in Kenya (kept by hand; checked 21 Nov 2026, Jose to confirm) ---------- */

/** Pay TV first, then anything free. SuperSport is on DStv and GOtv. Kept by hand: no free source lists Kenyan broadcasters. */
val KENYA_TV = mapOf(
    "ENG" to "SuperSport Premier League (DStv Sports package, GOtv)",
    "ESP" to "SuperSport La Liga (DStv, GOtv)",
    "UCL" to "SuperSport (DStv Sports package)",
    "UEL" to "SuperSport (DStv, GOtv)",
    "ITA" to "SuperSport Football (DStv, GOtv)",
    "GER" to "StarTimes, Azam TV or Canal+ · Bundesliga Pass app",
    "FRA" to "SuperSport (DStv)",
    "EFL" to "SuperSport (DStv)",
    "F1" to "SuperSport (DStv, GOtv) · F1 TV app",
    "MGP" to "SuperSport (DStv) · MotoGP VideoPass",
    "UFC" to "SuperSport (DStv)",
    "BOX" to "DAZN for the big Riyadh Season cards; others vary",
    "NFL" to "ESPN Africa (DStv)",
)
fun kenyaTv(code: String): String? = KENYA_TV[code]

/** "On TV in Kenya" with the channel, for league pages and match sheets. */
@Composable
fun KenyaTvLine(code: String, modifier: Modifier = Modifier) {
    val tv = kenyaTv(code) ?: return
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile2).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("📺", fontSize = 16.sp); Spacer(Modifier.width(10.dp))
        Column {
            Text("On TV in Kenya", color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
            Text(tv, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

/* ---------- data ---------- */

data class Leader(val name: String, val team: String, val value: Int, val matches: Int)

/** Top scorers and assists from ESPN's league statistics. */
suspend fun espnLeaders(code: String): Pair<List<Leader>, List<Leader>> = withContext(Dispatchers.IO) {
    val lg = ESPN_LEAGUE[code] ?: return@withContext emptyList<Leader>() to emptyList()
    val o = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/statistics"))
    val stats = o.optJSONArray("stats")
    fun list(name: String): List<Leader> {
        val st = (0 until (stats?.length() ?: 0)).mapNotNull { stats!!.optJSONObject(it) }.firstOrNull { it.optString("name") == name } ?: return emptyList()
        val ls = st.optJSONArray("leaders") ?: return emptyList()
        return (0 until ls.length()).mapNotNull { ls.optJSONObject(it) }.map { l ->
            val a = l.optJSONObject("athlete")
            Leader(a?.optString("displayName").orEmpty(), a?.optJSONObject("team")?.optString("displayName").orEmpty(), l.optDouble("value", 0.0).toInt(),
                Regex("Matches: (\\d+)").find(l.optString("displayValue"))?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }.filter { it.name.isNotBlank() }
    }
    list("goalsLeaders") to list("assistsLeaders")
}

/** Each league's own YouTube channel (by the site's name for it) and what its highlight videos are called. */
private val LEAGUE_VIDEOS = mapOf("ENG" to Triple("pl", "Premier League Highlights", "highlights"),
    "ESP" to Triple("laliga", "HIGHLIGHTS LALIGA EA SPORTS", "highlights"))
fun hasLeagueVideos(code: String) = code in LEAGUE_VIDEOS

/** The league channel's latest highlights: posted in the last ten days, else the newest few found. */
suspend fun leagueHighlights(code: String): List<YtVideo> {
    val (ch, q, word) = LEAGUE_VIDEOS[code] ?: return emptyList()
    val vs = withContext(Dispatchers.IO) { parseVideos(JSONObject(httpGet("$SITE/api/f1video?ch=$ch&q=" + Uri.encode(q)))) }
        .filter { it.title.contains(word, true) && !it.title.startsWith("🔴") }
    return vs.filter { recentAgo(it.ago) }.ifEmpty { vs.take(4) }.take(8)
}

/** BBC Sport's feed for a league. */
private val BBC_LEAGUE = mapOf("ENG" to "football/premier-league", "ESP" to "football/spanish-la-liga", "UCL" to "football/champions-league",
    "ITA" to "football/italian-serie-a", "GER" to "football/german-bundesliga", "FRA" to "football/french-ligue-one", "EFL" to "football/championship")
suspend fun leagueNews(code: String): List<Story> = BBC_LEAGUE[code]?.let { rss("https://feeds.bbci.co.uk/sport/$it/rss.xml", series(code)?.name ?: code).take(6) }.orEmpty()

/** This round: the next fixture and everything within four days of it (a matchweek runs Friday to Monday), plus any game on now. */
fun leagueRound(events: List<Event>, code: String, now: Instant): List<Event> {
    val mine = events.filter { it.code == code && !it.over(now) }.sortedBy { it.start }
    val first = mine.firstOrNull { !it.started(now) } ?: return mine.filter { it.inProgress(now) }
    return mine.filter { it.inProgress(now) || (!it.start.isBefore(first.start) && it.start.isBefore(first.start.plusSeconds(4 * 86400))) }.take(14)
}

/**
 * The same club under two sources' names, strictly: every meaningful word of the shorter name is in the other (a shared
 * prefix of four letters or more counts), so "Real Madrid CF" is Real Madrid but Manchester City is not Manchester United.
 */
fun sameClubStrict(a: String, b: String): Boolean {
    val x = nameWords(a); val y = nameWords(b)
    if (x.isEmpty() || y.isEmpty()) return a.equals(b, true)
    val (few, many) = if (x.size <= y.size) x to y else y to x
    return few.all { w -> w in many || many.any { m -> m.length >= 4 && w.length >= 4 && (m.startsWith(w) || w.startsWith(m)) } }
}

/** The table's top four and every club you follow (with the clubs either side of it), in table order. */
fun tableSnippet(rows: List<TableRow>, follows: Set<String>): List<TableRow> {
    val mine = follows.filter { it.startsWith("t:") }.map { it.substring(2) }
    val keep = rows.indices.filter { i -> i < 4 || mine.any { sameClubStrict(it, rows[i].team) } }.toMutableSet()
    keep.toList().filter { i -> i >= 4 }.forEach { i -> keep += i - 1; if (i + 1 < rows.size) keep += i + 1 }
    return keep.filter { it in rows.indices }.sorted().map { rows[it] }
}

/* ---------- the page ---------- */

@Composable
private fun <T> loadOnce(key: Any, load: suspend () -> T): T? {
    var v by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) { v = runCatching { load() }.getOrNull() }
    return v
}

@Composable
private fun Label(text: String, action: String? = null, onAction: () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        action?.let { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onAction).padding(8.dp)) }
    }

@Composable
fun LeagueOverview(code: String, st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>,
                   onOpen: (Event) -> Unit, onTeam: (String) -> Unit, onSeg: (String) -> Unit) {
    val ctx = LocalContext.current
    val round = remember(events, code, now.epochSecond / 600) { leagueRound(events, code, now) }
    val table = loadOnce("table:$code") { espnTable(code) }
    val leaders = loadOnce("leaders:$code") { espnLeaders(code) }
    val videos = if (hasLeagueVideos(code)) loadOnce("videos:$code") { leagueHighlights(code) } else null
    val stories = loadOnce("news:$code") { leagueNews(code) }
    val ours = remember(events, code) { events.filter { it.code == code }.flatMap { it.teams }.distinct() }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val mine = st.follows.filter { it.startsWith("t:") }.map { it.substring(2) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { KenyaTvLine(code, Modifier.padding(horizontal = 16.dp)) }
        item { Label("This round", "All fixtures ›") { onSeg("Fixtures") } }
        if (round.isEmpty()) item { Text("No fixtures listed yet.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(horizontal = 20.dp)) }
        dayGroups(round, now, scores, onOpen)

        table?.rows?.takeIf { it.isNotEmpty() }?.let { rows ->
            item { Label("Table", "Full table ›") { onSeg("Table") } }
            item {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile).padding(vertical = 6.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text("", Modifier.width(28.dp)); Text("Club", Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                        listOf("P", "GD", "Pts").forEach { Text(it, Modifier.width(36.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp) }
                    }
                    var last = 0
                    tableSnippet(rows, st.follows).forEach { r ->
                        if (r.rank > last + 1 && last > 0) Text("···", color = C.fg3, fontFamily = Inter, modifier = Modifier.padding(start = 40.dp))
                        last = r.rank
                        val me = mine.any { sameClubStrict(it, r.team) }
                        Row(Modifier.fillMaxWidth().background(if (me) C.lime.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { onTeam(ours.firstOrNull { sameTeam(it, r.team) } ?: r.team) }.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.rank}", Modifier.width(28.dp), color = if (me) C.lime else C.fg2, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(short(r.team), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = if (me) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            listOf(r.played, r.gd, r.points).forEachIndexed { i, v ->
                                Text(v, Modifier.width(36.dp), color = C.fg, fontFamily = Inter, fontWeight = if (i == 2) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        leaders?.let { (goals, assists) ->
            if (goals.isNotEmpty()) {
                item { Label("Top scorers") }
                item { LeaderBox(goals.take(5), "goals", mine) }
            }
            if (assists.isNotEmpty()) {
                item { Label("Most assists") }
                item { LeaderBox(assists.take(3), "assists", mine) }
            }
        }

        if (!videos.isNullOrEmpty()) {
            item { Label("Highlights", "YouTube ›") { open("https://www.youtube.com/" + if (code == "ENG") "@premierleague" else "@LaLiga") } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(videos, key = { it.id }) { v ->
                        Column(Modifier.width(220.dp).clickable { open(v.url) }) {
                            AsyncImage(v.thumb, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                            Text(v.title, color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            if (v.ago.isNotBlank()) Text(v.ago, color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { Text("From the league's own YouTube channel · opens in YouTube", color = C.fg3, fontFamily = Inter, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }

        if (!stories.isNullOrEmpty()) {
            item { Label("News") }
            items(stories, key = { "s" + it.link }) { s ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile)
                    .clickable { open(s.link) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    s.image?.let { AsyncImage(it, null, Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
                    Text(s.title, color = C.fg, fontFamily = Inter, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Text("Fixtures in your time · table, scorers and assists from ESPN · news from BBC Sport · TV list kept by hand",
            color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
    }
}

@Composable
private fun LeaderBox(list: List<Leader>, what: String, mine: List<String>) =
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile).padding(vertical = 4.dp)) {
        list.forEachIndexed { i, l ->
            val me = mine.any { sameClubStrict(it, l.team) }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(24.dp), color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Column(Modifier.weight(1f)) {
                    Text(l.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(short(l.team) + if (l.matches > 0) " · ${l.matches} games" else "", color = if (me) C.lime else C.fg2, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
                }
                Text("${l.value}", style = Digits, color = C.lime, fontSize = 22.sp)
                Text(" $what", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
            }
        }
    }
