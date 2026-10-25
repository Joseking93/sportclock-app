package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/*
 * F1 live timing, free, straight from Formula 1's own feed (livetiming.formula1.com/signalrcore), the one
 * F1's website and the community dashboards read. One "Subscribe" returns the whole current state of the live or
 * latest session, so the app takes a snapshot when it needs one instead of holding a socket open:
 * negotiate, handshake, subscribe, read the reply, close. Car positions on track and championship predictions
 * are F1 TV only, so they are not asked for.
 *
 * Track maps come from MultiViewer's circuit data; pit stop history from Jolpica; upgrades are the FIA's
 * "Car Presentation Submissions" document for each Grand Prix.
 */

data class F1Driver(val number: String, val tla: String, val name: String, val team: String, val colour: String)
data class F1Line(val number: String, val pos: Int, val gap: String, val interval: String, val laps: Int, val pits: Int,
                  val inPit: Boolean, val retired: Boolean, val stopped: Boolean, val best: String, val tyre: String, val knockedOut: Boolean)
data class F1Msg(val utc: String, val category: String, val flag: String, val message: String, val sector: Int?, val lap: Int?)
data class F1Pit(val number: String, val lap: String, val duration: String)
data class F1Weather(val air: String, val track: String, val rain: Boolean, val wind: String, val humidity: String)
data class F1Snapshot(
    val meeting: String, val session: String, val type: String, val start: Instant?, val circuitKey: Int?, val year: Int,
    val status: String,                // Inactive | Started | Aborted | Finished | Finalised | Ends
    val track: String,                 // 1 green, 2 yellow, 4 safety car, 5 red, 6 VSC, 7 VSC ending
    val lap: Int?, val totalLaps: Int?,
    val drivers: Map<String, F1Driver>, val lines: List<F1Line>, val messages: List<F1Msg>, val pits: List<F1Pit>, val weather: F1Weather?,
) {
    val live get() = status == "Started" || status == "Aborted"
    val over get() = status == "Finished" || status == "Finalised" || status == "Ends"
    val racing get() = type == "Race" || session.contains("Sprint") && !session.contains("Qualifying") && !session.contains("Shootout")
    fun tla(n: String) = drivers[n]?.tla ?: "#$n"
}

fun trackWord(code: String) = when (code) {
    "1" -> "Green flag"; "2" -> "Yellow flag"; "4" -> "Safety car"; "5" -> "Red flag"; "6" -> "Virtual safety car"; "7" -> "VSC ending"
    else -> "Track status $code"
}

private const val SR = "https://livetiming.formula1.com/signalrcore"
private const val RS = "\u001e"
private val TOPICS = listOf("SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "DriverList", "TimingData", "TimingAppData",
    "RaceControlMessages", "WeatherData", "PitLaneTimeCollection")

/** One HTTP call that keeps the load balancer's cookies, which the long-polling transport needs on every request. */
private class SrConn {
    private val cookies = LinkedHashMap<String, String>()
    fun call(url: String, method: String, body: String?): Pair<Int, String> {
        val h = URL(url).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 10_000; h.readTimeout = 30_000
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SportClock")
        h.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
        if (cookies.isNotEmpty()) h.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        try {
            if (body != null) { h.doOutput = true; h.outputStream.use { it.write(body.toByteArray()) } }
            val code = h.responseCode
            h.headerFields["Set-Cookie"]?.forEach { c -> c.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> cookies[k.trim()] = v.trim() } }
            val text = (if (code < 400) h.inputStream else h.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally { h.disconnect() }
    }
}

/** The current state of the live or latest F1 session. */
suspend fun f1Snapshot(): F1Snapshot = withContext(Dispatchers.IO) {
    val c = SrConn()
    val (nc, nb) = c.call("$SR/negotiate?negotiateVersion=1", "POST", "")
    if (nc != 200) error("negotiate $nc")
    val u = "$SR?id=" + URLEncoder.encode(JSONObject(nb).getString("connectionToken"), "UTF-8")
    c.call(u, "POST", """{"protocol":"json","version":1}$RS""")
    c.call(u, "GET", null)                                               // the handshake reply
    val sub = JSONObject().put("type", 1).put("target", "Subscribe").put("arguments", JSONArray().put(JSONArray(TOPICS))).put("invocationId", "0")
    c.call(u, "POST", sub.toString() + RS)
    var result: JSONObject? = null
    for (attempt in 0 until 5) {
        val (code, body) = c.call(u, "GET", null)
        if (code >= 400) error("poll $code")
        body.split(RS).filter { it.isNotBlank() }.forEach { m ->
            val o = runCatching { JSONObject(m) }.getOrNull()
            if (o?.optInt("type") == 3) result = o.optJSONObject("result") ?: error(o.optString("error", "no result"))
        }
        if (result != null) break
    }
    runCatching { c.call(u, "DELETE", null) }
    parseF1(result ?: error("no snapshot"))
}

private fun JSONObject?.entries(): List<Pair<String, JSONObject>> =
    if (this == null) emptyList() else keys().asSequence().mapNotNull { k -> optJSONObject(k)?.let { k to it } }.toList()
private fun JSONObject.anyArray(key: String): List<JSONObject> = when (val v = opt(key)) {
    is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
    is JSONObject -> v.entries().sortedBy { it.first.toIntOrNull() ?: 0 }.map { it.second }
    else -> emptyList()
}
private fun JSONObject.value(key: String) = optJSONObject(key)?.optString("Value") ?: optString(key)

internal fun parseF1(r: JSONObject): F1Snapshot {
    val si = r.optJSONObject("SessionInfo") ?: JSONObject()
    val start = runCatching {
        val off = si.optString("GmtOffset", "00:00:00").let { g -> val neg = g.startsWith("-"); val p = g.trimStart('-', '+').split(":")
            ZoneOffset.ofHoursMinutes(p[0].toInt() * if (neg) -1 else 1, p.getOrElse(1) { "0" }.toInt() * if (neg) -1 else 1) }
        LocalDateTime.parse(si.optString("StartDate")).toInstant(off)
    }.getOrNull()
    val drivers = r.optJSONObject("DriverList").entries().associate { (n, d) ->
        n to F1Driver(n, d.optString("Tla", n), d.optString("FullName").ifBlank { d.optString("BroadcastName") }, d.optString("TeamName"), d.optString("TeamColour"))
    }
    val tyres = r.optJSONObject("TimingAppData")?.optJSONObject("Lines").entries().associate { (n, l) ->
        n to (l.anyArray("Stints").lastOrNull()?.optString("Compound").orEmpty())
    }
    // in qualifying the gap belongs to the current part (Q3 for those still in, the part they went out in for the rest)
    val part = r.optJSONObject("TimingData")?.optInt("SessionPart", 0) ?: 0
    val lines = r.optJSONObject("TimingData")?.optJSONObject("Lines").entries().map { (n, l) ->
        val stats = l.anyArray("Stats")
        val qualiGap = (if (part > 0 && !l.optBoolean("KnockedOut")) stats.getOrNull(part - 1)?.optString("TimeDiffToFastest")
            else stats.lastOrNull { it.optString("TimeDiffToFastest").isNotBlank() }?.optString("TimeDiffToFastest")).orEmpty()
        F1Line(n, l.optString("Position").toIntOrNull() ?: 99,
            l.optString("GapToLeader").ifBlank { l.optString("TimeDiffToFastest").ifBlank { qualiGap } },
            l.value("IntervalToPositionAhead").ifBlank { l.optString("TimeDiffToPositionAhead") },
            l.optInt("NumberOfLaps", 0), l.optInt("NumberOfPitStops", 0),
            l.optBoolean("InPit"), l.optBoolean("Retired"), l.optBoolean("Stopped"), l.value("BestLapTime"), tyres[n].orEmpty(), l.optBoolean("KnockedOut"))
    }.sortedBy { it.pos }
    val msgs = r.optJSONObject("RaceControlMessages")?.anyArray("Messages").orEmpty().map { m ->
        F1Msg(m.optString("Utc"), m.optString("Category"), m.optString("Flag"), m.optString("Message"),
            m.optString("Sector").toIntOrNull(), m.optString("Lap").toIntOrNull())
    }
    val pits = r.optJSONObject("PitLaneTimeCollection")?.optJSONObject("PitTimes").entries().map { (n, p) ->
        F1Pit(p.optString("RacingNumber", n), p.optString("Lap"), p.optString("Duration"))
    }
    val w = r.optJSONObject("WeatherData")?.let { F1Weather(it.optString("AirTemp"), it.optString("TrackTemp"), it.optString("Rainfall") == "1",
        it.optString("WindSpeed"), it.optString("Humidity")) }
    val lc = r.optJSONObject("LapCount")
    return F1Snapshot(si.optJSONObject("Meeting")?.optString("Name").orEmpty(), si.optString("Name"), si.optString("Type"), start,
        si.optJSONObject("Meeting")?.optJSONObject("Circuit")?.optInt("Key"), start?.atZone(ZoneOffset.UTC)?.year ?: 0,
        r.optJSONObject("SessionStatus")?.optString("Status").orEmpty(), r.optJSONObject("TrackStatus")?.optString("Status").orEmpty(),
        lc?.optInt("CurrentLap"), lc?.optInt("TotalLaps"), drivers, lines, msgs, pits, w)
}

/** Marshal sectors under a flag right now, from the race control messages in order: "YELLOW IN TRACK SECTOR 7" until "CLEAR IN TRACK SECTOR 7". */
fun flaggedSectors(msgs: List<F1Msg>): Map<Int, String> {
    val out = LinkedHashMap<Int, String>()
    for (m in msgs) {
        if (m.category != "Flag") continue
        val s = m.sector ?: Regex("""SECTOR (\d+)""").find(m.message)?.groupValues?.get(1)?.toIntOrNull()
        when {
            m.flag == "CLEAR" && s != null -> out.remove(s)
            m.flag == "CLEAR" || m.flag == "GREEN" -> if (m.message.contains("TRACK CLEAR", true) || m.flag == "GREEN") out.clear()
            (m.flag == "YELLOW" || m.flag == "DOUBLE YELLOW") && s != null -> out[s] = m.flag
        }
    }
    return out
}

/* ---------- track maps ---------- */

data class TrackPoint(val number: Int, val x: Double, val y: Double)
data class CircuitMap(val name: String, val x: List<Double>, val y: List<Double>, val rotation: Double, val corners: List<TrackPoint>, val sectors: List<TrackPoint>)

/** A circuit's outline, corners and marshal sectors, kept on the phone after the first download. */
suspend fun circuitMap(cacheDir: File, key: Int, year: Int): CircuitMap = withContext(Dispatchers.IO) {
    val f = File(File(cacheDir, "circuits").apply { mkdirs() }, "$key-$year.json")
    val text = f.takeIf { it.exists() && it.length() > 0 }?.readText() ?: httpGet("https://api.multiviewer.app/api/v1/circuits/$key/$year").also { f.writeText(it) }
    val o = JSONObject(text)
    fun nums(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optDouble(it) } }.orEmpty()
    fun points(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
        TrackPoint(p.optInt("number"), p.optJSONObject("trackPosition")?.optDouble("x") ?: 0.0, p.optJSONObject("trackPosition")?.optDouble("y") ?: 0.0) } }.orEmpty()
    CircuitMap(o.optString("circuitName"), nums("x"), nums("y"), o.optDouble("rotation", 0.0), points("corners"), points("marshalSectors"))
}

/* ---------- pit stops after the race (Jolpica) ---------- */

data class PitStop(val driver: String, val lap: String, val stop: String, val duration: String)

suspend fun jolpicaPitStops(): Pair<String, List<PitStop>> = withContext(Dispatchers.IO) {
    val race = JSONObject(httpGet("https://api.jolpi.ca/ergast/f1/current/last/pitstops.json?limit=100")).getJSONObject("MRData")
        .getJSONObject("RaceTable").optJSONArray("Races")?.optJSONObject(0) ?: return@withContext "" to emptyList()
    val stops = race.optJSONArray("PitStops")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        .map { PitStop(it.optString("driverId").replace('_', ' ').replaceFirstChar { c -> c.uppercase() }, it.optString("lap"), it.optString("stop"), it.optString("duration")) }
    race.optString("raceName") to stops
}

/* ---------- upgrades (FIA) ---------- */

/** The FIA's "Car Presentation Submissions" PDF for a Grand Prix: every team's new parts and why. */
fun fiaUpgradesUrl(meeting: String, year: Int): String {
    val slug = Normalizer.normalize(meeting.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return "https://www.fia.com/system/files/decision-document/${year}_${slug}_-_car_presentation_submissions.pdf"
}

suspend fun fiaUpgradesPdf(cacheDir: File, meeting: String, year: Int): File = withContext(Dispatchers.IO) {
    val f = File(File(cacheDir, "upgrades").apply { mkdirs() }, "${year}_${meeting.hashCode()}.pdf")
    if (!f.exists() || f.length() == 0L) {
        val h = URL(fiaUpgradesUrl(meeting, year)).openConnection() as HttpURLConnection
        h.connectTimeout = 15_000; h.readTimeout = 60_000; h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SportClock")
        try {
            if (h.responseCode != 200) error("not published (${h.responseCode})")
            val tmp = File(f.path + ".part")
            h.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(f)) error("could not save")
        } finally { h.disconnect() }
    }
    f
}

/** Is this snapshot the session [e] is about? F1's start time and ours within an hour of each other. */
fun F1Snapshot.isFor(e: Event) = start != null && Duration.between(start, e.start).abs().toMinutes() <= 60
