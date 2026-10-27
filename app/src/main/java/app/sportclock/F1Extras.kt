package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Race-week content for F1, all free: news from five F1 feeds, the FIA's documents for the current Grand Prix
 * (stewards' decisions, penalties, classifications) and the title fight worked out from the standings.
 */

/** F1 news from F1.com, BBC Sport, Autosport, Motorsport.com and RaceFans, newest first. */
suspend fun f1News(): List<Story> = coroutineScope {
    listOf("https://www.formula1.com/en/latest/all.xml" to "F1.com", "https://feeds.bbci.co.uk/sport/formula1/rss.xml" to "BBC",
        "https://www.autosport.com/rss/f1/news/" to "Autosport", "https://www.motorsport.com/rss/f1/news/" to "Motorsport.com",
        "https://www.racefans.net/feed/" to "RaceFans")
        .map { (url, tag) -> async { runCatching { rss(url, tag) }.getOrDefault(emptyList()) } }
        .awaitAll().flatten()
        .distinctBy { it.title.lowercase().take(60) }
        .sortedByDescending { it.at ?: Instant.EPOCH }.take(60)
}

/* ---------- FIA documents ---------- */

data class FiaDoc(val number: Int?, val title: String, val at: Instant?, val url: String) {
    /** Stewards' business: infringements, decisions, penalties, summonses. */
    val stewards get() = listOf("Infringement", "Decision", "Penalty", "Summons", "Offence").any { title.contains(it, ignoreCase = true) }
}
data class FiaEvent(val name: String, val docs: List<FiaDoc>)

private val FIA_TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
private val FIA_ZONE = ZoneId.of("Europe/Paris")                      // the FIA site shows its own (CET/CEST) times

/** The documents of the most recent Grand Prix on the FIA's F1 page, newest first. */
suspend fun fiaDocs(): FiaEvent? = withContext(Dispatchers.IO) {
    val html = httpGetAs("https://www.fia.com/documents/championships/fia-formula-one-world-championship-14", "Mozilla/5.0 (Linux; Android) SportClock")
    parseFiaDocs(html)
}

internal fun parseFiaDocs(html: String): FiaEvent? {
    // the page lists the latest event first; keep only its section
    val events = Regex("""class="event-title[^"]*"[^>]*>\s*([^<]+?)\s*<""").findAll(html).toList()
    if (events.isEmpty()) return null
    val section = html.substring(events[0].range.last, events.getOrNull(1)?.range?.first ?: html.length)
    val docs = Regex("""<a href="(/system/files/decision-document/[^"]+\.pdf)"[\s\S]*?<div class="title">\s*([^<]+?)\s*</div>[\s\S]*?class="date-display-single"[^>]*>([^<]+)<""")
        .findAll(section).map { m ->
            val raw = unescape(m.groupValues[2]).trim()
            val num = Regex("""^Doc (\d+)\s*-\s*""").find(raw)
            FiaDoc(num?.groupValues?.get(1)?.toIntOrNull(), num?.let { raw.removeRange(it.range) } ?: raw,
                runCatching { LocalDateTime.parse(m.groupValues[3].trim(), FIA_TIME).atZone(FIA_ZONE).toInstant() }.getOrNull(),
                "https://www.fia.com" + m.groupValues[1])
        }.toList()
    return FiaEvent(events[0].groupValues[1].trim(), docs.sortedByDescending { it.number ?: 0 })
}

private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'").replace("&quot;", "\"")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

/* ---------- the title fight ---------- */

data class Contender(val name: String, val team: String, val points: Int, val behind: Int, val alive: Boolean)
data class TitleFight(val leader: Contender, val rest: List<Contender>, val racesLeft: Int, val sprintsLeft: Int, val available: Int,
                      val clinchNote: String?, val asOf: String)

/**
 * Who can still win the drivers' title: points still available are 25 a Grand Prix and 8 a sprint (no fastest-lap point
 * since 2025). [clinchNote] says what the leader needs at the next round to settle it there, when that's possible.
 */
suspend fun titleFight(now: Instant = Instant.now()): TitleFight? {
    val (asOf, rows) = f1Standings(false)
    if (rows.size < 2) return null
    val year = now.atZone(java.time.ZoneOffset.UTC).year
    val left = season(year).filter { it.start?.isAfter(now.minusSeconds(3 * 3600)) == true }
    val races = left.size; val sprints = left.count { it.sprint }
    val available = races * 25 + sprints * 8
    val lead = rows[0].points.toDoubleOrNull()?.toInt() ?: return null
    val all = rows.take(8).map { r ->
        val p = r.points.toDoubleOrNull()?.toInt() ?: 0
        Contender(r.name, r.team, p, lead - p, p + available >= lead)
    }
    val next = left.firstOrNull()
    val note = next?.let { n ->
        val second = all.getOrNull(1) ?: return@let null
        val afterNext = available - 25 - (if (n.sprint) 8 else 0)
        // after the next round the title is settled if the gap is bigger than everything still to come, so the leader
        // needs to outscore the runner-up there by at least `need` (zero or less: the runner-up must outscore the leader)
        val need = afterNext - second.behind + 1
        val roundMax = 25 + if (n.sprint) 8 else 0
        val who = all[0].name.substringAfterLast(' '); val rival = second.name.substringAfterLast(' '); val gp = n.name.replace("Grand Prix", "GP")
        when {
            second.behind > available -> "$who is already champion."
            need <= 0 -> "$who is champion at $gp unless $rival outscores him there by ${1 - need} or more."
            need <= roundMax -> "$who can clinch at $gp by outscoring $rival there by $need or more."
            else -> null
        }
    }
    return TitleFight(all[0], all.drop(1), races, sprints, available, note, asOf)
}
