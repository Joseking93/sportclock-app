package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/*
 * Results and context beyond the fixture list, all from free sources fetched by the phone:
 *  - ESPN: match form and head-to-head, team pages, F1 / IndyCar / NASCAR sessions, UFC cards.
 *  - MotoGP's own results feed (api.motogp.pulselive.com), which ESPN does not cover.
 *  - Jolpica: F1 race times and gaps, when it has caught up with the weekend.
 */

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun instant(s: String?): Instant? = s?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(if (it.length == 17) it.dropLast(1) + ":00Z" else it) }.getOrNull() }
private suspend fun json(url: String) = withContext(Dispatchers.IO) { JSONObject(httpGet(url)) }

/* ======================= match form and head-to-head ======================= */

data class FormGame(val result: String, val score: String, val atVs: String, val opponent: String)
data class Meeting(val date: Instant?, val home: String, val away: String, val homeScore: String, val awayScore: String)
data class MatchExtras(val homeName: String, val awayName: String, val homeForm: List<FormGame>, val awayForm: List<FormGame>,
                       val meetings: List<Meeting>, val series: String)

suspend fun espnSummary(league: String, id: String): JSONObject = json("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/summary?event=$id")

suspend fun matchExtras(e: Event): MatchExtras? {
    val m = Looks.espnMatch(e) ?: return null
    val o = espnSummary(m.league, m.id)
    fun form(teamName: String) = o.optJSONArray("lastFiveGames").objects()
        .firstOrNull { it.optJSONObject("team")?.optString("displayName") == teamName }
        ?.optJSONArray("events").objects().map {
            FormGame(it.optString("gameResult"), it.optString("score"), it.optString("atVs"), it.optJSONObject("opponent")?.optString("abbreviation").orEmpty())
        }.orEmpty()
    val ss = o.optJSONArray("seasonseries").objects().firstOrNull { it.optString("type") == "head-to-head" }
    val meetings = ss?.optJSONArray("events").objects().mapNotNull { ev ->
        val cs = ev.optJSONArray("competitors").objects()
        val h = cs.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
        val a = cs.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
        Meeting(instant(ev.optString("date")), h.optJSONObject("team")?.optString("displayName").orEmpty(), a.optJSONObject("team")?.optString("displayName").orEmpty(),
            h.optString("score"), a.optString("score"))
    }.orEmpty().sortedByDescending { it.date }
    return MatchExtras(m.home, m.away, form(m.home), form(m.away), meetings, ss?.optString("summary").orEmpty())
}

/* ======================= team pages ======================= */

data class TeamGame(val date: Instant, val home: String, val away: String, val homeScore: String?, val awayScore: String?,
                    val done: Boolean, val comp: String, val homeId: String, val awayId: String)
data class TeamInfo(val id: String, val league: String, val name: String, val logo: String, val standing: String,
                    val results: List<TeamGame>, val fixtures: List<TeamGame>) {
    /** Last five league results from this club's side, newest first: W, D or L. */
    fun form(): List<String> = results.sortedByDescending { it.date }.take(5).map { g ->
        val mine = if (g.homeId == id) g.homeScore else g.awayScore
        val theirs = if (g.homeId == id) g.awayScore else g.homeScore
        val a = mine?.toIntOrNull(); val b = theirs?.toIntOrNull()
        when { a == null || b == null -> "?"; a > b -> "W"; a < b -> "L"; else -> "D" }
    }
}

/** The ESPN league a followed team plays its league games in: its domestic league if we list one, else the Champions League. */
fun leagueFor(team: String, events: List<Event>): String? {
    if (team in NFL_TEAMS) return "football/nfl"
    val codes = events.filter { team in it.teams }.map { it.code }.distinct()
    return (codes.firstOrNull { it != "UCL" && it !in CUP_CODES && it in ESPN_LEAGUE }
        ?: codes.firstOrNull { it !in CUP_CODES && it in ESPN_LEAGUE }
        ?: codes.firstOrNull { it in ESPN_LEAGUE })?.let { "soccer/" + ESPN_LEAGUE[it] }
}

private val teamIds = HashMap<String, String>()

/** A club's ESPN id. Kept on the phone after the first look, so the big team list is fetched once per club, not per visit. */
suspend fun espnTeamId(league: String, name: String, ctx: android.content.Context? = null): String? {
    teamIds["$league/$name"]?.let { return it }
    ctx?.let { c -> Repo.prefs(c).getString("espnId:$league/$name", null)?.let { teamIds["$league/$name"] = it; return it } }
    val teams = json("https://site.api.espn.com/apis/site/v2/sports/$league/teams")
        .optJSONArray("sports").objects().firstOrNull()?.optJSONArray("leagues").objects().firstOrNull()?.optJSONArray("teams").objects()
        .mapNotNull { it.optJSONObject("team") }
    val hit = teams.firstOrNull { it.optString("displayName") == name }
        ?: teams.firstOrNull { sameTeam(it.optString("displayName"), name) }
    return hit?.optString("id")?.also { id ->
        teamIds["$league/$name"] = id
        ctx?.let { c -> Repo.prefs(c).edit().putString("espnId:$league/$name", id).apply() }
    }
}

private val teamCache = HashMap<String, Pair<Long, TeamInfo>>()

private fun games(o: JSONObject): List<TeamGame> = o.optJSONArray("events").objects().mapNotNull { ev ->
    val c = ev.optJSONArray("competitions").objects().firstOrNull() ?: return@mapNotNull null
    val cs = c.optJSONArray("competitors").objects()
    val h = cs.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
    val a = cs.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
    fun score(x: JSONObject) = x.optJSONObject("score")?.optString("displayValue") ?: x.optString("score").takeIf { it.isNotBlank() }
    val done = c.optJSONObject("status")?.optJSONObject("type")?.optBoolean("completed") == true
    TeamGame(instant(ev.optString("date")) ?: return@mapNotNull null,
        h.optJSONObject("team")?.optString("displayName").orEmpty(), a.optJSONObject("team")?.optString("displayName").orEmpty(),
        if (done) score(h) else null, if (done) score(a) else null, done,
        ev.optJSONObject("league")?.optString("abbreviation").orEmpty().ifEmpty { ev.optJSONObject("seasonType")?.optString("name").orEmpty() },
        h.optJSONObject("team")?.optString("id").orEmpty(), a.optJSONObject("team")?.optString("id").orEmpty())
}

/** A club's page: the team, its schedule and its fixtures fetched at the same time, and kept for ten minutes. */
suspend fun teamInfo(league: String, id: String): TeamInfo = kotlinx.coroutines.coroutineScope {
    teamCache["$league/$id"]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return@coroutineScope it.second }
    val base = "https://site.api.espn.com/apis/site/v2/sports/$league/teams/$id"
    val team = async { runCatching { json(base).optJSONObject("team") }.getOrNull() ?: JSONObject() }
    val schedule = async { runCatching { games(json("$base/schedule")) }.getOrDefault(emptyList()) }
    val fixtureList = async { runCatching { games(json("$base/schedule?fixture=true")) }.getOrDefault(emptyList()) }
    val t = team.await(); val sched = schedule.await()
    val results = sched.filter { it.done }
    val fixtures = (fixtureList.await() + sched).filter { !it.done }.distinctBy { it.date }
    TeamInfo(id, league, t.optString("displayName"), t.optJSONArray("logos").objects().firstOrNull()?.optString("href").orEmpty(),
        t.optString("standingSummary"), results.sortedByDescending { it.date }, fixtures.sortedBy { it.date })
        .also { teamCache["$league/$id"] = System.currentTimeMillis() to it }
}

/** The crest for a club, from the phone's copy or ESPN; saved once found, so the tab bar can show it straight away. */
suspend fun teamLogo(ctx: android.content.Context, team: String, events: List<Event>): String? {
    Repo.prefs(ctx).getString("logo:$team", null)?.let { return it }
    val lg = leagueFor(team, events) ?: return null
    val id = espnTeamId(lg, team, ctx) ?: return null
    return teamInfo(lg, id).logo.takeIf { it.isNotBlank() }?.also { Repo.prefs(ctx).edit().putString("logo:$team", it).apply() }
}

/* ======================= motorsport sessions ======================= */

data class RaceRow(val pos: Int, val name: String, val team: String, val time: String)
/** One session of a weekend. [rows] is null until fetched for feeds that need a call per session (MotoGP). */
data class RaceSession(val name: String, val state: String, val start: Instant?, val rows: List<RaceRow>?, val id: String? = null)
data class RaceWeekend(val series: String, val name: String, val start: Instant?, val sessions: List<RaceSession>)

/** ESPN racing slugs for the series we follow. MotoGP is not on ESPN; see [motoGp]. */
val ESPN_RACING = mapOf("F1" to "f1", "INDY" to "irl", "NAS" to "nascar-premier")

private val ESPN_SESSION = mapOf("FP1" to "Practice 1", "FP2" to "Practice 2", "FP3" to "Practice 3", "Qual" to "Qualifying",
    "SS" to "Sprint qualifying", "SQ" to "Sprint qualifying", "Sprint" to "Sprint", "Race" to "Race")

suspend fun espnRacing(code: String): RaceWeekend? {
    val o = json("https://site.api.espn.com/apis/site/v2/sports/racing/${ESPN_RACING.getValue(code)}/scoreboard")
    val ev = o.optJSONArray("events").objects().firstOrNull() ?: return null
    val sessions = ev.optJSONArray("competitions").objects().map { c ->
        val abbr = c.optJSONObject("type")?.optString("abbreviation").orEmpty()
        val state = c.optJSONObject("status")?.optJSONObject("type")?.optString("state").orEmpty()
        val rows = if (state == "pre") emptyList() else c.optJSONArray("competitors").objects().sortedBy { it.optInt("order", 99) }.map { x ->
            val stat = x.optJSONArray("statistics").objects().firstOrNull { it.optString("name") in setOf("totalTime", "behindLeader", "behindTime") }
            RaceRow(x.optInt("order", 0), x.optJSONObject("athlete")?.optString("displayName").orEmpty(), "", stat?.optString("displayValue").orEmpty())
        }
        RaceSession(ESPN_SESSION[abbr] ?: abbr.ifEmpty { "Race" }, state, instant(c.optString("date")), rows)
    }
    val weekend = RaceWeekend(series(code)?.name ?: code, ev.optString("name"), instant(ev.optString("date")), sessions)
    return if (code == "F1") withJolpicaTimes(weekend) else weekend
}

/** ESPN gives F1 finishing order only; Jolpica has times and gaps once it has the race, so add them when the race names agree. */
private suspend fun withJolpicaTimes(w: RaceWeekend): RaceWeekend = runCatching {
    val race = json("https://api.jolpi.ca/ergast/f1/current/last/results.json").getJSONObject("MRData").getJSONObject("RaceTable")
        .optJSONArray("Races").objects().firstOrNull() ?: return w
    val name = race.optString("raceName").removeSuffix(" Grand Prix")
    if (name.isBlank() || !w.name.contains(name, ignoreCase = true)) return w
    val times = race.optJSONArray("Results").objects().associate { r ->
        val d = r.optJSONObject("Driver")
        personKey("${d?.optString("givenName")} ${d?.optString("familyName")}") to
            ((r.optJSONObject("Time")?.optString("time") ?: r.optString("status")) to r.optJSONObject("Constructor")?.optString("name").orEmpty())
    }
    w.copy(sessions = w.sessions.map { s ->
        if (s.name != "Race" || s.state != "post") s
        else s.copy(rows = s.rows?.map { r -> times[personKey(r.name)]?.let { (t, team) -> r.copy(time = t, team = team) } ?: r })
    })
}.getOrDefault(w)

private const val MOTOGP = "https://api.motogp.pulselive.com/motogp/v1/results"
private val MOTOGP_SESSION = mapOf("FP" to "Free practice", "PR" to "Practice", "Q" to "Qualifying", "SPR" to "Sprint", "WUP" to "Warm up", "RAC" to "Race")

/** The current or latest MotoGP weekend (MotoGP class), with its sessions; results come per session from [motoGpRows]. */
suspend fun motoGp(now: Instant = Instant.now()): RaceWeekend? {
    val season = withContext(Dispatchers.IO) { JSONArray(httpGet("$MOTOGP/seasons")) }.objects().firstOrNull { it.optBoolean("current") } ?: return null
    val events = withContext(Dispatchers.IO) { JSONArray(httpGet("$MOTOGP/events?seasonUuid=${season.optString("id")}")) }.objects()
        .filter { !it.optBoolean("test") }
        .sortedBy { it.optString("date_start") }
    val ev = events.lastOrNull { it.optString("date_start") <= now.toString().take(10) } ?: return null
    val cat = withContext(Dispatchers.IO) { JSONArray(httpGet("$MOTOGP/categories?eventUuid=${ev.optString("id")}")) }.objects()
        .firstOrNull { it.optString("name").startsWith("MotoGP") } ?: return null
    val sessions = withContext(Dispatchers.IO) { JSONArray(httpGet("$MOTOGP/sessions?eventUuid=${ev.optString("id")}&categoryUuid=${cat.optString("id")}")) }.objects()
        .map { s ->
            val type = s.optString("type"); val n = s.optString("number").takeIf { it.isNotBlank() && it != "null" }
            val state = when (s.optString("status")) { "FINISHED" -> "post"; "CANCELLED" -> "cancelled"; else -> "pre" }
            RaceSession(listOfNotNull(MOTOGP_SESSION[type] ?: type, n).joinToString(" "), state, instant(s.optString("date")), null, s.optString("id"))
        }
    return RaceWeekend("MotoGP", ev.optString("name").lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
        instant(ev.optString("date_start") + "T00:00:00Z"), sessions)
}

suspend fun motoGpRows(sessionId: String): List<RaceRow> = json("$MOTOGP/session/$sessionId/classification?test=false")
    .optJSONArray("classification").objects().map { x ->
        val gap = x.optJSONObject("gap")?.optString("first").orEmpty()
        RaceRow(x.optInt("position", 0), x.optJSONObject("rider")?.optString("full_name").orEmpty(), x.optJSONObject("team")?.optString("name").orEmpty(),
            when { x.optInt("position", 0) == 1 -> x.optString("time"); gap.isNotBlank() && gap != "0.000" -> "+$gap"; else -> x.optString("status") })
    }.filter { it.pos > 0 }

/* ======================= UFC cards ======================= */

data class Bout(val a: String, val b: String, val aRecord: String, val bRecord: String, val weight: String, val state: String,
                val winner: Int?, val method: String, val round: Int, val clock: String)
data class FightCard(val name: String, val date: Instant?, val bouts: List<Bout>)

private fun method(text: String) = when {
    text.contains("Kotko", true) || text.contains("KO", false) -> "KO/TKO"
    text.contains("Sub", true) -> "Submission"
    text.contains("Dec", true) -> "Decision"
    text.contains("DQ", true) || text.contains("Disq", true) -> "Disqualification"
    text.contains("No Contest", true) -> "No contest"
    text.contains("Draw", true) -> "Draw"
    else -> text.trim()
}

/** The current or latest UFC event, main event first, with results once fought. */
suspend fun ufcCard(): FightCard? {
    val ev = json("https://site.api.espn.com/apis/site/v2/sports/mma/ufc/scoreboard").optJSONArray("events").objects().firstOrNull() ?: return null
    val bouts = ev.optJSONArray("competitions").objects().map { c ->
        val cs = c.optJSONArray("competitors").objects().sortedBy { it.optInt("order", 9) }
        fun name(i: Int) = cs.getOrNull(i)?.optJSONObject("athlete")?.optString("displayName").orEmpty()
        fun rec(i: Int) = cs.getOrNull(i)?.optJSONArray("records").objects().firstOrNull()?.optString("summary").orEmpty()
        val st = c.optJSONObject("status")
        val state = st?.optJSONObject("type")?.optString("state").orEmpty()
        val winText = c.optJSONArray("details").objects().map { it.optJSONObject("type")?.optString("text").orEmpty() }
            .firstOrNull { it.startsWith("Unofficial Winner") }?.removePrefix("Unofficial Winner").orEmpty()
        Bout(name(0), name(1), rec(0), rec(1), c.optJSONObject("type")?.optString("abbreviation").orEmpty(), state,
            cs.indexOfFirst { it.optBoolean("winner") }.takeIf { it >= 0 }, if (state == "post") method(winText) else "",
            st?.optInt("period") ?: 0, st?.optString("displayClock").orEmpty())
    }.reversed()
    return FightCard(ev.optString("name"), instant(ev.optString("date")), bouts)
}
