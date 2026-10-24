package app.sportclock

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

const val SITE = "https://sportclock.pages.dev"

enum class Filter(val label: String) { FOLLOWING("Following"), ALL("All"), FOOTBALL("Football"), MOTORSPORT("Motorsport"), FIGHT("Fight") }
fun Filter.sport(): Sport? = when (this) { Filter.FOOTBALL -> Sport.FOOTBALL; Filter.MOTORSPORT -> Sport.MOTORSPORT; Filter.FIGHT -> Sport.COMBAT; else -> null }

data class Data(val events: List<Event>, val generated: String, val attribution: String, val fetchedAt: Instant?)
data class Score(val status: String, val home: Int?, val away: Int?, val minute: Int?)
data class Scores(val matches: Map<Long, Score>, val fetchedAt: Instant?, val error: String?)

/** One card in the swipeable NEXT UP pager: the next event for one thing you follow. */
data class Hero(val token: String?, val event: Event, val person: Person?)

object Repo {
    private val _data = MutableStateFlow<Data?>(null)
    val data: StateFlow<Data?> = _data

    private fun cache(ctx: Context) = File(ctx.filesDir, "data.json")
    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("sportclock", Context.MODE_PRIVATE)

    /** Cached copy first (instant, offline), then the network when the copy is older than [maxAgeMin]. */
    suspend fun load(ctx: Context, maxAgeMin: Long = 30): Data? = withContext(Dispatchers.IO) {
        val f = cache(ctx)
        if (_data.value == null && f.exists()) runCatching { _data.value = toData(f.readText(), Instant.ofEpochMilli(f.lastModified())) }
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMin * 60_000
        if (!fresh) runCatching {
            val body = get("$SITE/data.json")
            val d = toData(body, Instant.now())
            f.writeText(body)
            _data.value = d
        }
        _data.value
    }

    private fun toData(json: String, at: Instant?): Data {
        val (events, root) = parseEvents(json)
        return Data(events, root.optString("generated"), root.optString("attribution", "Football data provided by the Football-Data.org API"), at)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "SportClock-Android/2.0")
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    /** Free, delayed football scores through the site's own /api/scores. A score is shown only as the feed sent it. */
    suspend fun scores(ids: Collection<Long>): Scores = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext Scores(emptyMap(), null, null)
        runCatching {
            val o = JSONObject(get("$SITE/api/scores?ids=" + ids.take(200).joinToString(",")))
            val m = o.optJSONObject("matches") ?: JSONObject()
            val map = HashMap<Long, Score>()
            for (k in m.keys()) {
                val s = m.getJSONObject(k)
                map[k.toLong()] = Score(s.optString("status"), s.optIntOrNull("home"), s.optIntOrNull("away"), s.optIntOrNull("minute"))
            }
            Scores(map, o.optString("fetchedAt").takeIf { it.isNotEmpty() && it != "null" }?.let(Instant::parse), o.optString("error").takeIf { it.isNotEmpty() && it != "null" })
        }.getOrElse { Scores(emptyMap(), null, "network") }
    }
    private fun JSONObject.optIntOrNull(k: String) = if (isNull(k) || !has(k)) null else optInt(k)

    /* ---------- what Jose chose ---------- */
    private val DEFAULT_FOLLOWS = setOf("c:F1", "t:Manchester United", "c:UFC")
    fun follows(ctx: Context): Set<String> = prefs(ctx).getStringSet("follows", null) ?: DEFAULT_FOLLOWS
    fun setFollow(ctx: Context, token: String, on: Boolean) {
        val s = follows(ctx).toMutableSet(); if (on) s += token else s -= token
        prefs(ctx).edit().putStringSet("follows", s).apply()
    }
    fun fav(ctx: Context, kind: Kind): Person? = person(prefs(ctx).getString("fav_${kind.name}", null) ?: when (kind) {
        Kind.DRIVER -> "lewis-hamilton"; Kind.FIGHTER -> "alexander-volkanovski"; Kind.PLAYER -> "bruno-fernandes" })
    fun setFav(ctx: Context, p: Person) = prefs(ctx).edit().putString("fav_${p.kind.name}", p.id).apply()
    fun pinned(ctx: Context): String? = prefs(ctx).getString("pinned", null)
    fun setPinned(ctx: Context, token: String?) = prefs(ctx).edit().putString("pinned", token).apply()
}

/** Whose photo leads a hero card: the favourite driver for F1, fighter for UFC, player for a followed club. */
fun heroPerson(token: String?, e: Event, favs: Map<Kind, Person?>): Person? = when {
    token?.startsWith("t:") == true -> favs[Kind.PLAYER]
    e.code == "F1" -> favs[Kind.DRIVER]
    e.code == "UFC" -> favs[Kind.FIGHTER]
    else -> null
}

/** The swipeable NEXT UP cards: one per followed thing that has something coming, soonest first, the pinned one always first. */
fun heroes(events: List<Event>, follows: Set<String>, pinned: String?, filter: Filter, favs: Map<Kind, Person?>, now: Instant): List<Hero> {
    val sport = filter.sport()
    val tokens = follows.filter { sport == null || tokenSport(it) == sport }
    val cards = tokens.mapNotNull { t ->
        events.firstOrNull { tokenMatches(t, it) && !it.started(now) && !it.over(now) }?.let { Hero(t, it, heroPerson(t, it, favs)) }
    }.sortedBy { it.event.start }
    if (cards.isEmpty() && sport != null) {
        events.firstOrNull { it.sport == sport && !it.started(now) && !it.over(now) }?.let { return listOf(Hero(null, it, heroPerson(null, it, favs))) }
    }
    val pin = cards.firstOrNull { it.token == pinned }
    return if (pin != null) listOf(pin) + (cards - pin) else cards
}

/** The list under the heroes: what the pill selects, from now, without the card on screen. */
fun listEvents(events: List<Event>, follows: Set<String>, filter: Filter, now: Instant, exclude: String?): List<Event> {
    val sport = filter.sport()
    val anyFollowedInSport = sport != null && events.any { it.sport == sport && followed(it, follows) }
    val horizon = now.plusSeconds(if (filter == Filter.ALL) 2 * 86400L else 14 * 86400L)
    return events.filter { e ->
        !e.over(now) && e.start.isBefore(horizon) && e.id != exclude && when (filter) {
            Filter.FOLLOWING -> followed(e, follows)
            Filter.ALL -> true
            else -> e.sport == sport && (!anyFollowedInSport || followed(e, follows))
        }
    }
}
