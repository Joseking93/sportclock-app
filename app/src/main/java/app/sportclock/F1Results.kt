package app.sportclock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The F1 Results page, laid out like part 2 of the F1 app redesign Jose sent (amanbirdi, Nov 2026): a year switch and
 * four tabs. Races: pick a Grand Prix and a session, the podium, then the classification. Drivers: the standings with
 * each driver's last five finishes as chips (tap one for that race), filters for your driver and a team, and the two
 * team-mates head to head. Teams: the constructors with their points from the last five rounds. Awards: the season's
 * leaders in wins, poles, fastest laps, podiums, points finishes and places gained.
 * Everything from Jolpica (the Ergast successor), headshots from OpenF1. No F2/F3/F1 Academy: no free source has them.
 */

/* ======================= the season's data ======================= */

data class ResRow(val pos: Int, val posText: String, val id: String, val code: String, val given: String, val family: String, val team: String,
                  val time: String, val status: String, val points: Double, val grid: Int, val laps: Int, val fastest: Boolean) {
    /** A finishing position counts as classified when it's a number; R, D, W, N, E, F are retired, disqualified, did not start… */
    val classified get() = posText.toIntOrNull() != null
    val short get() = "${given.firstOrNull() ?: ' '}. $family"
}

data class RaceRes(val round: Int, val name: String, val locality: String, val country: String, val date: LocalDate, val rows: List<ResRow>) {
    val laps get() = rows.maxOfOrNull { it.laps } ?: 0
}

data class QualRow(val pos: Int, val id: String, val code: String, val given: String, val family: String, val team: String,
                   val q1: String, val q2: String, val q3: String) {
    val best get() = q3.ifBlank { q2.ifBlank { q1 } }
    val reached get() = if (q3.isNotBlank()) "Q3" else if (q2.isNotBlank()) "Q2" else "Q1"
}

data class DriverStanding(val pos: Int, val id: String, val code: String, val given: String, val family: String, val team: String,
                          val points: Double, val wins: Int) {
    val short get() = "${given.firstOrNull() ?: ' '}. $family"
}
data class TeamStanding(val pos: Int, val id: String, val name: String, val points: Double, val wins: Int)

data class F1Season(val year: Int, val races: List<RaceRes>, val sprints: Map<Int, List<ResRow>>, val poles: Map<Int, ResRow>,
                    val drivers: List<DriverStanding>, val teams: List<TeamStanding>, val faces: Map<String, String>, val afterRound: Int)

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun resRow(x: JSONObject): ResRow {
    val d = x.optJSONObject("Driver") ?: JSONObject()
    return ResRow(x.optString("position").toIntOrNull() ?: 99, x.optString("positionText", x.optString("position")), d.optString("driverId"), d.optString("code"),
        d.optString("givenName"), d.optString("familyName"), x.optJSONObject("Constructor")?.optString("name").orEmpty(),
        x.optJSONObject("Time")?.optString("time") ?: x.optString("status"), x.optString("status"), x.optString("points").toDoubleOrNull() ?: 0.0,
        x.optString("grid").toIntOrNull() ?: 0, x.optString("laps").toIntOrNull() ?: 0, x.optJSONObject("FastestLap")?.optString("rank") == "1")
}

/** Jolpica's race lists, merged across its 100-row pages (a race's results can be split between two pages). [key] is Results, SprintResults or QualifyingResults. */
internal fun parseRaces(pages: List<JSONObject>, key: String = "Results"): List<RaceRes> {
    val byRound = sortedMapOf<Int, Pair<JSONObject, MutableList<ResRow>>>()
    pages.forEach { p ->
        p.optJSONObject("MRData")?.optJSONObject("RaceTable")?.optJSONArray("Races").objs().forEach { r ->
            val e = byRound.getOrPut(r.optInt("round")) { r to mutableListOf() }
            r.optJSONArray(key).objs().forEach { e.second += resRow(it) }
        }
    }
    return byRound.map { (round, v) ->
        val (r, rows) = v
        val loc = r.optJSONObject("Circuit")?.optJSONObject("Location")
        RaceRes(round, r.optString("raceName"), loc?.optString("locality").orEmpty(), loc?.optString("country").orEmpty(),
            runCatching { LocalDate.parse(r.optString("date")) }.getOrDefault(LocalDate.MIN), rows.distinctBy { it.id }.sortedBy { it.pos })
    }
}

internal fun parseQualifying(o: JSONObject): List<QualRow> =
    o.optJSONObject("MRData")?.optJSONObject("RaceTable")?.optJSONArray("Races").objs().firstOrNull()?.optJSONArray("QualifyingResults").objs().map { x ->
        val d = x.optJSONObject("Driver") ?: JSONObject()
        QualRow(x.optString("position").toIntOrNull() ?: 99, d.optString("driverId"), d.optString("code"), d.optString("givenName"), d.optString("familyName"),
            x.optJSONObject("Constructor")?.optString("name").orEmpty(), x.optString("Q1"), x.optString("Q2"), x.optString("Q3"))
    }.orEmpty()

/** The standings and the round they're after. */
internal fun parseDriverStandings(o: JSONObject): Pair<Int, List<DriverStanding>> {
    val l = o.optJSONObject("MRData")?.optJSONObject("StandingsTable")?.optJSONArray("StandingsLists").objs().firstOrNull() ?: return 0 to emptyList()
    return l.optString("round").toIntOrNull().let { it ?: 0 } to l.optJSONArray("DriverStandings").objs().map { s ->
        val d = s.optJSONObject("Driver") ?: JSONObject()
        val cs = s.optJSONArray("Constructors").objs()
        DriverStanding(s.optString("position").toIntOrNull() ?: 99, d.optString("driverId"), d.optString("code"), d.optString("givenName"), d.optString("familyName"),
            cs.lastOrNull()?.optString("name").orEmpty(), s.optString("points").toDoubleOrNull() ?: 0.0, s.optString("wins").toIntOrNull() ?: 0)
    }
}

internal fun parseTeamStandings(o: JSONObject): List<TeamStanding> =
    o.optJSONObject("MRData")?.optJSONObject("StandingsTable")?.optJSONArray("StandingsLists").objs().firstOrNull()?.optJSONArray("ConstructorStandings").objs().map { s ->
        val c = s.optJSONObject("Constructor") ?: JSONObject()
        TeamStanding(s.optString("position").toIntOrNull() ?: 99, c.optString("constructorId"), c.optString("name"),
            s.optString("points").toDoubleOrNull() ?: 0.0, s.optString("wins").toIntOrNull() ?: 0)
    }.orEmpty()

/** OpenF1's official headshot for each driver code (NOR, HAM…). */
internal fun parseFaces(a: JSONArray): Map<String, String> = a.objs().mapNotNull { d ->
    val code = d.optString("name_acronym"); val url = d.optString("headshot_url")
    if (code.isBlank() || url.isBlank() || url == "null") null else code to url
}.toMap()

/** One cell of a driver's form: the round, and his result in it (null when he didn't take part). */
data class FormCell(val race: RaceRes, val row: ResRow?)
fun form(id: String, races: List<RaceRes>, n: Int = 5): List<FormCell> = races.takeLast(n).map { r -> FormCell(r, r.rows.firstOrNull { it.id == id }) }

/** A team's points in one Grand Prix (as the redesign shows them; sprint points count in the season total only). */
fun teamRoundPoints(team: String, round: Int, s: F1Season): Double =
    s.races.firstOrNull { it.round == round }?.rows.orEmpty().filter { it.team == team }.sumOf { it.points }

/** The two drivers of a team side by side over the season. */
data class HeadToHead(val a: DriverStanding, val b: DriverStanding, val together: Int, val raceA: Int, val raceB: Int, val winsA: Int, val winsB: Int,
                      val podiumsA: Int, val podiumsB: Int, val pointsFinA: Int, val pointsFinB: Int, val bestA: Int?, val bestB: Int?, val retA: Int, val retB: Int)

fun headToHead(a: DriverStanding, b: DriverStanding, races: List<RaceRes>): HeadToHead {
    var together = 0; var ra = 0; var rb = 0
    val ma = races.mapNotNull { r -> r.rows.firstOrNull { it.id == a.id } }
    val mb = races.mapNotNull { r -> r.rows.firstOrNull { it.id == b.id } }
    races.forEach { r ->
        val x = r.rows.firstOrNull { it.id == a.id }; val y = r.rows.firstOrNull { it.id == b.id }
        if (x != null && y != null) { together++; if (x.pos < y.pos) ra++ else if (y.pos < x.pos) rb++ }
    }
    fun wins(m: List<ResRow>) = m.count { it.posText == "1" }
    fun pods(m: List<ResRow>) = m.count { (it.posText.toIntOrNull() ?: 99) <= 3 }
    fun pf(m: List<ResRow>) = m.count { it.points > 0 }
    fun best(m: List<ResRow>) = m.mapNotNull { it.posText.toIntOrNull() }.minOrNull()
    // a retirement is any race he didn't finish, classified or not (Leclerc retired at Barcelona 2026 yet was classified 15th)
    fun ret(m: List<ResRow>) = m.count { r -> r.status != "Finished" && r.status != "Lapped" && !r.status.startsWith("+") &&
        !r.status.contains("not start", true) && !r.status.contains("Withdr", true) && !r.status.contains("Disqualified", true) }
    return HeadToHead(a, b, together, ra, rb, wins(ma), wins(mb), pods(ma), pods(mb), pf(ma), pf(mb), best(ma), best(mb), ret(ma), ret(mb))
}

data class Award(val title: String, val what: String, val leaders: List<Pair<String, Int>>)

/** The season's leaders, each award's top three. */
fun awards(s: F1Season): List<Award> {
    val names = HashMap<String, String>()
    s.races.forEach { r -> r.rows.forEach { names[it.id] = it.short } }
    fun top(counts: Map<String, Int>) = counts.filter { it.value > 0 }.entries.sortedByDescending { it.value }.take(3).map { (names[it.key] ?: it.key) to it.value }
    val all = s.races.flatMap { it.rows }
    return listOf(
        Award("Most wins", "Grands Prix won", top(all.filter { it.posText == "1" }.groupingBy { it.id }.eachCount())),
        Award("Pole positions", "Fastest in qualifying", top(s.poles.values.groupingBy { it.id }.eachCount())),
        Award("Fastest laps", "Quickest lap of a Grand Prix", top(all.filter { it.fastest }.groupingBy { it.id }.eachCount())),
        Award("Podiums", "Top three finishes", top(all.filter { (it.posText.toIntOrNull() ?: 99) <= 3 }.groupingBy { it.id }.eachCount())),
        Award("Points finishes", "Grands Prix in the points", top(all.filter { it.points > 0 }.groupingBy { it.id }.eachCount())),
        Award("Places gained", "From the grid to the flag, all season", top(all.filter { it.classified && it.grid > 0 }.groupBy { it.id }
            .mapValues { (_, l) -> l.sumOf { it.grid - it.pos } })),
    ).filter { it.leaders.isNotEmpty() }
}

/** "BRA", "USA", "LVG": a Grand Prix's three-letter tag for the form columns. */
fun raceCode(r: RaceRes): String {
    val n = r.name.lowercase(Locale.ROOT)
    return listOf("australia" to "AUS", "chin" to "CHN", "japan" to "JPN", "bahrain" to "BHR", "saudi" to "KSA", "miami" to "MIA", "emilia" to "EMI",
        "monaco" to "MON", "canad" to "CAN", "barcelona" to "BAR", "spanish" to "ESP", "austria" to "AUT", "british" to "GBR", "belgi" to "BEL",
        "hungar" to "HUN", "dutch" to "NED", "ital" to "ITA", "azerbaijan" to "AZE", "singapore" to "SIN", "united states" to "USA", "mexic" to "MEX",
        "brazil" to "BRA", "paulo" to "BRA", "vegas" to "LVG", "qatar" to "QAT", "abu dhabi" to "ABU", "portug" to "POR", "french" to "FRA", "german" to "GER")
        .firstOrNull { n.contains(it.first) }?.second ?: r.name.take(3).uppercase(Locale.ROOT)
}

object F1Seasons {
    private val cache = HashMap<Int, Pair<Long, F1Season>>()
    private val quali = HashMap<String, List<QualRow>>()

    suspend fun load(year: Int, current: Boolean): F1Season = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[year] }?.takeIf { !current || System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return@withContext it.second }
        val base = "https://api.jolpi.ca/ergast/f1/$year"
        // Jolpica gives 100 rows a page; one at a time keeps within its rate limit
        fun pages(path: String): List<JSONObject> {
            val first = JSONObject(httpGet("$base/$path.json?limit=100&offset=0"))
            val total = first.optJSONObject("MRData")?.optString("total")?.toIntOrNull() ?: 0
            return listOf(first) + (100 until total step 100).map { JSONObject(httpGet("$base/$path.json?limit=100&offset=$it")) }
        }
        val races = parseRaces(pages("results"))
        val sprints = runCatching { parseRaces(pages("sprint"), "SprintResults").associate { it.round to it.rows } }.getOrDefault(emptyMap())
        val poles = runCatching { parseRaces(pages("qualifying/1"), "QualifyingResults").mapNotNull { r -> r.rows.firstOrNull()?.let { r.round to it } }.toMap() }.getOrDefault(emptyMap())
        val (after, drivers) = parseDriverStandings(JSONObject(httpGet("$base/driverStandings.json")))
        val teams = runCatching { parseTeamStandings(JSONObject(httpGet("$base/constructorStandings.json"))) }.getOrDefault(emptyList())
        val faces = if (!current) emptyMap() else runCatching { parseFaces(JSONArray(httpGet("https://api.openf1.org/v1/drivers?session_key=latest"))) }.getOrDefault(emptyMap())
        F1Season(year, races, sprints, poles, drivers, teams, faces, after).also { synchronized(cache) { cache[year] = System.currentTimeMillis() to it } }
    }

    suspend fun qualifying(year: Int, round: Int): List<QualRow> = withContext(Dispatchers.IO) {
        synchronized(quali) { quali["$year/$round"] }?.let { return@withContext it }
        parseQualifying(JSONObject(httpGet("https://api.jolpi.ca/ergast/f1/$year/$round/qualifying.json"))).also { if (it.isNotEmpty()) synchronized(quali) { quali["$year/$round"] = it } }
    }
}

/* ======================= the page ======================= */

private val GOLD = Color(0xFFE2B543); private val SILVER = Color(0xFFC3C7CE); private val BRONZE = Color(0xFFC98A5A)
private val DAYMON = DateTimeFormatter.ofPattern("dd MMM", Locale.UK)

@Composable
fun F1ResultsView(now: Instant, startTab: Int = 1) {
    val ctx = LocalContext.current
    val thisYear = now.atZone(ZoneOffset.UTC).year
    var year by rememberSaveable { mutableIntStateOf(thisYear) }
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var season by remember(year) { mutableStateOf<F1Season?>(null) }
    var failed by remember(year) { mutableStateOf(false) }
    var round by remember(year) { mutableStateOf<Int?>(null) }
    var session by remember(year) { mutableStateOf("Race") }
    val fav = remember { Repo.fav(ctx, Kind.DRIVER) }
    LaunchedEffect(year) {
        runCatching { F1Seasons.load(year, year == thisYear) }.onSuccess { season = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failed = true }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RESULTS", Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 24.sp)
            Picker("$year", (thisYear downTo thisYear - 4).map { "$it" }) { year = it.toInt() }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            listOf("Races", "Drivers", "Teams", "Awards").forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clickable { tab = i }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, color = if (tab == i) C.fg else C.fg2, fontFamily = Inter, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                    Box(Modifier.padding(top = 6.dp).width(28.dp).height(2.dp).background(if (tab == i) F1_RED else Color.Transparent))
                }
            }
        }
        val s = season
        when {
            failed -> Text("Couldn't load the results. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
            s == null -> Column(Modifier.padding(top = 8.dp)) { repeat(6) { Skeleton(56.dp, Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) } }
            s.races.isEmpty() -> Text("No races run in $year yet.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
            else -> Spoiler("f1results") {
                when (tab) {
                    0 -> RacesTab(s, round ?: s.races.last().round, session, { round = it; session = "Race" }, { session = it })
                    1 -> DriversTab(s, fav) { r -> round = r; session = "Race"; tab = 0 }
                    2 -> TeamsTab(s, fav)
                    else -> AwardsTab(s)
                }
            }
        }
    }
}

/** A pill that opens a menu of choices. */
@Composable
private fun Picker(label: String, options: List<String>, lead: String = "", onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, C.line, RoundedCornerShape(18.dp)).background(C.tile).clickable { open = true }
            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lead.isNotEmpty()) Text("$lead ", fontSize = 13.sp)
            Text(label, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            Text("  ⌄", color = C.fg2, fontSize = 12.sp)
        }
        DropdownMenu(open, { open = false }, Modifier.background(C.tile2).heightIn(max = 420.dp)) {
            options.forEach { o -> DropdownMenuItem({ Text(o, color = C.fg, fontFamily = Inter, fontSize = 14.sp) }, { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun FilterPill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) C.fg else C.tile).border(1.dp, if (on) C.fg else C.line, RoundedCornerShape(18.dp))
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp), color = if (on) C.bg else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
}

@Composable
private fun Big(title: String, aside: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title.uppercase(Locale.ROOT), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
            fontSize = 19.sp, lineHeight = 21.sp)
        if (aside.isNotEmpty()) Text(aside.uppercase(Locale.ROOT), color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.5.sp, letterSpacing = .5.sp)
    }
}

/** A driver's face: OpenF1's headshot on his team colour, or his initials when there's none. */
@Composable
private fun Face(url: String?, code: String, team: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(f1TeamColor(team).copy(alpha = .35f)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        else Text(code.take(3), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = (size.value * .28f).sp)
    }
}

@Composable
private fun TeamBar(team: String) = Box(Modifier.size(width = 3.dp, height = 26.dp).clip(RoundedCornerShape(2.dp)).background(f1TeamColor(team)))

/* ---------- Races ---------- */

@Composable
private fun RacesTab(s: F1Season, round: Int, session: String, onRound: (Int) -> Unit, onSession: (String) -> Unit) {
    val race = s.races.firstOrNull { it.round == round } ?: s.races.last()
    val sessions = listOfNotNull("Race", "Qualifying", if (s.sprints.containsKey(race.round)) "Sprint" else null)
    var quali by remember(s.year, race.round) { mutableStateOf<List<QualRow>?>(null) }
    if (session == "Qualifying") LaunchedEffect(s.year, race.round) { quali = runCatching { F1Seasons.qualifying(s.year, race.round) }.getOrDefault(emptyList()) }
    val rows = if (session == "Sprint") s.sprints[race.round].orEmpty() else race.rows
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker(race.locality, s.races.reversed().map { "${it.round}. ${it.locality}" }, flagOf(race.country)) { o -> o.substringBefore('.').toIntOrNull()?.let(onRound) }
                Picker(session, sessions) { onSession(it) }
            }
        }
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Text("ROUND ${race.round} · ${DAYMON.format(race.date).uppercase(Locale.ROOT)}${if (race.laps > 0 && session != "Qualifying") " · ${(if (session == "Sprint") rows.maxOfOrNull { it.laps } ?: 0 else race.laps)} LAPS" else ""}",
                    color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = .5.sp)
                Text("${race.name} ${s.year}".uppercase(Locale.ROOT) + if (session != "Race") " · ${session.uppercase(Locale.ROOT)}" else "", color = C.fg, fontFamily = Inter,
                    fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 21.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (session == "Qualifying") {
            val q = quali
            if (q == null) items(6) { Skeleton(50.dp, Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
            else {
                item { TableHead("Pos.", "Driver", "Best", "") }
                items(q, key = { "q" + it.id }) { r ->
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.pos}", Modifier.width(30.dp), style = Digits, color = C.fg, fontSize = 16.sp)
                        Face(s.faces[r.code], r.code, r.team, 34.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${r.given.firstOrNull() ?: ' '}. ${r.family}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(r.team, color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(r.best, style = Digits, color = C.fg, fontSize = 15.sp)
                            Text("Out in ${r.reached}".takeIf { r.reached != "Q3" } ?: "Q3", color = C.fg3, fontFamily = Inter, fontSize = 10.5.sp)
                        }
                    }
                }
                if (q.isEmpty()) item { Text("No qualifying result for this round.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
            }
        } else {
            if (rows.size >= 3) item { Podium(rows.take(3), s.faces) }
            item { TableHead("Pos.", "Driver", "Time/Retired", "Pts.") }
            items(rows, key = { session + it.id }) { r ->
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.classified) r.posText else r.posText.let { if (it == "R") "DNF" else if (it == "W") "DNS" else if (it == "D") "DSQ" else "NC" },
                        Modifier.width(34.dp), style = Digits, color = if (r.classified) C.fg else C.fg3, fontSize = if (r.classified) 16.sp else 12.sp)
                    Face(s.faces[r.code], r.code, r.team, 34.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.short + if (r.fastest) "  ⏱" else "", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        Text(r.team, color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp, maxLines = 1)
                    }
                    Text(if (r.classified) r.time else r.status, style = Digits, color = if (r.classified) C.fg else C.fg3, fontSize = 13.5.sp, maxLines = 1)
                    Text(if (r.points > 0) fmtPts(r.points) else "", Modifier.width(34.dp), style = Digits, color = C.fg, fontSize = 15.sp, textAlign = TextAlign.End)
                }
            }
            if (rows.any { it.fastest }) item { Text("⏱ fastest lap", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
        }
        item { Text("Results from Jolpica (Ergast), headshots from OpenF1.", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
    }
}

private fun fmtPts(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()

@Composable
private fun TableHead(a: String, b: String, c: String, d: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().border(width = 0.dp, color = Color.Transparent).padding(bottom = 6.dp)) {
        Text(a, Modifier.width(34.dp), color = C.fg3, fontFamily = Inter, fontSize = 10.5.sp)
        Text(b.uppercase(Locale.ROOT), Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
        Text(c.uppercase(Locale.ROOT), color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp)
        if (d.isNotEmpty()) Text(d.uppercase(Locale.ROOT), Modifier.width(34.dp), color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, textAlign = TextAlign.End)
    }
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.line))
}

/** Second, first, third: faces over steps of gold, silver and bronze. */
@Composable
private fun Podium(top: List<ResRow>, faces: Map<String, String>) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).padding(start = 8.dp, end = 8.dp, top = 14.dp),
        verticalAlignment = Alignment.Bottom) {
        listOf(1 to SILVER, 0 to GOLD, 2 to BRONZE).forEach { (i, col) ->
            val r = top[i]
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.border(2.dp, col, CircleShape).padding(2.dp)) { Face(faces[r.code], r.code, r.team, if (i == 0) 60.dp else 48.dp) }
                Text(r.family, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text(r.team, color = C.fg3, fontFamily = Inter, fontSize = 10.5.sp, maxLines = 1)
                Text(r.time, style = Digits, color = C.fg, fontSize = 13.sp, maxLines = 1)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(if (i == 0) 46.dp else if (i == 1) 32.dp else 24.dp)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(col.copy(alpha = .85f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Digits, color = Color(0xFF1A1A1A), fontSize = 18.sp)
                }
            }
        }
    }
}

/* ---------- Drivers ---------- */

private fun isFav(d: DriverStanding, fav: Person?) = fav != null && personKey(d.family) == personKey(fav.name.substringAfterLast(' '))

@Composable
private fun DriversTab(s: F1Season, fav: Person?, onRace: (Int) -> Unit) {
    var filter by remember { mutableStateOf("All") }            // All | Following | a team
    val teams = s.drivers.map { it.team }.distinct()
    val shown = when (filter) { "All" -> s.drivers; "Following" -> s.drivers.filter { isFav(it, fav) }; else -> s.drivers.filter { it.team == filter } }
    val lead = s.drivers.firstOrNull()?.points ?: 0.0
    val last5 = s.races.takeLast(5)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All drivers", filter == "All") { filter = "All" }
                FilterPill("Following", filter == "Following") { filter = "Following" }
                Picker(if (filter in teams) filter else "Team", teams) { filter = it }
            }
        }
        item { Big("Drivers' standings", "After round ${s.afterRound}") }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("POS", Modifier.width(30.dp), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp)
                Text("DRIVER", Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("FORM · LAST 5", color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 8.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { last5.forEach { Text(raceCode(it), Modifier.width(22.dp), color = C.fg3, fontFamily = Inter, fontSize = 7.5.sp, textAlign = TextAlign.Center) } }
                }
                Text("PTS", Modifier.width(52.dp), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp, textAlign = TextAlign.End)
            }
        }
        if (shown.isEmpty()) item {
            Text(if (filter == "Following") "Choose your favourite driver in More → Following." else "No drivers.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
        }
        items(shown, key = { "d" + it.id }) { d ->
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (isFav(d, fav)) F1_RED.copy(alpha = .13f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${d.pos}", Modifier.width(26.dp), style = Digits, color = C.fg, fontSize = 16.sp)
                Face(s.faces[d.code], d.code, d.team, 32.dp); Spacer(Modifier.width(8.dp)); TeamBar(d.team); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.short, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(d.team, color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { form(d.id, s.races).forEach { c -> FormChip(c, d, onRace) } }
                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
                    Text(fmtPts(d.points), style = Digits, color = C.fg, fontSize = 17.sp)
                    if (d.pos > 1) Text("−${fmtPts(lead - d.points)}", style = Digits, color = C.fg3, fontSize = 11.sp)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(GOLD to "1", SILVER to "2", BRONZE to "3").forEach { (c, n) -> Chip(n, c, Color(0xFF1A1A1A)) }
                Text("Podium", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                Chip("6", C.tile2, C.fg); Text("Points", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                Chip("14", Color.Transparent, C.fg3); Text("No points", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                Chip("NC", Color.Transparent, F1_RED); Text("Not classified", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                Chip("–", Color.Transparent, C.fg3); Text("Did not race", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
            }
        }
        if (shown.size == 2 && filter != "All" && filter != "Following") item { H2HCard(headToHead(shown[0], shown[1], s.races), s) }
    }
}

@Composable
private fun Chip(t: String, bg: Color, fg: Color) {
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(bg).border(1.dp, if (bg == Color.Transparent) C.line else bg, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center) { Text(t, color = fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = if (t.length > 1) 9.sp else 10.5.sp, maxLines = 1) }
}

/** One finish in the form column; tap it for that race's line and a way to its full result. */
@Composable
private fun FormChip(c: FormCell, d: DriverStanding, onRace: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val r = c.row
    val p = r?.posText?.toIntOrNull()
    Box(Modifier.clickable(enabled = r != null) { open = true }) {
        when {
            r == null -> Chip("–", Color.Transparent, C.fg3)
            p == null -> Chip("NC", Color.Transparent, F1_RED)
            p == 1 -> Chip("1", GOLD, Color(0xFF1A1A1A)); p == 2 -> Chip("2", SILVER, Color(0xFF1A1A1A)); p == 3 -> Chip("3", BRONZE, Color(0xFF1A1A1A))
            r.points > 0 -> Chip("$p", C.tile2, C.fg)
            else -> Chip("$p", Color.Transparent, C.fg3)
        }
        if (r != null) DropdownMenu(open, { open = false }, Modifier.background(C.tile2).width(230.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text("${raceCode(c.race)} · ROUND ${c.race.round} · ${DAYMON.format(c.race.date).uppercase(Locale.ROOT)}", color = C.fg3, fontFamily = Inter,
                    fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(c.race.name.replace(" Grand Prix", ""), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.short, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                        Text(if (r.classified) r.time else r.status, style = Digits, color = C.fg3, fontSize = 12.sp)
                    }
                    Text("${if (p != null) "P$p" else "NC"} · ${fmtPts(r.points)} pts", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text("Race result ›", Modifier.padding(top = 10.dp).clickable { open = false; onRace(c.race.round) }, color = F1_RED, fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

/** The two team-mates over the season, side by side with bars. */
@Composable
private fun H2HCard(h: HeadToHead, s: F1Season) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(f1TeamColor(h.a.team)))
        Column(Modifier.padding(14.dp)) {
            Row {
                Text("${h.a.team.uppercase(Locale.ROOT)} · HEAD-TO-HEAD", Modifier.weight(1f), color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("${s.year} · ${h.together} RACES TOGETHER", color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Face(s.faces[h.a.code], h.a.code, h.a.team, 40.dp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(h.a.short, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text("P${h.a.pos} · ${fmtPts(h.a.points)} pts", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                }
                Text("vs", color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                Column(Modifier.weight(1f).padding(end = 8.dp), horizontalAlignment = Alignment.End) {
                    Text(h.b.short, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Text("P${h.b.pos} · ${fmtPts(h.b.points)} pts", color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                }
                Face(s.faces[h.b.code], h.b.code, h.b.team, 40.dp)
            }
            listOf(
                Triple("Race head-to-head", h.raceA.toDouble(), h.raceB.toDouble()), Triple("Championship points", h.a.points, h.b.points),
                Triple("Wins", h.winsA.toDouble(), h.winsB.toDouble()), Triple("Podiums", h.podiumsA.toDouble(), h.podiumsB.toDouble()),
                Triple("Points finishes", h.pointsFinA.toDouble(), h.pointsFinB.toDouble()),
            ).forEach { (l, x, y) -> H2HLine(l, fmtPts(x), fmtPts(y), x, y, higherWins = true) }
            H2HLine("Best finish", h.bestA?.let { "P$it" } ?: "–", h.bestB?.let { "P$it" } ?: "–", (h.bestA ?: 99).toDouble(), (h.bestB ?: 99).toDouble(), higherWins = false)
            H2HLine("Retirements", "${h.retA}", "${h.retB}", h.retA.toDouble(), h.retB.toDouble(), higherWins = false)
            Text("Grand Prix results from rounds 1–${s.races.last().round}. Championship points also include sprint races.", color = C.fg3, fontFamily = Inter,
                fontSize = 10.5.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun H2HLine(label: String, a: String, b: String, x: Double, y: Double, higherWins: Boolean) {
    val aBetter = if (higherWins) x > y else x < y; val bBetter = if (higherWins) y > x else y < x
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a, Modifier.width(56.dp), style = Digits, color = if (aBetter) C.fg else C.fg2, fontSize = 17.sp)
            Text(label, Modifier.weight(1f), color = C.fg2, fontFamily = Inter, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(b, Modifier.width(56.dp), style = Digits, color = if (bBetter) C.fg else C.fg2, fontSize = 17.sp, textAlign = TextAlign.End)
        }
        Row(Modifier.padding(top = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (aBetter) F1_RED else C.line))
            Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (bBetter) F1_RED else C.line))
        }
    }
}

/* ---------- Teams ---------- */

@Composable
private fun TeamsTab(s: F1Season, fav: Person?) {
    var filter by remember { mutableStateOf("All") }
    fun favTeam(t: TeamStanding) = fav != null && (t.name.contains(fav.team, true) || fav.team.contains(t.name, true))
    val shown = when (filter) { "All" -> s.teams; "Following" -> s.teams.filter(::favTeam); else -> s.teams.filter { it.name == filter } }
    val lead = s.teams.firstOrNull()?.points ?: 0.0
    val last5 = s.races.takeLast(5)
    val pairs = s.races.lastOrNull()?.rows.orEmpty().groupBy { it.team }.mapValues { (_, l) -> l.joinToString(" · ") { it.family } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All teams", filter == "All") { filter = "All" }
                FilterPill("Following", filter == "Following") { filter = "Following" }
                Picker(if (filter in s.teams.map { it.name }) filter else "Team", s.teams.map { it.name }) { filter = it }
            }
        }
        item { Big("Constructors' standings", "After round ${s.afterRound}") }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("POS", Modifier.width(30.dp), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp)
                Text("TEAM", Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("POINTS · LAST 5", color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 8.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { last5.forEach { Text(raceCode(it), Modifier.width(22.dp), color = C.fg3, fontFamily = Inter, fontSize = 7.5.sp, textAlign = TextAlign.Center) } }
                }
                Text("PTS", Modifier.width(52.dp), color = C.fg3, fontFamily = Inter, fontSize = 9.5.sp, textAlign = TextAlign.End)
            }
        }
        items(shown, key = { "t" + it.id }) { t ->
            Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (favTeam(t)) F1_RED.copy(alpha = .13f) else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${t.pos}", Modifier.width(26.dp), style = Digits, color = C.fg, fontSize = 16.sp)
                Box(Modifier.size(30.dp).clip(CircleShape).background(f1TeamColor(t.name).copy(alpha = .3f)), contentAlignment = Alignment.Center) {
                    Text(t.name.take(1), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Black, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp)); TeamBar(t.name); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pairs[t.name].orEmpty(), color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    last5.forEach { r ->
                        val p = teamRoundPoints(t.name, r.round, s)
                        Chip(fmtPts(p), if (p >= 20) C.fg else if (p > 0) C.tile2 else Color.Transparent, if (p >= 20) C.bg else if (p > 0) C.fg else C.fg3)
                    }
                }
                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
                    Text(fmtPts(t.points), style = Digits, color = C.fg, fontSize = 17.sp)
                    if (t.pos > 1) Text("−${fmtPts(lead - t.points)}", style = Digits, color = C.fg3, fontSize = 11.sp)
                }
            }
        }
        item { Text("Points per Grand Prix; sprint points count in the totals.", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
    }
}

/* ---------- Awards ---------- */

@Composable
private fun AwardsTab(s: F1Season) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Big("${s.year} season leaders", "After round ${s.races.last().round}") }
        items(awards(s), key = { it.title }) { a ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).padding(14.dp)) {
                Text(a.title.uppercase(Locale.ROOT), color = F1_RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = .5.sp)
                Text(a.what, color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
                a.leaders.forEachIndexed { i, (name, n) ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("${i + 1}", listOf(GOLD, SILVER, BRONZE)[i], Color(0xFF1A1A1A)); Spacer(Modifier.width(10.dp))
                        Text(name, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium, fontSize = 14.5.sp)
                        Text("$n", style = Digits, color = C.fg, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
