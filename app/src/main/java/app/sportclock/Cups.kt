package app.sportclock

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/*
 * Cup ties for the clubs you follow: each country's domestic cups (Carabao Cup and FA Cup, Copa del Rey, DFB-Pokal…)
 * and the Europa and Conference Leagues, from ESPN's per-club cup schedules. The website's data file only has the
 * leagues and the Champions League, so the phone fetches these for followed clubs only, keeps them for three hours
 * and merges them into the events everything else reads: Home, NEXT UP, the calendar, the widget and the alerts.
 */

data class Cup(val code: String, val slug: String, val name: String, val color: Color)

val CUPS = listOf(
    Cup("LC", "eng.league_cup", "Carabao Cup", Color(0xFF3FD06A)),
    Cup("FAC", "eng.fa", "FA Cup", Color(0xFFE8E8E8)),
    Cup("CDR", "esp.copa_del_rey", "Copa del Rey", Color(0xFFFFC23D)),
    Cup("DFB", "ger.dfb_pokal", "DFB-Pokal", Color(0xFFFF6B6B)),
    Cup("CIT", "ita.coppa_italia", "Coppa Italia", Color(0xFF6FA8FF)),
    Cup("CDF", "fra.coupe_de_france", "Coupe de France", Color(0xFF8FA8FF)),
    Cup("KNV", "ned.cup", "KNVB Beker", Color(0xFFFFA24D)),
    Cup("TDP", "por.taca.portugal", "Taça de Portugal", Color(0xFF5FD39A)),
    Cup("UEL", "uefa.europa", "Europa League", Color(0xFFFF8C1A)),
    Cup("UECL", "uefa.europa.conf", "Conference League", Color(0xFF3DDC97)),
)
fun cup(code: String) = CUPS.firstOrNull { it.code == code }

/** The cups a club from each of our leagues can play in, besides the Champions League (already in the data file). */
private val DOMESTIC = mapOf("ENG" to listOf("LC", "FAC"), "EFL" to listOf("LC", "FAC"), "ESP" to listOf("CDR"), "GER" to listOf("DFB"),
    "ITA" to listOf("CIT"), "FRA" to listOf("CDF"), "NED" to listOf("KNV"), "POR" to listOf("TDP"))
private val EUROPE = listOf("UEL", "UECL")
fun cupsFor(leagueCode: String) = DOMESTIC[leagueCode].orEmpty() + EUROPE

/** Every competition the phone can score from ESPN: the leagues and the cups. */
val ESPN_COMP: Map<String, String> = ESPN_LEAGUE + CUPS.associate { it.code to it.slug }

/** One cup tie as ESPN lists it. Winner flags settle ties that went to penalties, where the scores are level. */
data class CupTie(val cup: String, val id: String, val date: Instant, val homeId: String, val home: String, val awayId: String, val away: String,
                  val homeScore: String?, val awayScore: String?, val state: String, val round: String, val venue: String, val note: String,
                  val homeWon: Boolean, val awayWon: Boolean, val timeValid: Boolean)

/** The current season starts on 1 July; anything older belongs to last season's cup runs. */
fun seasonStart(now: Instant): Instant {
    val d = now.atZone(ZoneOffset.UTC)
    return d.withYear(if (d.monthValue >= 7) d.year else d.year - 1).withMonth(7).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
}

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseCupTies(cupCode: String, raw: String): List<CupTie> = JSONObject(raw).optJSONArray("events").objs().mapNotNull { ev ->
    val c = ev.optJSONArray("competitions").objs().firstOrNull() ?: return@mapNotNull null
    val cs = c.optJSONArray("competitors").objs()
    val h = cs.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
    val a = cs.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
    val state = c.optJSONObject("status")?.optJSONObject("type")?.optString("state").orEmpty().ifEmpty { "pre" }
    fun score(x: JSONObject) = if (state == "pre") null else
        (x.optJSONObject("score")?.optString("displayValue") ?: x.optString("score")).takeIf { it.isNotBlank() }
    val date = runCatching { Instant.parse(ev.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }) }.getOrNull() ?: return@mapNotNull null
    CupTie(cupCode, ev.optString("id"), date,
        h.optJSONObject("team")?.optString("id").orEmpty(), h.optJSONObject("team")?.optString("displayName").orEmpty(),
        a.optJSONObject("team")?.optString("id").orEmpty(), a.optJSONObject("team")?.optString("displayName").orEmpty(),
        score(h), score(a), state, ev.optJSONObject("seasonType")?.optString("name").orEmpty(),
        c.optJSONObject("venue")?.optString("fullName").orEmpty(), c.optJSONArray("notes").objs().firstOrNull()?.optString("headline").orEmpty(),
        h.optBoolean("winner"), a.optBoolean("winner"), c.optBoolean("timeValid", true))
}

/** A club's cup ties this season, played and to come, in every cup its league's clubs enter. */
suspend fun cupTies(leagueCode: String, teamId: String, now: Instant = Instant.now()): List<CupTie> = coroutineScope {
    val from = seasonStart(now)
    cupsFor(leagueCode).flatMap { code ->
        listOf("", "?fixture=true").map { q ->
            async(Dispatchers.IO) {
                runCatching { parseCupTies(code, httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/${cup(code)!!.slug}/teams/$teamId/schedule$q")) }
                    .getOrDefault(emptyList())
            }
        }
    }.awaitAll().flatten().filter { !it.date.isBefore(from) }.distinctBy { it.id }.sortedBy { it.date }
}

/** Where a club stands in one cup: still in, through and waiting for the draw, out, or not started yet. */
data class CupRun(val cup: Cup, val line: String, val alive: Boolean?, val next: CupTie?)

fun cupRuns(leagueCode: String, teamId: String, ties: List<CupTie>, now: Instant): List<CupRun> = cupsFor(leagueCode).mapNotNull { code ->
    val c = cup(code) ?: return@mapNotNull null
    val ts = ties.filter { it.cup == code }.sortedBy { it.date }
    val europe = code in EUROPE
    if (europe && ts.isEmpty()) return@mapNotNull null                       // not in that competition this season
    fun opp(t: CupTie) = short(if (t.homeId == teamId) t.away else t.home)
    fun round(t: CupTie) = t.round.ifBlank { "next round" }
    val live = ts.firstOrNull { it.state == "in" }
    val next = ts.firstOrNull { it.state == "pre" }
    val last = ts.lastOrNull { it.state == "post" }
    when {
        live != null -> CupRun(c, "Playing now: ${round(live)} v ${opp(live)}", true, live)
        next != null -> CupRun(c, "Still in · ${round(next)} v ${opp(next)}${if (next.homeId == teamId) " (home)" else " (away)"}, " +
            when {
                !next.timeValid -> "${dayMon(next.date)}, time TBC"
                java.time.Duration.between(now, next.date).toDays() >= 6 -> "${dayMon(next.date)} ${hm(next.date)}"   // "Tue 1" alone is unclear that far out
                else -> "${dayWord(next.date, now)} ${hm(next.date)}"
            }, true, next)
        last == null -> CupRun(c, "No tie yet this season: the club joins in a later round", null, null)
        else -> {
            val mine = if (last.homeId == teamId) last.homeScore else last.awayScore
            val theirs = if (last.homeId == teamId) last.awayScore else last.homeScore
            val won = if (last.homeId == teamId) last.homeWon else last.awayWon
            val lost = if (last.homeId == teamId) last.awayWon else last.homeWon
            val score = "$mine–$theirs"
            val inRound = "in the ${round(last).lowercase()}"
            val note = last.note.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            when {
                europe && round(last).contains("League", true) -> CupRun(c, "In the league phase · last game $score v ${opp(last)}", true, null)
                won && round(last).equals("Final", true) -> CupRun(c, "Winners · beat ${opp(last)} $score in the final$note", false, null)
                won -> CupRun(c, "Through · beat ${opp(last)} $score $inRound$note. Next round still to be drawn", true, null)
                lost && mine == theirs -> CupRun(c, "Out · $score v ${opp(last)} $inRound$note", false, null)
                lost -> CupRun(c, "Out · lost $score to ${opp(last)} $inRound$note", false, null)
                else -> CupRun(c, "Drew $score with ${opp(last)} $inRound, more to play", true, null)
            }
        }
    }
}

object Cups {
    /** The clubs whose cup ties we fetch: the ones you follow and the one on your tab. */
    fun teams(ctx: Context): List<String> =
        (Repo.follows(ctx).filter { it.startsWith("t:") }.map { it.substring(2) } + listOfNotNull(Settings.myTab(ctx).takeIf { it.startsWith("t:") }?.substring(2)))
            .filter { it !in NFL_TEAMS }.distinct().sorted()

    private fun file(ctx: Context) = File(ctx.filesDir, "cups.json")

    /** Cup ties of every followed club as events: the phone's copy if under [maxAgeMin] old and for the same clubs, else ESPN. */
    suspend fun events(ctx: Context, base: List<Event>, maxAgeMin: Long = 180): List<Event> = withContext(Dispatchers.IO) {
        val teams = teams(ctx)
        if (teams.isEmpty()) return@withContext emptyList()
        val f = file(ctx)
        val key = teams.joinToString("|")
        val saved = runCatching { JSONObject(f.readText()) }.getOrNull()
        val fresh = saved != null && saved.optString("key") == key && System.currentTimeMillis() - f.lastModified() < maxAgeMin * 60_000
        val doc = if (fresh) saved!! else runCatching { fetch(ctx, teams, base).also { f.writeText(it.put("key", key).toString()) } }.getOrElse { saved ?: return@withContext emptyList() }
        toEvents(doc)
    }

    private suspend fun fetch(ctx: Context, teams: List<String>, base: List<Event>): JSONObject = coroutineScope {
        val out = JSONObject()
        teams.map { team ->
            async {
                val code = base.filter { team in it.teams }.map { it.code }.firstOrNull { it != "UCL" && it in DOMESTIC } ?: return@async null
                val id = runCatching { espnTeamId("soccer/" + ESPN_LEAGUE.getValue(code), team, ctx) }.getOrNull() ?: return@async null
                Triple(team, id, cupTies(code, id))
            }
        }.awaitAll().filterNotNull().forEach { (team, id, ties) ->
            out.put(team, JSONObject().put("id", id).put("ties", JSONArray(ties.map { tieJson(it) })))
        }
        out
    }

    private fun tieJson(t: CupTie) = JSONObject().put("cup", t.cup).put("id", t.id).put("date", t.date.toString()).put("homeId", t.homeId).put("home", t.home)
        .put("awayId", t.awayId).put("away", t.away).put("hs", t.homeScore ?: "").put("as", t.awayScore ?: "").put("state", t.state).put("round", t.round)
        .put("venue", t.venue).put("note", t.note).put("hw", t.homeWon).put("aw", t.awayWon).put("tv", t.timeValid)

    private fun tie(o: JSONObject) = CupTie(o.optString("cup"), o.optString("id"), Instant.parse(o.optString("date")), o.optString("homeId"), o.optString("home"),
        o.optString("awayId"), o.optString("away"), o.optString("hs").ifEmpty { null }, o.optString("as").ifEmpty { null }, o.optString("state"),
        o.optString("round"), o.optString("venue"), o.optString("note"), o.optBoolean("hw"), o.optBoolean("aw"), o.optBoolean("tv", true))

    /** Each tie becomes an event under our name for the followed club, so following, alerts and NEXT UP pick it up. */
    internal fun toEvents(doc: JSONObject): List<Event> {
        val out = LinkedHashMap<String, Event>()
        doc.keys().asSequence().filter { it != "key" }.forEach { team ->
            val o = doc.optJSONObject(team) ?: return@forEach
            val id = o.optString("id")
            o.optJSONArray("ties").objs().map(::tie).forEach { t ->
                val c = cup(t.cup) ?: return@forEach
                val eid = "CUP|${t.id}"
                val prev = out[eid]
                // when two followed clubs meet, keep both our names
                val home = if (t.homeId == id) team else prev?.teams?.getOrNull(0) ?: t.home
                val away = if (t.awayId == id) team else prev?.teams?.getOrNull(1) ?: t.away
                out[eid] = Event(eid, Sport.FOOTBALL, c.code, c.name + (t.round.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), "$home v $away", "Kick-off",
                    t.date, 120, t.venue, if (t.timeValid) "confirmed" else "tbc", listOf(home, away), null, null, null)
            }
        }
        return out.values.sortedBy { it.start }
    }
}
