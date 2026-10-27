package app.sportclock

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Sport { MOTORSPORT, FOOTBALL, COMBAT, NFL }

/** One row of data.json. Every time is a UTC instant; the phone's own zone is applied only for display. */
data class Event(
    val id: String,
    val sport: Sport,
    val code: String,
    val comp: String,
    val title: String,
    val session: String,
    val start: Instant,
    val mins: Long,
    val venue: String,
    val status: String,            // confirmed | approx | tbc
    val teams: List<String>,
    val window: Pair<Instant, Instant>?,
    val fdid: Long?,
    val movedFrom: String?,
) {
    val isTbc get() = status == "tbc"
    val isApprox get() = status == "approx"
    val end: Instant get() = start.plusSeconds(mins * 60)
    fun started(now: Instant) = !isTbc && !now.isBefore(start)
    fun over(now: Instant) = if (isTbc) (window?.second ?: start.plusSeconds(86400)).isBefore(now) else end.isBefore(now)
    fun inProgress(now: Instant) = started(now) && !over(now)
}

data class Series(val code: String, val name: String, val sport: Sport, val color: Color)

val SERIES = listOf(
    Series("F1", "Formula 1", Sport.MOTORSPORT, Color(0xFFFF6A3D)),
    Series("MGP", "MotoGP", Sport.MOTORSPORT, Color(0xFFFF9A2E)),
    Series("NAS", "NASCAR Cup", Sport.MOTORSPORT, Color(0xFFF2D33D)),
    Series("INDY", "IndyCar", Sport.MOTORSPORT, Color(0xFF4FB7FF)),
    Series("ENG", "Premier League", Sport.FOOTBALL, Color(0xFFE95FC4)),
    Series("UCL", "Champions League", Sport.FOOTBALL, Color(0xFF5C8DFF)),
    Series("EFL", "Championship", Sport.FOOTBALL, Color(0xFF3FD0C0)),
    Series("FACUP", "FA Cup", Sport.FOOTBALL, Color(0xFF5AA7F8)),
    Series("CARABAO", "Carabao Cup", Sport.FOOTBALL, Color(0xFF9B7BEA)),
    Series("ESP", "La Liga", Sport.FOOTBALL, Color(0xFFFF8A65)),
    Series("ITA", "Serie A", Sport.FOOTBALL, Color(0xFF3FB8E8)),
    Series("GER", "Bundesliga", Sport.FOOTBALL, Color(0xFFE5484D)),
    Series("FRA", "Ligue 1", Sport.FOOTBALL, Color(0xFF9AD94F)),
    Series("NED", "Eredivisie", Sport.FOOTBALL, Color(0xFFFF7A1A)),
    Series("POR", "Primeira Liga", Sport.FOOTBALL, Color(0xFF2ECC71)),
    Series("UFC", "UFC", Sport.COMBAT, Color(0xFFB57CFF)),
    Series("BOX", "Boxing", Sport.COMBAT, Color(0xFFD8A25A)),
    Series("NFL", "NFL", Sport.NFL, Color(0xFF4F7CFF)),
)
fun series(code: String) = SERIES.firstOrNull { it.code == code }
fun seriesColor(code: String) = series(code)?.color ?: Color(0xFF8E9186)

private val SESS = mapOf("Free Practice 1" to "Practice 1", "Free Practice 2" to "Practice 2", "Free Practice 3" to "Practice 3",
    "Sprint Qualifying" to "Sprint qualifying", "Sprint Shootout" to "Sprint qualifying", "Warm Up" to "Warm-up",
    "Green flag" to "Race", "Race start (scheduled)" to "Race", "Main Card" to "Main card", "Early Prelims" to "Early prelims")
fun sessName(s: String) = SESS[s] ?: s

val SHORT = mapOf("Manchester United" to "Man Utd", "Manchester City" to "Man City", "Tottenham Hotspur" to "Tottenham",
    "Newcastle United" to "Newcastle", "Nottingham Forest" to "Nott'm Forest", "Brighton & Hove Albion" to "Brighton",
    "West Ham United" to "West Ham", "Wolverhampton Wanderers" to "Wolves", "Sheffield United" to "Sheffield Utd",
    "AS Roma" to "Roma", "FC Bayern München" to "Bayern", "Paris Saint-Germain FC" to "PSG", "Real Madrid CF" to "Real Madrid",
    "FC Barcelona" to "Barcelona", "FC Internazionale Milano" to "Inter", "Borussia Dortmund" to "Dortmund")
fun short(team: String) = SHORT[team] ?: NFL_TEAMS[team] ?: team

/** Headline line for a row or hero: football says the fixture, everything else says series · session. */
fun Event.line1(): String = if (sport == Sport.NFL) teams.takeIf { it.size == 2 }?.let { "${short(it[1])} @ ${short(it[0])}" } ?: title
    else if (sport == Sport.FOOTBALL) teams.takeIf { it.size == 2 }?.let { "${short(it[0])} v ${short(it[1])}" } ?: title
    else "${if (code == "F1") "F1" else series(code)?.name ?: comp} · ${sessName(session)}"
fun Event.line2(): String = when {
    sport == Sport.FOOTBALL -> if (code in CUP_CODES) {
        listOfNotNull(series(code)?.name ?: comp, session.takeIf { it.isNotBlank() }).joinToString(" · ")
    } else series(code)?.name ?: comp
    sport == Sport.NFL -> "NFL · $session"
    sport == Sport.MOTORSPORT -> title.replace("Grand Prix", "GP")
    else -> title
}

val CUP_CODES = setOf("FACUP", "CARABAO")

fun parseEvents(json: String): Pair<List<Event>, JSONObject> {
    val root = JSONObject(json)
    val arr = root.getJSONArray("events")
    val out = ArrayList<Event>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val startS = o.optString("start", "")
        if (startS.isEmpty()) continue
        val sport = when (o.optString("sport")) { "football" -> Sport.FOOTBALL; "combat" -> Sport.COMBAT; else -> Sport.MOTORSPORT }
        val teams = o.optJSONArray("teams")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val win = o.optJSONArray("window")?.let { a -> if (a.length() == 2) Instant.parse(a.getString(0)) to Instant.parse(a.getString(1)) else null }
        out += Event(
            id = "${o.optString("code")}|${o.optString("title")}|${o.optString("session")}|$startS",
            sport = sport, code = o.optString("code"), comp = o.optString("comp"), title = o.optString("title"),
            session = o.optString("session", if (sport == Sport.FOOTBALL) "Kick-off" else ""),
            start = Instant.parse(startS), mins = o.optLong("mins", 120), venue = o.optString("venue"),
            status = o.optString("status", "confirmed"), teams = teams, window = win,
            fdid = if (o.has("fdid")) o.optLong("fdid") else null,
            movedFrom = o.optString("moved_from").takeIf { it.isNotEmpty() },
        )
    }
    out.sortBy { it.start }
    return out to root
}

/* ---------- follows: "c:CODE" for a whole series, "t:Team" for one club ---------- */
fun followed(e: Event, follows: Set<String>) = follows.any { tokenMatches(it, e) }
fun tokenMatches(token: String, e: Event) = when {
    token.startsWith("c:") -> e.code == token.substring(2)
    token.startsWith("t:") -> e.teams.any { teamTokenMatches(token.substring(2), it) }
    else -> false
}
fun teamTokenMatches(tokenTeam: String, eventTeam: String) =
    if (tokenTeam in NFL_TEAMS || eventTeam in NFL_TEAMS) tokenTeam.equals(eventTeam, ignoreCase = true)
    else sameTeam(tokenTeam, eventTeam) || sameTeam(short(tokenTeam), short(eventTeam))
fun tokenSport(token: String) = if (token.startsWith("t:")) (if (token.substring(2) in NFL_TEAMS) Sport.NFL else Sport.FOOTBALL)
    else series(token.substring(2))?.sport ?: Sport.MOTORSPORT
fun tokenLabel(token: String) = if (token.startsWith("t:")) short(token.substring(2)) else series(token.substring(2))?.name ?: token.substring(2)

/* ---------- time wording (24 h, the phone's zone) ---------- */
val zone: ZoneId get() = ZoneId.systemDefault()
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val DAYSUB = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DOW = DateTimeFormatter.ofPattern("EEEE", Locale.UK)
private val DOWS = DateTimeFormatter.ofPattern("EEE", Locale.UK)
fun localDate(i: Instant): LocalDate = i.atZone(zone).toLocalDate()
fun hm(i: Instant): String = HM.format(i.atZone(zone))
fun timeText(e: Event) = if (e.isTbc) "TBC" else (if (e.isApprox) "~" else "") + hm(e.start)
fun dayTitle(d: LocalDate, today: LocalDate) = when (d) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> DOW.format(d) }
fun daySub(d: LocalDate): String = DAYSUB.format(d)
fun dayWord(i: Instant, now: Instant): String {
    val d = localDate(i); val t = localDate(now)
    return when (d) { t -> "Today"; t.plusDays(1) -> "Tomorrow"; else -> DOWS.format(d) + " " + d.dayOfMonth }
}
/** After-midnight starts sit under their real day; while it is still the evening before, they get a "Tonight" cue. */
fun tonightCue(e: Event, now: Instant): String? {
    if (e.isTbc) return null
    val z: ZonedDateTime = e.start.atZone(zone)
    return if (z.toLocalDate() == localDate(now).plusDays(1) && z.hour < 6) "Tonight" else null
}
fun untilText(mins: Long): String = when {
    mins < 60 -> "$mins min"
    mins % 60 == 0L || mins >= 600 -> "${mins / 60}h"
    else -> "${mins / 60}h ${mins % 60}m"
}
fun ringText(mins: Long): String = when { mins < 60 -> "${mins}m"; mins < 48 * 60 -> "${mins / 60}h"; else -> "${mins / 1440}d" }

/** The one status word a row may carry: LIVE (confirmed only), ON NOW (approximate), Moved, or a countdown inside 12 h. */
sealed interface Pill { data object Live : Pill; data object OnNow : Pill; data object Moved : Pill; data class In(val text: String) : Pill }
fun pillFor(e: Event, now: Instant): Pill? {
    if (e.isTbc) return null
    if (e.inProgress(now)) return if (e.isApprox) Pill.OnNow else Pill.Live
    if (e.movedFrom != null) return Pill.Moved
    val m = Duration.between(now, e.start).toMinutes()
    return if (m in 0..(12 * 60)) Pill.In("in " + untilText(m.coerceAtLeast(1))) else null
}
