package app.sportclock

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Free sources, fetched by the phone itself for Jose's personal use (never republished through the website):
 *  - ESPN's site JSON: football scores, results, lineups, goals and tables; UFC results.
 *  - Jolpica (Ergast successor): F1 driver and team standings.
 *  - BBC Sport RSS: news headlines with photos.
 * Polling stays light: scores only while a match is on, lineups from an hour before kick-off.
 */

private const val UA = "SportClock/2.13 (personal Android app)"

internal fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000; c.readTimeout = 15_000
    c.setRequestProperty("User-Agent", UA)
    try {
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }
}

/* ======================= ESPN football ======================= */

val ESPN_LEAGUE = mapOf("ENG" to "eng.1", "EFL" to "eng.2", "ESP" to "esp.1", "ITA" to "ita.1", "GER" to "ger.1",
    "FRA" to "fra.1", "NED" to "ned.1", "POR" to "por.1", "UCL" to "uefa.champions",
    "FACUP" to "eng.fa", "CARABAO" to "eng.league_cup")

private val CUP_LEAGUES = mapOf("FACUP" to "eng.fa", "CARABAO" to "eng.league_cup")

fun parseCupEvents(json: String, code: String): List<Event> {
    val league = series(code) ?: return emptyList()
    val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
    return (0 until events.length()).mapNotNull { i ->
        runCatching {
            val e = events.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val teams = comp.getJSONArray("competitors")
            val home = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it.optString("homeAway") == "home" }
            val away = (0 until teams.length()).map { teams.getJSONObject(it) }.first { it.optString("homeAway") == "away" }
            val stage = e.optJSONObject("season")?.optString("slug").orEmpty()
                .replace('-', ' ').split(' ').filter(String::isNotBlank)
                .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
            val start = e.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }
            Event(
                id = "${code}|${e.getString("id")}", sport = Sport.FOOTBALL, code = code, comp = league.name,
                title = e.optString("name"), session = stage.ifEmpty { "Cup match" }, start = Instant.parse(start),
                mins = 120, venue = comp.optJSONObject("venue")?.optString("fullName").orEmpty(), status = "confirmed",
                teams = listOf(home.getJSONObject("team").optString("displayName"), away.getJSONObject("team").optString("displayName")),
                window = null, fdid = null, movedFrom = null,
            )
        }.getOrNull()
    }
}

object CupSchedule {
    private val cache = HashMap<Int, Pair<Long, List<Event>>>()
    private val lock = Mutex()

    suspend fun events(now: Instant): List<Event> = coroutineScope {
        val firstYear = now.atZone(ZoneId.systemDefault()).year
        (firstYear..firstYear + 1).map { year ->
            async {
                runCatching { year(year) }
                    .onFailure { Diag.log("cup schedule $year: $it") }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten().filter { it.start.isAfter(now.minusSeconds(14 * 86400L)) }.distinctBy { it.id }
    }

    private suspend fun year(year: Int): List<Event> = lock.withLock {
        cache[year]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second?.let { return@withLock it }
        val fresh = coroutineScope {
            CUP_LEAGUES.map { (code, league) ->
                async {
                    val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=$year&limit=1000"
                    code to parseCupEvents(httpGet(url), code)
                }
            }.awaitAll().flatMap { it.second }
        }
        cache[year] = System.currentTimeMillis() to fresh
        fresh
    }
}

data class EspnMatch(val id: String, val league: String, val start: Instant, val home: String, val away: String,
                     val homeScore: Int?, val awayScore: Int?, val state: String, val detail: String,
                     val homeSide: TeamSide? = null, val awaySide: TeamSide? = null,
                     val homeShootout: Int? = null, val awayShootout: Int? = null)
/** A club as ESPN draws it: badge URL and its two colours ("da020e"), for the NEXT UP card. */
data class TeamSide(val id: String, val name: String, val logo: String, val color: String, val alt: String)
private fun side(c: JSONObject) = c.getJSONObject("team").let { t ->
    TeamSide(t.optString("id"), t.optString("displayName"), t.optString("logo"), t.optString("color"), t.optString("alternateColor"))
}

/** ESPN's day is US Eastern, so a fixture is looked up under its Eastern date. */
private val ET = ZoneId.of("America/New_York")
private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
fun espnDate(i: Instant): String = YMD.format(i.atZone(ET))

suspend fun espnScoreboard(league: String, date: String): List<EspnMatch> = withContext(Dispatchers.IO) {
    val o = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=$date"))
    val evs = o.optJSONArray("events") ?: JSONArray()
    (0 until evs.length()).mapNotNull { i ->
        runCatching {
            val e = evs.getJSONObject(i)
            val comp = e.getJSONArray("competitions").getJSONObject(0)
            val cs = comp.getJSONArray("competitors")
            val teams = (0 until cs.length()).map { cs.getJSONObject(it) }
            val h = teams.first { it.optString("homeAway") == "home" }; val a = teams.first { it.optString("homeAway") == "away" }
            val st = e.getJSONObject("status").getJSONObject("type")
            val state = st.optString("state")                        // pre | in | post
            EspnMatch(e.getString("id"), league, Instant.parse(e.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }),
                h.getJSONObject("team").optString("displayName"), a.getJSONObject("team").optString("displayName"),
                if (state == "pre") null else h.optString("score").toIntOrNull(), if (state == "pre") null else a.optString("score").toIntOrNull(),
                state, st.optString("shortDetail"), side(h), side(a), h.optString("shootoutScore").toIntOrNull(), a.optString("shootoutScore").toIntOrNull())
        }.getOrNull()
    }
}

/** Club names differ between sources ("1. FSV Mainz 05" v "Mainz"), so compare the meaningful words. */
private val STOP = setOf("fc", "cf", "afc", "sc", "ac", "ss", "us", "ssc", "club", "de", "the", "and", "calcio", "1", "cd", "rc", "fk", "sv", "vfb", "vfl", "tsg", "bv", "rb", "ud", "sd", "ca", "real", "1899", "1909", "1907", "1913", "1963", "05", "04", "07")
fun nameWords(n: String): Set<String> = Normalizer.normalize(n.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").replace("&", " ").split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOP }.toSet()
/** True when two sources' names for a club are the same club. */
fun sameTeam(a: String, b: String) = sameClub(a, b)
private fun sameClub(a: String, b: String): Boolean {
    val x = nameWords(a); val y = nameWords(b)
    if (x.isEmpty() || y.isEmpty()) return false
    return x.intersect(y).isNotEmpty() || x.any { w -> y.any { it.startsWith(w) || w.startsWith(it) } }
}

/** Our fixture's ESPN twin: same league, kick-off within three hours, and at least one of the two clubs matching. */
fun matchEspn(e: Event, pool: List<EspnMatch>): EspnMatch? {
    if (e.teams.size != 2) return null
    return pool.filter { Math.abs(it.start.epochSecond - e.start.epochSecond) <= 3 * 3600 }
        .firstOrNull { (sameClub(it.home, e.teams[0]) && sameClub(it.away, e.teams[1])) }
        ?: pool.filter { Math.abs(it.start.epochSecond - e.start.epochSecond) <= 3 * 3600 }
            .firstOrNull { sameClub(it.home, e.teams[0]) || sameClub(it.away, e.teams[1]) }
}

/** Scores for our football events, fetched per league and ESPN day only for the events asked about. */
suspend fun espnScores(events: List<Event>): Map<String, Score> = coroutineScope {
    val wanted = events.filter { it.sport == Sport.FOOTBALL && ESPN_LEAGUE.containsKey(it.code) }
    val keys = wanted.map { ESPN_LEAGUE.getValue(it.code) to espnDate(it.start) }.distinct()
    val boards = keys.map { (lg, d) -> async { (lg to d) to runCatching { espnScoreboard(lg, d) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
    val out = HashMap<String, Score>()
    for (e in wanted) {
        val m = matchEspn(e, boards[ESPN_LEAGUE.getValue(e.code) to espnDate(e.start)].orEmpty()) ?: continue
        val status = when (m.state) { "in" -> if (m.detail.contains("HT", true) || m.detail.contains("Half", true)) "PAUSED" else "IN_PLAY"; "post" -> "FINISHED"; else -> "TIMED" }
        out[e.id] = Score(status, m.homeScore, m.awayScore, null, m.detail, m.id, m.league, m.homeShootout, m.awayShootout)
    }
    out
}

data class Player(val name: String, val number: String, val position: String)
data class TeamSheet(val team: String, val formation: String, val starters: List<Player>, val subs: List<Player>)
data class Goal(val minute: String, val text: String)
data class MatchSheet(val home: TeamSheet?, val away: TeamSheet?, val goals: List<Goal>)

/** Lineups (once announced, usually about an hour before kick-off) and goals, from ESPN's match summary. */
suspend fun espnSheet(league: String, id: String): MatchSheet = withContext(Dispatchers.IO) {
    val o = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/summary?event=$id"))
    val rosters = o.optJSONArray("rosters") ?: JSONArray()
    val sheets = (0 until rosters.length()).map { i ->
        val r = rosters.getJSONObject(i)
        val list = r.optJSONArray("roster") ?: JSONArray()
        val ps = (0 until list.length()).map { list.getJSONObject(it) }.map { p ->
            p.optBoolean("starter") to Player(p.optJSONObject("athlete")?.optString("displayName").orEmpty(), p.optString("jersey"),
                p.optJSONObject("position")?.optString("abbreviation").orEmpty())
        }
        r.optString("homeAway") to TeamSheet(r.optJSONObject("team")?.optString("displayName").orEmpty(), r.optString("formation"),
            ps.filter { it.first }.map { it.second }, ps.filter { !it.first }.map { it.second })
    }.toMap()
    val ke = o.optJSONArray("keyEvents") ?: JSONArray()
    val goals = (0 until ke.length()).map { ke.getJSONObject(it) }.filter { it.optBoolean("scoringPlay") }.map {
        // "Goal! Arsenal 1, Everton 0. Ezri Konsa (Arsenal) right footed shot..." -> "Ezri Konsa (Arsenal)"
        val who = it.optString("text").substringAfter(". ", "").substringBefore(")", "").let { w -> if (w.isEmpty()) "" else "$w)" }
        Goal(it.optJSONObject("clock")?.optString("displayValue").orEmpty(), who.ifEmpty { it.optJSONObject("type")?.optString("text").orEmpty() })
    }
    MatchSheet(sheets["home"]?.takeIf { it.starters.isNotEmpty() }, sheets["away"]?.takeIf { it.starters.isNotEmpty() }, goals)
}

data class TableRow(val rank: Int, val team: String, val played: String, val gd: String, val points: String)
data class Table(val name: String, val rows: List<TableRow>)

suspend fun espnTable(code: String): Table = withContext(Dispatchers.IO) {
    val lg = ESPN_LEAGUE.getValue(code)
    val o = JSONObject(httpGet("https://site.api.espn.com/apis/v2/sports/soccer/$lg/standings"))
    val g = o.optJSONArray("children")?.optJSONObject(0) ?: o
    val entries = g.optJSONObject("standings")?.optJSONArray("entries") ?: JSONArray()
    fun stat(e: JSONObject, n: String): String {
        val s = e.optJSONArray("stats") ?: return ""
        for (i in 0 until s.length()) { val x = s.getJSONObject(i); if (x.optString("name") == n) return x.optString("displayValue") }
        return ""
    }
    val rows = (0 until entries.length()).map { entries.getJSONObject(it) }.map {
        TableRow(stat(it, "rank").toIntOrNull() ?: 99, it.getJSONObject("team").optString("displayName"),
            stat(it, "gamesPlayed"), stat(it, "pointDifferential"), stat(it, "points"))
    }.sortedBy { it.rank }
    Table(g.optString("name"), rows)
}

/* ======================= Jolpica F1 standings ======================= */

data class Standing(val pos: String, val name: String, val team: String, val points: String)

suspend fun f1Standings(constructors: Boolean): Pair<String, List<Standing>> = withContext(Dispatchers.IO) {
    val kind = if (constructors) "constructorStandings" else "driverStandings"
    val o = JSONObject(httpGet("https://api.jolpi.ca/ergast/f1/current/$kind.json"))
    val lists = o.getJSONObject("MRData").getJSONObject("StandingsTable").getJSONArray("StandingsLists")
    if (lists.length() == 0) return@withContext "" to emptyList()
    val l = lists.getJSONObject(0)
    val arr = l.getJSONArray(if (constructors) "ConstructorStandings" else "DriverStandings")
    val rows = (0 until arr.length()).map { arr.getJSONObject(it) }.map { s ->
        if (constructors) Standing(s.optString("position"), s.getJSONObject("Constructor").optString("name"), "", s.optString("points"))
        else {
            val d = s.getJSONObject("Driver")
            val team = s.optJSONArray("Constructors")?.optJSONObject(0)?.optString("name").orEmpty()
            Standing(s.optString("position"), "${d.optString("givenName")} ${d.optString("familyName")}", team, s.optString("points"))
        }
    }
    "${l.optString("season")} · after round ${l.optString("round")}" to rows
}

/* ======================= BBC Sport news ======================= */

data class Story(val title: String, val link: String, val image: String?, val at: Instant?, val tag: String)

private val RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME

/** BBC Sport feeds for what Jose follows; a followed club gets its own team feed. */
fun newsFeeds(follows: Set<String>): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    follows.forEach { t ->
        when {
            t == "c:F1" -> out += "F1" to "formula1"
            t == "c:UFC" -> out += "UFC" to "mixed-martial-arts"
            t == "c:BOX" -> out += "Boxing" to "boxing"
            t == "c:MGP" -> out += "MotoGP" to "motorsport"
            t == "c:INDY" || t == "c:NAS" -> out += "Motorsport" to "motorsport"
            t.startsWith("t:") -> out += short(t.substring(2)) to "football/teams/" + t.substring(2).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            t.startsWith("c:") && series(t.substring(2))?.sport == Sport.FOOTBALL -> out += "Football" to "football"
        }
    }
    return out.distinct()
}

suspend fun news(follows: Set<String>): List<Story> = coroutineScope {
    newsFeeds(follows).map { (tag, path) -> async { runCatching { rss("https://feeds.bbci.co.uk/sport/$path/rss.xml", tag) }.getOrDefault(emptyList()) } }
        .awaitAll().flatten().distinctBy { it.link.substringBefore("?") }.sortedByDescending { it.at ?: Instant.EPOCH }.take(60)
}

private suspend fun rss(url: String, tag: String): List<Story> = withContext(Dispatchers.IO) {
    val p = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); setInput(httpGet(url).reader()) }
    val out = ArrayList<Story>()
    var inItem = false; var title = ""; var link = ""; var img: String? = null; var date: Instant? = null
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.name) {
                "item" -> { inItem = true; title = ""; link = ""; img = null; date = null }
                "title" -> if (inItem) title = p.nextText().trim()
                "link" -> if (inItem) link = p.nextText().trim()
                "pubDate" -> if (inItem) date = runCatching { ZonedDateTime.parse(p.nextText().trim(), RFC1123).toInstant() }.getOrNull()
                "media:thumbnail" -> if (inItem) img = p.getAttributeValue(null, "url")?.replace("/240/", "/480/")
            }
            XmlPullParser.END_TAG -> if (p.name == "item" && inItem) { inItem = false; if (title.isNotEmpty() && link.isNotEmpty()) out += Story(title, link, img, date, tag) }
        }
    }
    out
}
