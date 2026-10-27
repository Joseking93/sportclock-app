package app.sportclock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** What a football card needs beyond our own data: both clubs' badges and colours, and who is in each squad. */
data class MatchLook(val home: TeamSide, val away: TeamSide, val squad: Set<String>)

/** "Vinícius Júnior" and "Vinicius Junior" are the same person. */
fun personKey(name: String) = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Badges, colours and squads from ESPN, fetched by the phone and kept for the session: a scoreboard for 30 minutes,
 * a squad for 12 hours. A failed look is retried after 10 minutes; until then the card simply has no art.
 */
object Looks {
    val ready = mutableStateMapOf<String, MatchLook>()
    private val failed = HashMap<String, Long>()
    private val boards = HashMap<Pair<String, String>, Pair<Long, List<EspnMatch>>>()
    private val rosters = HashMap<String, Pair<Long, Set<String>>>()
    private val lock = Mutex()

    suspend fun load(e: Event): MatchLook? {
        ready[e.id]?.let { return it }
        if (e.code == "NFL") return Nfl.sides[e.id]?.let { (h, a) -> MatchLook(h, a, emptySet()).also { ready[e.id] = it } }
        val lg = ESPN_LEAGUE[e.code] ?: return null
        if (e.sport != Sport.FOOTBALL || e.teams.size != 2) return null
        val t = System.currentTimeMillis()
        if (t - (failed[e.id] ?: 0L) < 10 * 60_000) return null
        return lock.withLock {
            runCatching {
                val m = board(e, lg) ?: error("no ESPN match")
                val h = m.homeSide ?: error("no badge"); val a = m.awaySide ?: error("no badge")
                MatchLook(h, a, roster(lg, h.id) + roster(lg, a.id)).also { ready[e.id] = it }
            }.getOrElse { failed[e.id] = t; null }
        }
    }

    /** Our fixture's ESPN match (id, league, names), from a scoreboard kept for 30 minutes. */
    suspend fun espnMatch(e: Event): EspnMatch? {
        val lg = ESPN_LEAGUE[e.code] ?: return null
        if (e.sport != Sport.FOOTBALL || e.teams.size != 2) return null
        return runCatching { lock.withLock { board(e, lg) } }.getOrNull()
    }

    private suspend fun board(e: Event, lg: String): EspnMatch? {
        val t = System.currentTimeMillis()
        val key = lg to espnDate(e.start)
        val board = boards[key]?.takeIf { t - it.first < 30 * 60_000 }?.second
            ?: espnScoreboard(lg, key.second).also { boards[key] = t to it }
        return matchEspn(e, board)
    }

    /** A club's current squad as person keys; empty when ESPN has none, so the badges still show. */
    private suspend fun roster(lg: String, teamId: String): Set<String> {
        val t = System.currentTimeMillis()
        rosters["$lg/$teamId"]?.takeIf { t - it.first < 12 * 3600_000L }?.let { return it.second }
        val names = runCatching {
            withContext(Dispatchers.IO) {
                val a = JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/$lg/teams/$teamId/roster")).optJSONArray("athletes")
                (0 until (a?.length() ?: 0)).flatMap { i -> a!!.getJSONObject(i).let { listOf(it.optString("displayName"), it.optString("fullName")) } }
                    .filter { it.isNotBlank() }.map(::personKey).toSet()
            }
        }.getOrDefault(emptySet())
        rosters["$lg/$teamId"] = t to names
        return names
    }
}

/* ---------- colours ---------- */

private fun hex(s: String): Color? = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
private fun Color.usable() = luminance() in 0.02f..0.8f                // pure white or black would drown the badge
private fun dist(a: Color, b: Color) = Math.sqrt(((a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) + (a.blue - b.blue) * (a.blue - b.blue)).toDouble())
private val NEUTRAL = Color(0xFF3A3D35)

/**
 * Each club's own colour for its half. When the two are too alike (Man Utd v Bournemouth: both red), the away half
 * goes a darker shade of its own colour rather than ESPN's second colour, which is often not the club's (Bournemouth: lilac).
 */
fun sideColors(h: TeamSide, a: TeamSide): Pair<Color, Color> {
    fun pick(s: TeamSide) = listOfNotNull(hex(s.color), hex(s.alt)).firstOrNull { it.usable() } ?: NEUTRAL
    val hc = pick(h); var ac = pick(a)
    if (dist(hc, ac) < 0.3) {
        val darker = lerp(ac, Color.Black, 0.5f)                    // a darker shade, or a lighter one if both are dark already
        ac = if (dist(darker, hc) >= 0.3) darker else lerp(ac, Color.White, 0.4f)
    }
    return hc to ac
}

/**
 * The NEXT UP art for a match with no favourite player in it: the two clubs half and half, a slanted edge between
 * their colours, fading into the card on the left like the photos do. The badges go on top of the card's text shade
 * ([BadgeLogos]) so they stay crisp; both are placed to sit between the pin button and the countdown ring.
 */
@Composable
fun BadgeBackdrop(look: MatchLook, modifier: Modifier = Modifier) {
    val (hc, ac) = sideColors(look.home, look.away)
    Canvas(modifier
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.15f to Color.Black.copy(alpha = .5f), 0.32f to Color.Black), blendMode = BlendMode.DstIn)
        }) {
        val w = size.width; val h = size.height
        val top = w - 98.dp.toPx(); val bottom = w - 128.dp.toPx()            // the slanted edge between the halves
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(top, 0f); lineTo(bottom, h); lineTo(0f, h); close() }, hc.copy(alpha = .62f))
        drawPath(Path().apply { moveTo(top, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(bottom, h); close() }, ac.copy(alpha = .62f))
        drawLine(Color.White.copy(alpha = .28f), Offset(top, 0f), Offset(bottom, h), strokeWidth = 2.dp.toPx())
    }
}

@Composable
fun BadgeLogos(look: MatchLook, modifier: Modifier = Modifier) = Box(modifier) {
    Row(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AsyncImage(look.home.logo, look.home.name, Modifier.size(44.dp))
        AsyncImage(look.away.logo, look.away.name, Modifier.size(44.dp))
    }
}
