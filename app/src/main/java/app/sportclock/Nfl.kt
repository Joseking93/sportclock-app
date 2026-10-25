package app.sportclock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/*
 * The NFL, fetched by the phone from ESPN (the website's data file stays football, motorsport and fights).
 * Last week to three weeks ahead, kept on the phone for three hours, merged into the events everything else reads,
 * so NFL games appear on Home, in the Calendar, on the widget and in starting-soon alerts like any other event.
 */

/** The 32 teams with the short names ESPN uses ("Kansas City Chiefs" -> "Chiefs"). */
val NFL_TEAMS = mapOf("Arizona Cardinals" to "Cardinals", "Atlanta Falcons" to "Falcons", "Baltimore Ravens" to "Ravens", "Buffalo Bills" to "Bills",
    "Carolina Panthers" to "Panthers", "Chicago Bears" to "Bears", "Cincinnati Bengals" to "Bengals", "Cleveland Browns" to "Browns",
    "Dallas Cowboys" to "Cowboys", "Denver Broncos" to "Broncos", "Detroit Lions" to "Lions", "Green Bay Packers" to "Packers",
    "Houston Texans" to "Texans", "Indianapolis Colts" to "Colts", "Jacksonville Jaguars" to "Jaguars", "Kansas City Chiefs" to "Chiefs",
    "Las Vegas Raiders" to "Raiders", "Los Angeles Chargers" to "Chargers", "Los Angeles Rams" to "Rams", "Miami Dolphins" to "Dolphins",
    "Minnesota Vikings" to "Vikings", "New England Patriots" to "Patriots", "New Orleans Saints" to "Saints", "New York Giants" to "Giants",
    "New York Jets" to "Jets", "Philadelphia Eagles" to "Eagles", "Pittsburgh Steelers" to "Steelers", "San Francisco 49ers" to "49ers",
    "Seattle Seahawks" to "Seahawks", "Tampa Bay Buccaneers" to "Buccaneers", "Tennessee Titans" to "Titans", "Washington Commanders" to "Commanders")

data class NflRow(val team: String, val wins: String, val losses: String, val ties: String, val pct: String, val streak: String, val seed: String)

object Nfl {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/football/nfl"
    /** Badges and colours per game, for the NEXT UP card and the widget. */
    val sides = HashMap<String, Pair<TeamSide, TeamSide>>()

    /** NFL is fetched only when you follow some of it or picked it for your tab. */
    fun enabled(ctx: Context) = Repo.follows(ctx).any { it == "c:NFL" || (it.startsWith("t:") && it.substring(2) in NFL_TEAMS) } || Settings.myTab(ctx) == "NFL"

    private fun file(ctx: Context) = File(ctx.filesDir, "nfl.json")

    /** NFL games from last week to three weeks ahead: from the phone's copy if it is under [maxAgeMin] old, else from ESPN. */
    suspend fun events(ctx: Context, maxAgeMin: Long = 180): List<Event> = withContext(Dispatchers.IO) {
        val f = file(ctx)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMin * 60_000
        val raw = if (fresh) f.readText() else runCatching { fetch().also { f.writeText(it) } }.getOrElse { if (f.exists()) f.readText() else return@withContext emptyList() }
        parse(raw)
    }

    private suspend fun fetch(): String = coroutineScope {
        val now = JSONObject(httpGet("$BASE/scoreboard"))
        val type = now.optJSONObject("season")?.optInt("type") ?: 2
        val week = now.optJSONObject("week")?.optInt("number") ?: 1
        val weeks = now.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("calendar")?.let { cal ->
            (0 until cal.length()).map { cal.getJSONObject(it) }.firstOrNull { it.optString("value") == "$type" }?.optJSONArray("entries")?.length()
        } ?: 18
        val all = JSONArray()
        (week - 1..week + 3).filter { it in 1..weeks }.map { w -> async { runCatching { JSONObject(httpGet("$BASE/scoreboard?seasontype=$type&week=$w")) }.getOrNull() } }
            .awaitAll().filterNotNull().forEach { o -> o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) all.put(a.get(i)) } }
        JSONObject().put("events", all).toString()
    }

    internal fun parse(raw: String): List<Event> {
        val a = JSONObject(raw).optJSONArray("events") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val e = a.getJSONObject(i)
                val c = e.getJSONArray("competitions").getJSONObject(0)
                val cs = c.getJSONArray("competitors").let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
                val h = cs.first { it.optString("homeAway") == "home" }; val w = cs.first { it.optString("homeAway") == "away" }
                fun side(x: JSONObject) = x.getJSONObject("team").let { t ->
                    TeamSide(t.optString("id"), t.optString("displayName"), t.optString("logo"), t.optString("color"), t.optString("alternateColor"))
                }
                val home = side(h); val away = side(w)
                val id = "NFL|" + e.getString("id")
                sides[id] = home to away
                val start = Instant.parse(e.getString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it })
                Event(id, Sport.NFL, "NFL", "NFL", "${away.name} at ${home.name}", "Week ${e.optJSONObject("week")?.optInt("number") ?: ""}".trim(),
                    start, 200, c.optJSONObject("venue")?.optString("fullName").orEmpty(), if (c.optBoolean("timeValid", true)) "confirmed" else "tbc",
                    listOf(home.name, away.name), null, null, null)
            }.getOrNull()
        }.sortedBy { it.start }
    }

    /** Scores for these NFL games: live, half-time and final, keyed by our event id. */
    suspend fun scores(events: List<Event>): Map<String, Score> = withContext(Dispatchers.IO) {
        val nfl = events.filter { it.code == "NFL" }
        if (nfl.isEmpty()) return@withContext emptyMap()
        val board = JSONObject(httpGet("$BASE/scoreboard")).optJSONArray("events") ?: JSONArray()
        (0 until board.length()).mapNotNull { i ->
            runCatching {
                val e = board.getJSONObject(i)
                val id = "NFL|" + e.getString("id")
                if (nfl.none { it.id == id }) return@runCatching null
                val c = e.getJSONArray("competitions").getJSONObject(0)
                val cs = c.getJSONArray("competitors").let { x -> (0 until x.length()).map { x.getJSONObject(it) } }
                val st = e.getJSONObject("status").getJSONObject("type")
                val state = st.optString("state")
                if (state == "pre") return@runCatching null
                val detail = st.optString("shortDetail")
                id to Score(when { state == "post" -> "FINISHED"; detail.contains("Half", true) -> "PAUSED"; else -> "IN_PLAY" },
                    cs.first { it.optString("homeAway") == "home" }.optString("score").toIntOrNull(),
                    cs.first { it.optString("homeAway") == "away" }.optString("score").toIntOrNull(), null,
                    if (state == "post") "Final" else detail.replace(" - ", " "), e.getString("id"), "nfl")
            }.getOrNull()
        }.toMap()
    }

    /** Standings by division: "AFC East" -> rows in order. */
    suspend fun standings(): List<Pair<String, List<NflRow>>> = withContext(Dispatchers.IO) {
        val o = JSONObject(httpGet("https://site.api.espn.com/apis/v2/sports/football/nfl/standings?level=3"))
        fun groups(x: JSONObject): List<JSONObject> = x.optJSONArray("children")?.let { a -> (0 until a.length()).flatMap { groups(a.getJSONObject(it)) } }
            ?: listOf(x)
        groups(o).filter { it.optJSONObject("standings") != null }.map { g ->
            val entries = g.getJSONObject("standings").optJSONArray("entries") ?: JSONArray()
            g.optString("name").replace("American Football Conference", "AFC").replace("National Football Conference", "NFC") to
                (0 until entries.length()).map { entries.getJSONObject(it) }.map { en ->
                    val stats = en.optJSONArray("stats")?.let { s -> (0 until s.length()).associate { s.getJSONObject(it).let { x -> x.optString("name") to x.optString("displayValue") } } }.orEmpty()
                    NflRow(en.getJSONObject("team").optString("displayName"), stats["wins"].orEmpty(), stats["losses"].orEmpty(), stats["ties"].orEmpty(),
                        stats["winPercent"].orEmpty(), stats["streak"].orEmpty(), stats["playoffSeed"].orEmpty())
                }.sortedWith(compareByDescending<NflRow> { it.pct.toDoubleOrNull() ?: 0.0 }.thenByDescending { it.wins.toIntOrNull() ?: 0 })
        }
    }
}
