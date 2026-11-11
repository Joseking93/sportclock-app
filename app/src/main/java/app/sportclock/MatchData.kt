package app.sportclock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Everything the match page shows, read from one ESPN match summary (site.api.espn.com/.../summary?event=id):
 * the header, the timeline of goals, cards and subs, both lineups placed in their formations, the team stats,
 * the commentary, the league table, and form and head-to-head. Parsing is kept apart from drawing so it can be tested.
 */

data class MpTeam(val name: String, val logo: String, val color: Long?, val alt: Long?, val score: Int?)

/** One line of the timeline. [kind]: goal, own, pen, penmiss, yellow, red, sub, or a marker: ht, ft. */
data class MpEvent(val minute: String, val kind: String, val home: Boolean, val player: String = "", val other: String = "", val score: String = "")

data class MpPlayer(val name: String, val number: String, val pos: String, val goals: Int = 0, val yellow: Boolean = false, val red: Boolean = false,
                    val offAt: String? = null, val onAt: String? = null)

/** A team's starting eleven in rows from the keeper forwards, each row from the team's left to its right; then the bench. */
data class MpSide(val formation: String, val lines: List<List<MpPlayer>>, val bench: List<MpPlayer>)

data class MpStat(val label: String, val home: String, val away: String, val hv: Double, val av: Double, val lowerBetter: Boolean = false)
data class MpComment(val minute: String, val text: String, val kind: String)
data class MpRow(val rank: String, val team: String, val played: String, val gd: String, val points: String)

data class MatchData(
    val home: MpTeam, val away: MpTeam, val state: String, val detail: String,
    val events: List<MpEvent>, val homeSide: MpSide?, val awaySide: MpSide?,
    val possession: Pair<Double, Double>?, val stats: List<MpStat>, val commentary: List<MpComment>,
    val tableName: String, val table: List<MpRow>, val venue: String, val attendance: Int?, val referee: String,
    val homeForm: List<FormGame>, val awayForm: List<FormGame>, val meetings: List<Meeting>, val series: String,
) {
    val started get() = state != "pre"
    /** "Ødegaard 72', Saka 80'" for one side, from the timeline. */
    fun scorers(home: Boolean) = events.filter { it.home == home && it.kind in GOALS }.reversed()
        .joinToString(", ") { "${surname(it.player)} ${it.minute}${if (it.kind == "own") " (OG)" else if (it.kind == "pen") " (P)" else ""}" }
}

val GOALS = setOf("goal", "own", "pen")

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun surname(n: String) = n.trim().substringAfterLast(' ')
private fun hex(s: String?) = s?.takeIf { it.length == 6 }?.toLongOrNull(16)?.or(0xFF000000)

suspend fun matchData(league: String, id: String): MatchData = withContext(Dispatchers.IO) {
    parseMatch(JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/summary?event=$id")))
}

/* ---------- lineups: rows by how far forward a position plays, then left to right ---------- */

internal fun depth(pos: String) = when {
    pos == "G" -> 0
    pos in setOf("RB", "LB", "CD", "CD-L", "CD-R", "RWB", "LWB", "SW", "D") -> 1
    pos.startsWith("DM") -> 2
    pos.startsWith("CM") || pos.startsWith("LM") || pos.startsWith("RM") || pos == "M" -> 3
    pos.startsWith("AM") || pos.startsWith("LW") || pos.startsWith("RW") -> 4
    else -> 5
}
internal fun lateral(pos: String) = when {
    pos in setOf("LB", "LWB", "LM", "LW") -> -2
    pos in setOf("RB", "RWB", "RM", "RW") -> 2
    pos.endsWith("-L") -> -1
    pos.endsWith("-R") -> 1
    else -> 0
}

/** Starters into rows: the keeper, then the formation's lines ("4-2-3-1"); by depth alone when the formation doesn't add up. */
internal fun formationLines(formation: String, starters: List<MpPlayer>): List<List<MpPlayer>> {
    val sorted = starters.sortedWith(compareBy({ depth(it.pos) }, { lateral(it.pos) }))
    val counts = formation.split('-').mapNotNull { it.trim().toIntOrNull() }
    val rows = if (counts.isNotEmpty() && counts.sum() + 1 == starters.size) {
        var i = 0
        (listOf(1) + counts).map { c -> sorted.subList(i, i + c).also { i += c } }
    } else sorted.groupBy { depth(it.pos) }.toSortedMap().values.toList()
    return rows.map { r -> r.sortedBy { lateral(it.pos) } }
}

/* ---------- the summary ---------- */

internal fun parseMatch(o: JSONObject): MatchData {
    val comp = o.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()
    val cs = comp.optJSONArray("competitors").objs()
    fun team(side: String): MpTeam {
        val c = cs.firstOrNull { it.optString("homeAway") == side } ?: JSONObject()
        val t = c.optJSONObject("team") ?: JSONObject()
        val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href") ?: t.optString("logo")
        return MpTeam(t.optString("displayName"), logo.orEmpty(), hex(t.optString("color")), hex(t.optString("alternateColor")),
            c.optString("score").toIntOrNull())
    }
    val home = team("home"); val away = team("away")
    val st = comp.optJSONObject("status")?.optJSONObject("type") ?: JSONObject()
    val state = st.optString("state").ifEmpty { "pre" }
    val detail = st.optString("shortDetail").ifEmpty { st.optString("detail") }

    // timeline, oldest first while counting the score, shown newest first
    val events = ArrayList<MpEvent>()
    var h = 0; var a = 0
    val ke = o.optJSONArray("keyEvents").objs()
    ke.forEachIndexed { idx, k ->
        val type = k.optJSONObject("type")?.optString("text").orEmpty()
        val minute = k.optJSONObject("clock")?.optString("displayValue").orEmpty()
        val isHome = k.optJSONObject("team")?.optString("displayName") == home.name
        val ps = k.optJSONArray("participants").objs().map { it.optJSONObject("athlete")?.optString("displayName").orEmpty() }
        when {
            k.optBoolean("scoringPlay") -> {
                if (isHome) h++ else a++
                val kind = when { type.startsWith("Own Goal") -> "own"; type.startsWith("Penalty") -> "pen"; else -> "goal" }
                events += MpEvent(minute, kind, isHome, ps.firstOrNull().orEmpty(), "", "$h - $a")
            }
            type == "Penalty - Missed" || type == "Penalty - Saved" -> events += MpEvent(minute, "penmiss", isHome, ps.firstOrNull().orEmpty(),
                if (type.endsWith("Saved")) "Saved" else "Missed")
            type == "Yellow Card" -> events += MpEvent(minute, "yellow", isHome, ps.firstOrNull().orEmpty())
            type.contains("Red Card") -> events += MpEvent(minute, "red", isHome, ps.firstOrNull().orEmpty())
            type == "Substitution" && ps.size >= 2 -> events += MpEvent(minute, "sub", isHome, ps[0], ps[1])
            type == "Halftime" -> events += MpEvent("", "ht", false, score = "$h - $a")
            type == "End Regular Time" -> {
                val more = ke.drop(idx + 1).any { it.optBoolean("scoringPlay") || it.optJSONObject("type")?.optString("text") == "Substitution" }
                events += MpEvent("", if (more || state != "post") "90" else "ft", false, score = "$h - $a")
            }
        }
    }

    // lineups
    val sides = o.optJSONArray("rosters").objs().associate { r ->
        val list = r.optJSONArray("roster").objs()
        fun player(p: JSONObject): MpPlayer {
            val plays = p.optJSONArray("plays").objs()
            fun at(flag: String) = plays.firstOrNull { it.optBoolean(flag) }?.optJSONObject("clock")?.optString("displayValue")
            val starter = p.optBoolean("starter")
            return MpPlayer(p.optJSONObject("athlete")?.optString("displayName").orEmpty(), p.optString("jersey"),
                p.optJSONObject("position")?.optString("abbreviation").orEmpty(),
                goals = plays.count { it.optBoolean("scoringPlay") && !it.optBoolean("ownGoal") },
                yellow = plays.any { it.optBoolean("yellowCard") }, red = plays.any { it.optBoolean("redCard") },
                offAt = if (starter && p.optBoolean("subbedOut")) at("substitution") ?: "" else null,
                onAt = if (!starter && p.optBoolean("subbedIn")) at("substitution") ?: "" else null)
        }
        val starters = list.filter { it.optBoolean("starter") }.map(::player)
        val bench = list.filter { !it.optBoolean("starter") }.map(::player)
            .sortedWith(compareBy({ it.onAt == null }, { it.number.toIntOrNull() ?: 99 }))
        r.optString("homeAway") to (if (starters.size < 7) null else MpSide(r.optString("formation"), formationLines(r.optString("formation"), starters), bench))
    }

    // team stats
    val box = o.optJSONObject("boxscore")?.optJSONArray("teams").objs()
    fun statsOf(name: String) = box.firstOrNull { it.optJSONObject("team")?.optString("displayName") == name }
        ?.optJSONArray("statistics").objs().associate { it.optString("name") to it.optString("displayValue") }
    val hs = statsOf(home.name).orEmpty(); val asx = statsOf(away.name).orEmpty()
    fun num(m: Map<String, String>, k: String) = m[k]?.toDoubleOrNull()
    val possession = num(hs, "possessionPct")?.let { p -> p to (num(asx, "possessionPct") ?: (100 - p)) }
    val stats = ArrayList<MpStat>()
    fun add(label: String, key: String, lower: Boolean = false) {
        val x = num(hs, key); val y = num(asx, key)
        if (x != null && y != null) stats += MpStat(label, hs.getValue(key).substringBefore(".0"), asx.getValue(key).substringBefore(".0"), x, y, lower)
    }
    add("Total shots", "totalShots"); add("Shots on target", "shotsOnTarget"); add("Corners", "wonCorners"); add("Passes", "totalPasses")
    run {
        val x = num(hs, "accuratePasses"); val y = num(asx, "accuratePasses"); val tx = num(hs, "totalPasses"); val ty = num(asx, "totalPasses")
        if (x != null && y != null && tx != null && ty != null && tx > 0 && ty > 0)
            stats += MpStat("Accurate passes", "${x.toInt()} (${Math.round(100 * x / tx)}%)", "${y.toInt()} (${Math.round(100 * y / ty)}%)", x / tx, y / ty)
    }
    add("Fouls", "foulsCommitted", true); add("Offsides", "offsides", true); add("Yellow cards", "yellowCards", true)
    if ((num(hs, "redCards") ?: 0.0) + (num(asx, "redCards") ?: 0.0) > 0) add("Red cards", "redCards", true)
    add("Saves", "saves")
    // before kick-off ESPN sends a stats block of zeros; it means nothing yet
    val haveStats = state != "pre" && stats.any { it.hv + it.av > 0 }

    // commentary, newest first
    val commentary = o.optJSONArray("commentary").objs().map { c ->
        val t = c.optJSONObject("play")?.optJSONObject("type")?.optString("type").orEmpty()
        val text = c.optString("text")
        val kind = when {
            text.startsWith("Goal!") || text.startsWith("Own Goal") -> "goal"
            t.contains("yellow") -> "yellow"
            t.contains("red-card") || t.contains("red card") -> "red"
            t.contains("substitution") -> "sub"
            else -> ""
        }
        MpComment(c.optJSONObject("time")?.optString("displayValue").orEmpty(), text, kind)
    }.filter { it.text.isNotBlank() }.reversed()

    // the league table
    val sd = o.optJSONObject("standings")
    val entries = sd?.optJSONArray("groups")?.optJSONObject(0)?.optJSONObject("standings")?.optJSONArray("entries").objs()
    fun sv(e: JSONObject, n: String) = e.optJSONArray("stats").objs().firstOrNull { it.optString("name") == n }?.optString("displayValue").orEmpty()
    val table = entries.map { MpRow(sv(it, "rank"), it.optString("team"), sv(it, "gamesPlayed"), sv(it, "pointDifferential"), sv(it, "points")) }

    // form and head-to-head
    val x = extrasOf(o, home.name, away.name)
    val gi = o.optJSONObject("gameInfo") ?: JSONObject()
    val referee = gi.optJSONArray("officials").objs().firstOrNull()?.optString("displayName").orEmpty()

    return MatchData(home, away, state, detail, events.reversed(), sides["home"], sides["away"],
        possession.takeIf { haveStats }, if (haveStats) stats else emptyList(), commentary,
        sd?.optString("header").orEmpty().removeSuffix(" Standings"), table,
        gi.optJSONObject("venue")?.optString("fullName").orEmpty(), gi.optInt("attendance").takeIf { it > 0 }, referee,
        x.homeForm, x.awayForm, x.meetings, x.series)
}
