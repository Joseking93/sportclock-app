package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Three free sources from the public-apis list, each filling a gap ESPN leaves:
 *  - Touchline Wire: Premier League predicted lineups, injuries and suspensions, before the real teams are out.
 *    Free for personal use with attribution; no key.
 *  - OpenF1: team radio clips from a finished F1 session (live clips need a paid account; finished ones don't).
 *  - Boxerbook, through the site's /api/boxing: boxing results, records going in, titles and broadcasters.
 */

private fun JSONArray?.list(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** A small time-limited cache: one fetch serves every sheet opened in the next [ttlMs]. */
private class Cached<T>(private val ttlMs: Long, private val load: suspend () -> T) {
    private val lock = Mutex(); private var at = 0L; private var v: T? = null
    suspend fun get(): T = lock.withLock {
        val now = System.currentTimeMillis()
        v?.takeIf { now - at < ttlMs } ?: load().also { v = it; at = now }
    }
}

/* ======================= Touchline Wire ======================= */

data class Absence(val team: String, val name: String, val status: String, val news: String, val chance: Int?, val deputy: String)
data class PredictedXI(val club: String, val formation: String, val manager: String, val confidence: Double, val starters: List<MpPlayer>) {
    fun side() = MpSide(formation, formationLines(formation, starters), emptyList())
}

/** Touchline Wire's short club names, as ESPN writes them. */
private val TW_NAMES = mapOf("Man Utd" to "Manchester United", "Man City" to "Manchester City", "Spurs" to "Tottenham Hotspur",
    "Nott'm Forest" to "Nottingham Forest", "Brighton" to "Brighton & Hove Albion", "Newcastle" to "Newcastle United",
    "Bournemouth" to "AFC Bournemouth", "Wolves" to "Wolverhampton Wanderers", "West Ham" to "West Ham United", "Leeds" to "Leeds United")
/** Same club when one name's words hold the other's ("AFC Bournemouth" / "Bournemouth"); "Man City" never matches Manchester United. */
internal fun twSame(tw: String, espn: String): Boolean {
    val x = nameWords(TW_NAMES[tw] ?: tw); val y = nameWords(espn)
    return x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
}

/** A predicted slot ("LCB", "RDM", "LS") as the position code the pitch already places (see MatchData.formationLines). */
internal fun slotPos(label: String) = when (label) {
    "GK" -> "G"; "LCB" -> "CD-L"; "CB" -> "CD"; "RCB" -> "CD-R"
    "LDM" -> "DM-L"; "CDM" -> "DM"; "RDM" -> "DM-R"
    "LAM" -> "AM-L"; "CAM" -> "AM"; "RAM" -> "AM-R"
    "LS" -> "CF-L"; "ST" -> "F"; "RS" -> "CF-R"
    else -> label                                                    // LB RB LWB RWB LM CM RM LW RW as they are
}
private fun slotShort(label: String) = when (label) { "CDM" -> "DM"; "CAM" -> "AM"; else -> label }

/**
 * Numbered slots (cb1, cb2, cb3; dm1, dm2; cm1, cm2; st1, st2) run from the team's left to its right, and their labels
 * don't always say which side ("CB" twice), so the side comes from the number and how many share the slot.
 */
internal fun slotSide(slotId: String, label: String, sameKind: Int): String {
    val m = Regex("([a-z]+)(\\d)").matchEntire(slotId) ?: return slotPos(label)
    val kind = m.groupValues[1]; val i = m.groupValues[2].toInt()
    val base = when (kind) { "cb" -> "CD"; "dm" -> "DM"; "cm" -> "CM"; "st" -> "CF"; "am" -> "AM"; else -> return slotPos(label) }
    val side = when { sameKind < 2 -> ""; i == 1 -> "-L"; i == sameKind -> "-R"; else -> "" }
    return if (base == "CF" && side.isEmpty()) "F" else base + side
}

internal fun parsePredicted(o: JSONObject): List<PredictedXI> = o.optJSONArray("data").list().map { c ->
    val starters = c.optJSONArray("starters").list()
    val kinds = starters.map { it.optString("slot_id").trimEnd { ch -> ch.isDigit() } }
    PredictedXI(c.optString("club"), c.optString("formation"), c.optString("manager"), c.optDouble("tactical_confidence_rate", 0.0),
        starters.map { s ->
            val id = s.optString("slot_id"); val label = s.optString("slot_label")
            MpPlayer(s.optString("web_name").ifEmpty { s.optString("name") }, slotShort(label),
                slotSide(id, label, kinds.count { it == id.trimEnd { ch -> ch.isDigit() } }))
        })
}
internal fun parseAbsences(o: JSONObject): List<Absence> = o.optJSONArray("data").list().map { a ->
    fun s(k: String) = if (a.isNull(k)) "" else a.optString(k)                     // JSON null reads as "null" otherwise
    Absence(s("team"), s("web_name").ifEmpty { s("name") }, s("status"), s("news"),
        if (a.isNull("chance_of_playing")) null else a.optInt("chance_of_playing"), s("projected_deputy"))
}

object Touchline {
    private const val BASE = "https://touchlinewire.com/api/v1"
    private val lineups = Cached(30 * 60_000L) { withContext(Dispatchers.IO) { parsePredicted(JSONObject(httpGet("$BASE/lineups"))) } }
    private val absences = Cached(30 * 60_000L) { withContext(Dispatchers.IO) { parseAbsences(JSONObject(httpGet("$BASE/injuries"))) } }
    /** Both clubs' predicted elevens and who's missing, for a Premier League match; null when the source doesn't know either club. */
    suspend fun forMatch(home: String, away: String): Triple<PredictedXI?, PredictedXI?, List<Absence>>? = coroutineScope {
        val l = async { runCatching { lineups.get() }.getOrDefault(emptyList()) }
        val a = async { runCatching { absences.get() }.getOrDefault(emptyList()) }
        val xs = l.await()
        val h = xs.firstOrNull { twSame(it.club, home) }; val w = xs.firstOrNull { twSame(it.club, away) }
        if (h == null && w == null) null
        else Triple(h, w, a.await().filter { twSame(it.team, home) || twSame(it.team, away) })
    }
}

/* ======================= OpenF1 team radio ======================= */

data class RadioClip(val at: Instant, val number: Int, val code: String, val name: String, val team: String, val colour: Long?, val url: String)

internal fun parseRadio(radio: JSONArray, drivers: JSONArray): List<RadioClip> {
    val ds = drivers.list().associateBy { it.optInt("driver_number") }
    return radio.list().mapNotNull { r ->
        val n = r.optInt("driver_number"); val d = ds[n]
        val at = runCatching { Instant.parse(r.optString("date").replace("+00:00", "Z")) }.getOrNull() ?: return@mapNotNull null
        RadioClip(at, n, d?.optString("name_acronym").orEmpty().ifEmpty { "#$n" },
            d?.let { "${it.optString("first_name")} ${it.optString("last_name").lowercase().replaceFirstChar { c -> c.uppercase() }}".trim() }.orEmpty(),
            d?.optString("team_name").orEmpty(), d?.optString("team_colour")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.or(0xFF000000),
            r.optString("recording_url"))
    }.filter { it.url.startsWith("https://") }.sortedBy { it.at }
}

/** The radio clips of the session that started at [start], or empty when OpenF1 has none (yet). */
suspend fun teamRadio(start: Instant): List<RadioClip> = withContext(Dispatchers.IO) {
    fun t(i: Instant) = URLEncoder.encode(i.truncatedTo(ChronoUnit.SECONDS).toString().removeSuffix("Z"), "UTF-8")
    val q = "date_start%3E%3D${t(start.minusSeconds(45 * 60))}&date_start%3C%3D${t(start.plusSeconds(45 * 60))}"
    val ses = JSONArray(httpGet("https://api.openf1.org/v1/sessions?$q")).list().firstOrNull() ?: return@withContext emptyList()
    val key = ses.optInt("session_key")
    coroutineScope {
        val r = async { JSONArray(httpGet("https://api.openf1.org/v1/team_radio?session_key=$key")) }
        val d = async { JSONArray(httpGet("https://api.openf1.org/v1/drivers?session_key=$key")) }
        parseRadio(r.await(), d.await())
    }
}

/* ======================= Boxerbook ======================= */

data class BoxBout(val a: String, val aRecord: String, val b: String, val bRecord: String, val date: LocalDate, val start: Instant?,
                val winner: String?, val method: String, val round: Int?, val title: String, val division: String,
                val broadcasts: List<String>, val venue: String, val scheduledRounds: Int?) {
    val done get() = winner != null || method.isNotEmpty()
    /** "Bazinyan won by TKO in round 10", "Draw (SD)". */
    fun result(): String {
        if (winner == "draw") return "Draw" + (method.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        val who = when (winner) { "a" -> a; "b" -> b; else -> return method.ifBlank { "Result in" } }
        val how = METHOD[method] ?: method
        return "${surname(who)} won" + (if (how.isNotBlank()) " by $how" else "") + (round?.let { if (method in setOf("KO", "TKO", "RTD", "DQ", "TD")) " in round $it" else "" } ?: "")
    }
}
private val METHOD = mapOf("UD" to "unanimous decision", "SD" to "split decision", "MD" to "majority decision", "DEC" to "decision",
    "PTS" to "points", "TD" to "technical decision", "RTD" to "retirement", "DQ" to "disqualification")

private fun bout(b: JSONObject, date: String, start: String?, venue: String, broadcasts: List<String>): BoxBout? {
    val a = b.optJSONObject("a") ?: return null; val c = b.optJSONObject("b") ?: return null
    // Boxerbook's "display" puts the billed name first ("Canelo v Mbilli"); keep that order
    val flip = b.optJSONArray("display")?.optString(0) == "b"
    val (x, y) = if (flip) c to a else a to c
    val win = b.optString("winner").takeIf { it.isNotBlank() && it != "null" }?.let { w -> if (flip && w != "draw") (if (w == "a") "b" else "a") else w }
    return BoxBout(x.optString("name"), x.optString("record").takeIf { it != "null" }.orEmpty(), y.optString("name"), y.optString("record").takeIf { it != "null" }.orEmpty(),
        runCatching { LocalDate.parse(b.optString("date").ifEmpty { date }) }.getOrNull() ?: return null,
        start?.let { runCatching { Instant.parse(if (it.length == 17) it.dropLast(1) + ":00Z" else it) }.getOrNull() },
        win, b.optString("method").takeIf { it != "null" }.orEmpty(), if (b.has("round") && !b.isNull("round")) b.optInt("round") else null,
        b.optString("title_text").takeIf { it != "null" }.orEmpty(), b.optString("division").takeIf { it != "other" && it != "null" }.orEmpty(),
        broadcasts, venue, if (b.has("scheduled_rounds") && !b.isNull("scheduled_rounds")) b.optInt("scheduled_rounds") else null)
}

internal fun parseBoxResults(o: JSONObject) = o.optJSONArray("bouts").list().mapNotNull { bout(it, "", null, "", emptyList()) }
internal fun parseBoxSchedule(o: JSONObject) = o.optJSONArray("events").list().flatMap { e ->
    val bc = e.optJSONArray("broadcasts").list().map { b -> b.optString("platform") + (b.optString("region").takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" } ?: "") }
    val venue = listOf(e.optString("venue"), e.optString("city")).filter { it.isNotBlank() && it != "null" }.joinToString(", ")
    e.optJSONArray("bouts").list().mapNotNull { bout(it, e.optString("date"), e.optString("start_utc").takeIf { s -> s.isNotBlank() && s != "null" }, venue, bc) }
}

/** The bout behind one of our boxing rows ("Canelo v Mbilli"): both names on the card, within two days of our date. */
internal fun findBout(title: String, start: Instant, bouts: List<BoxBout>): BoxBout? {
    val sides = title.split(Regex("\\s+v(?:s\\.?)?\\s+", RegexOption.IGNORE_CASE)).map { nameWords(it) }.takeIf { it.size == 2 } ?: return null
    val day = start.atZone(ZoneOffset.UTC).toLocalDate()
    fun has(name: String, words: Set<String>) = nameWords(name).let { n -> words.isNotEmpty() && words.all { w -> n.any { it == w || it.startsWith(w) } } }
    return bouts.filter { Math.abs(ChronoUnit.DAYS.between(day, it.date)) <= 2 }.firstOrNull {
        (has(it.a, sides[0]) && has(it.b, sides[1])) || (has(it.a, sides[1]) && has(it.b, sides[0]))
    }
}

object Boxing {
    private val results = Cached(15 * 60_000L) { withContext(Dispatchers.IO) { parseBoxResults(JSONObject(httpGet("$SITE/api/boxing?kind=results"))) } }
    private val schedule = Cached(15 * 60_000L) { withContext(Dispatchers.IO) { parseBoxSchedule(JSONObject(httpGet("$SITE/api/boxing?kind=schedule"))) } }
    suspend fun bout(e: Event): BoxBout? = coroutineScope {
        val r = async { runCatching { results.get() }.getOrDefault(emptyList()) }
        val s = async { runCatching { schedule.get() }.getOrDefault(emptyList()) }
        findBout(e.title, e.start, r.await()) ?: findBout(e.title, e.start, s.await())
    }
}
