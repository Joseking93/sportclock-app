package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Everything for an F1 weekend page, all free:
 *  - Jolpica: the season calendar (round, circuit, coordinates) and last year's race at the circuit.
 *  - OpenF1: each meeting's circuit key, so the MultiViewer track map can be drawn for future races too.
 *  - ESPN (this weekend) or Jolpica (earlier weekends): session results.
 *  - Open-Meteo: the forecast at the circuit for each session, up to 16 days ahead.
 */

data class GpInfo(val year: Int, val round: Int, val raceName: String, val circuitId: String, val circuit: String,
                  val locality: String, val country: String, val lat: Double, val lon: Double, val wiki: String)
data class LastYear(val year: Int, val winner: String, val team: String, val laps: String, val time: String, val fastest: String?)
data class Forecast(val temp: Double, val rain: Int, val wind: Double, val code: Int)

private val cache = HashMap<String, Any>()
private suspend fun cachedJson(url: String): JSONObject = (cache[url] as? JSONObject)
    ?: withContext(Dispatchers.IO) { JSONObject(httpGet(url)) }.also { cache[url] = it }
private suspend fun cachedArray(url: String): JSONArray = (cache[url] as? JSONArray)
    ?: withContext(Dispatchers.IO) { JSONArray(httpGet(url)) }.also { cache[url] = it }

private fun sameGp(a: String, b: String) = a.replace("Grand Prix", "GP").trim().equals(b.replace("Grand Prix", "GP").trim(), ignoreCase = true)
private fun year(e: Event) = e.start.atZone(ZoneOffset.UTC).year

/** This Grand Prix in the Jolpica calendar: by name, or failing that the race within four days. */
suspend fun gpInfo(e: Event): GpInfo? {
    val y = year(e)
    val races = cachedJson("https://api.jolpi.ca/ergast/f1/$y.json").getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races") ?: return null
    val all = (0 until races.length()).map { races.getJSONObject(it) }
    val r = all.firstOrNull { sameGp(it.optString("raceName"), e.title) }
        ?: all.firstOrNull { runCatching { Duration.between(Instant.parse(it.optString("date") + "T" + it.optString("time", "12:00:00Z")), e.start).abs().toDays() <= 4 }.getOrDefault(false) }
        ?: return null
    val c = r.getJSONObject("Circuit"); val loc = c.optJSONObject("Location") ?: JSONObject()
    return GpInfo(y, r.optInt("round"), r.optString("raceName"), c.optString("circuitId"), c.optString("circuitName"), loc.optString("locality"),
        loc.optString("country"), loc.optString("lat").toDoubleOrNull() ?: 0.0, loc.optString("long").toDoubleOrNull() ?: 0.0, c.optString("url"))
}

/** Last year's race at this circuit: winner, laps, time and the fastest lap. */
suspend fun lastYear(g: GpInfo): LastYear? {
    val y = g.year - 1
    val race = cachedJson("https://api.jolpi.ca/ergast/f1/$y/circuits/${g.circuitId}/results/1.json").getJSONObject("MRData")
        .getJSONObject("RaceTable").optJSONArray("Races")?.optJSONObject(0) ?: return null
    val w = race.optJSONArray("Results")?.optJSONObject(0) ?: return null
    val fast = runCatching {
        cachedJson("https://api.jolpi.ca/ergast/f1/$y/circuits/${g.circuitId}/fastest/1/results.json").getJSONObject("MRData")
            .getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0).getJSONArray("Results").getJSONObject(0)
            .let { "${it.getJSONObject("Driver").optString("familyName")} ${it.getJSONObject("FastestLap").getJSONObject("Time").optString("time")} (lap ${it.getJSONObject("FastestLap").optString("lap")})" }
    }.getOrNull()
    return LastYear(y, w.getJSONObject("Driver").let { "${it.optString("givenName")} ${it.optString("familyName")}" }, w.optJSONObject("Constructor")?.optString("name").orEmpty(),
        w.optString("laps"), w.optJSONObject("Time")?.optString("time").orEmpty(), fast)
}

/** F1's circuit key for this meeting (from OpenF1's calendar), which the track map needs. */
suspend fun circuitKey(e: Event): Int? {
    val meetings = cachedArray("https://api.openf1.org/v1/meetings?year=${year(e)}")
    val all = (0 until meetings.length()).map { meetings.getJSONObject(it) }
    val m = all.firstOrNull { sameGp(it.optString("meeting_name"), e.title) }
        ?: all.firstOrNull { runCatching { Duration.between(Instant.parse(it.optString("date_start").replace("+00:00", "Z")), e.start).abs().toDays() <= 4 }.getOrDefault(false) }
    return m?.optInt("circuit_key")?.takeIf { it > 0 }
}

/**
 * Results so far this weekend. ESPN carries the current weekend, practice included; for an earlier weekend Jolpica has
 * qualifying, the sprint and the race.
 */
suspend fun weekendResults(e: Event, g: GpInfo?): List<RaceSession> {
    val espn = runCatching { espnRacing("F1") }.getOrNull()
    if (espn != null && (espn.name.contains(e.title.removeSuffix(" Grand Prix"), ignoreCase = true)) &&
        espn.start?.let { Duration.between(it, e.start).abs().toDays() <= 4 } != false) return espn.sessions
    g ?: return emptyList()
    val base = "https://api.jolpi.ca/ergast/f1/${g.year}/${g.round}"
    suspend fun table(path: String, key: String, name: String, time: (JSONObject) -> String): RaceSession? = runCatching {
        val race = cachedJson("$base/$path.json?limit=30").getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races")?.optJSONObject(0) ?: return null
        val rows = race.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().map { r ->
            val d = r.getJSONObject("Driver")
            RaceRow(r.optString("position").toIntOrNull() ?: 0, "${d.optString("givenName")} ${d.optString("familyName")}", r.optJSONObject("Constructor")?.optString("name").orEmpty(), time(r))
        }
        if (rows.isEmpty()) null else RaceSession(name, "post", null, rows)
    }.getOrNull()
    return listOfNotNull(
        table("qualifying", "QualifyingResults", "Qualifying") { it.optString("Q3").ifBlank { it.optString("Q2").ifBlank { it.optString("Q1") } } },
        table("sprint", "SprintResults", "Sprint") { it.optJSONObject("Time")?.optString("time") ?: it.optString("status") },
        table("results", "Results", "Race") { it.optJSONObject("Time")?.optString("time") ?: it.optString("status") },
    )
}

/** The hourly forecast at the circuit for each of [times] (up to 16 days ahead). */
suspend fun forecast(lat: Double, lon: Double, times: List<Instant>): Map<Instant, Forecast> {
    val now = Instant.now()
    val wanted = times.filter { it.isAfter(now.minusSeconds(3 * 3600)) && it.isBefore(now.plusSeconds(15 * 86400)) }
    if (wanted.isEmpty()) return emptyMap()
    val o = cachedJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m&forecast_days=16&timezone=UTC")
    val h = o.getJSONObject("hourly")
    val stamps = h.getJSONArray("time").let { a -> (0 until a.length()).map { a.getString(it) } }
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00").withZone(ZoneOffset.UTC)
    return wanted.mapNotNull { t ->
        val i = stamps.indexOf(fmt.format(t)).takeIf { it >= 0 } ?: return@mapNotNull null
        t to Forecast(h.getJSONArray("temperature_2m").optDouble(i), h.getJSONArray("precipitation_probability").optInt(i),
            h.getJSONArray("wind_speed_10m").optDouble(i), h.getJSONArray("weather_code").optInt(i))
    }.toMap()
}

/** WMO weather codes, as words. */
fun weatherWord(code: Int) = when (code) {
    0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "fog"; in 51..57 -> "drizzle"; in 61..67 -> "rain"
    in 71..77 -> "snow"; in 80..82 -> "showers"; in 95..99 -> "thunderstorms"; else -> ""
}
