package app.sportclock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/*
 * The F1 home page, laid out like the F1 app redesign Jose sent (amanbirdi, Nov 2026): the race card with its countdown
 * that turns into live session pills, quick links, the story carousel, this week's videos, F1 Moments (Shorts), top news,
 * your driver, the race and qualifying highlights, the standings with your driver marked, and the season's highlights.
 * Videos come from Formula 1's own YouTube channel through sportclock.pages.dev/api/f1video and open in YouTube.
 * No store and no sponsor banners.
 */

/* ======================= F1's YouTube videos ======================= */

data class YtVideo(val id: String, val title: String, val published: Instant? = null, val ago: String = "", val length: String = "",
                   val short: Boolean = false) {
    val url get() = if (short) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id"
    /** 480x360 with the video letterboxed in it: cropped to 16:9 (or 9:16 for a Short) it is just the picture. */
    val thumb get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

internal fun parseVideos(o: JSONObject): List<YtVideo> {
    val a = o.optJSONArray("videos") ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val v = a.optJSONObject(i) ?: return@mapNotNull null
        val id = v.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        YtVideo(id, v.optString("title"), v.optString("published").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            v.optString("ago"), v.optString("length"), v.optBoolean("short"))
    }
}

/** "São Paulo Grand Prix" and "Sao Paulo GP" both read "sao paulo": how YouTube titles and Jolpica names are compared. */
internal fun gpPlace(name: String): String = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    .replace("grand prix", "").replace(Regex("\\bgp\\b"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/** "3 days ago", "5 hours ago": posted in the last ten days (YouTube's search says when, not the date). */
internal fun recentAgo(ago: String): Boolean {
    val n = Regex("(\\d+)").find(ago)?.value?.toIntOrNull() ?: return false
    return ago.contains("minute") || ago.contains("hour") || (ago.contains("day") && n <= 10)
}
/**
 * The names a Grand Prix goes by: its race name and its city. Jolpica's "Brazilian Grand Prix" is F1's "Sao Paulo Grand Prix"
 * on YouTube, and only the city (São Paulo) is in both.
 */
internal fun gpPlaces(vararg names: String?): List<String> = names.filterNotNull().map(::gpPlace).filter { it.length >= 3 }.distinct()

/** A video about this Grand Prix this year: its title names the place, and the year or it is from the last ten days (previews name no year). */
private fun about(v: YtVideo, places: List<String>, year: Int) = gpPlace(v.title).let { t -> places.any { t.contains(it) } } &&
    (v.title.contains(year.toString()) || (recentAgo(v.ago) && !Regex("\\b(19|20)\\d\\d\\b").containsMatchIn(v.title)))

internal fun raceHighlights(vs: List<YtVideo>, places: List<String>, year: Int) = vs.firstOrNull { it.title.startsWith("Race Highlights", true) && about(it, places, year) }
internal fun qualiHighlights(vs: List<YtVideo>, places: List<String>, year: Int) = vs.firstOrNull { it.title.startsWith("Qualifying Highlights", true) && about(it, places, year) }
/** A weekend's other videos (practice highlights, onboards, drivers' reactions, previews), the race and qualifying ones aside. */
internal fun weekVideos(vs: List<YtVideo>, places: List<String>, year: Int) = vs.filter { about(it, places, year) && !it.short &&
    !it.title.startsWith("Race Highlights", true) && !it.title.startsWith("Qualifying Highlights", true) && !it.title.startsWith("LIVE", true) }
/** Every race's highlights this season, in calendar order, newest first. [calendar] is each round's [gpPlaces]. */
internal fun seasonHighlights(vs: List<YtVideo>, year: Int, calendar: List<List<String>>): List<YtVideo> {
    val all = vs.filter { it.title.startsWith("Race Highlights", true) && it.title.contains(year.toString()) }
    return all.distinctBy { it.id }.sortedByDescending { v -> gpPlace(v.title).let { t -> calendar.indexOfFirst { ps -> ps.any { t.contains(it) } } } }
}

object F1Videos {
    private val cache = HashMap<String, Pair<Long, List<YtVideo>>>()
    private suspend fun load(path: String, ttlMin: Long): List<YtVideo> {
        synchronized(cache) { cache[path] }?.takeIf { System.currentTimeMillis() - it.first < ttlMin * 60_000 }?.let { return it.second }
        val vs = withContext(Dispatchers.IO) { parseVideos(JSONObject(httpGet("$SITE/api/f1video?$path"))) }
        if (vs.isNotEmpty()) synchronized(cache) { cache[path] = System.currentTimeMillis() to vs }
        return vs
    }
    suspend fun feed() = load("kind=feed", 30)
    suspend fun search(q: String) = load("q=" + Uri.encode(q), 60)
}

/* ======================= the rest of the page's data ======================= */

/** Where a driver finished in the last Grand Prix, "P9", from Jolpica; null when he didn't start or isn't found. */
suspend fun lastFinish(driver: String): String? = withContext(Dispatchers.IO) {
    val race = JSONObject(httpGet("https://api.jolpi.ca/ergast/f1/current/last/results.json")).getJSONObject("MRData").getJSONObject("RaceTable")
        .optJSONArray("Races")?.optJSONObject(0) ?: return@withContext null
    val rs = race.optJSONArray("Results") ?: return@withContext null
    val key = personKey(driver.substringAfterLast(' '))
    (0 until rs.length()).map { rs.getJSONObject(it) }.firstOrNull { personKey(it.optJSONObject("Driver")?.optString("familyName").orEmpty()) == key }
        ?.let { r -> r.optString("positionText").let { if (it.toIntOrNull() != null) "P$it" else "DNF" } }
}

/** Each team's colour, for its chips and your driver's card. */
fun f1TeamColor(team: String): Color = when {
    team.contains("McLaren", true) -> Color(0xFFFF8000)
    team.contains("Ferrari", true) -> Color(0xFFE8002D)
    team.contains("Mercedes", true) -> Color(0xFF27F4D2)
    team.contains("Racing Bulls", true) || team.contains("RB F1", true) || team == "RB" -> Color(0xFF6692FF)
    team.contains("Red Bull", true) -> Color(0xFF3671C6)
    team.contains("Aston", true) -> Color(0xFF229971)
    team.contains("Alpine", true) -> Color(0xFF0093CC)
    team.contains("Williams", true) -> Color(0xFF64C4FF)
    team.contains("Haas", true) -> Color(0xFFB6BABD)
    team.contains("Audi", true) || team.contains("Sauber", true) -> Color(0xFFF50537)
    team.contains("Cadillac", true) -> Color(0xFFC9C9C9)
    else -> Color(0xFF8E9186)
}

private val F1_COUNTRY = mapOf("Australia" to "AU", "China" to "CN", "Japan" to "JP", "Bahrain" to "BH", "Saudi Arabia" to "SA", "USA" to "US",
    "United States" to "US", "Italy" to "IT", "Monaco" to "MC", "Spain" to "ES", "Canada" to "CA", "Austria" to "AT", "UK" to "GB",
    "United Kingdom" to "GB", "Belgium" to "BE", "Hungary" to "HU", "Netherlands" to "NL", "Azerbaijan" to "AZ", "Singapore" to "SG",
    "Mexico" to "MX", "Brazil" to "BR", "Qatar" to "QA", "UAE" to "AE", "United Arab Emirates" to "AE", "Portugal" to "PT", "France" to "FR",
    "Germany" to "DE", "Turkey" to "TR", "Argentina" to "AR", "South Africa" to "ZA", "Thailand" to "TH", "Korea" to "KR", "Malaysia" to "MY")
/** The country's flag as an emoji: "Brazil" is 🇧🇷. */
fun flagOf(country: String): String = F1_COUNTRY[country]?.let { iso -> iso.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") } ?: ""

/** FP1, FP2, FP3, SQ, S, Q, R: a session's code for the live pills. */
fun sessionCode(session: String) = when (sessName(session).lowercase(Locale.ROOT)) {
    "practice 1" -> "FP1"; "practice 2" -> "FP2"; "practice 3" -> "FP3"; "sprint qualifying" -> "SQ"; "sprint" -> "S"
    "qualifying" -> "Q"; "race" -> "R"; else -> sessName(session).take(3).uppercase(Locale.ROOT)
}

internal val F1_RED = Color(0xFFE10600)

/* ======================= the page ======================= */

@Composable
fun F1Home(events: List<Event>, now: Instant, onOpen: (Event) -> Unit, onPage: (String) -> Unit, onSeg: (Int) -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    val race = remember(events, now.epochSecond / 600) { currentGp(events, now) }
    val year = now.atZone(ZoneOffset.UTC).year
    val fav = remember { Repo.fav(ctx, Kind.DRIVER) }

    // everything the page reads, loaded together; each part may fail on its own and its section then stays away
    var gp by remember { mutableStateOf<GpInfo?>(null) }
    var cal by remember { mutableStateOf<List<SeasonRace>>(emptyList()) }
    var stories by remember { mutableStateOf<List<Story>?>(null) }
    var feed by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var week by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var weekFrom by remember { mutableStateOf("") }
    var watch by remember { mutableStateOf<Pair<YtVideo?, YtVideo?>>(null to null) }
    var watchRace by remember { mutableStateOf<SeasonRace?>(null) }
    var seasonVids by remember { mutableStateOf<List<YtVideo>>(emptyList()) }
    var drivers by remember { mutableStateOf<List<Standing>>(emptyList()) }
    var teams by remember { mutableStateOf<List<Standing>>(emptyList()) }
    var favLast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(race?.id) {
        coroutineScope {
            async { gp = race?.let { runCatching { gpInfo(it) }.getOrNull() } }
            async { stories = runCatching { f1News() }.getOrDefault(emptyList()) }
            async { feed = runCatching { F1Videos.feed() }.getOrDefault(emptyList()) }
            async { drivers = runCatching { f1Standings(false).second }.getOrDefault(emptyList()) }
            async { teams = runCatching { f1Standings(true).second }.getOrDefault(emptyList()) }
            async { favLast = fav?.let { runCatching { lastFinish(it.name) }.getOrNull() } }
            async {
                cal = runCatching { season(year) }.getOrDefault(emptyList())
                val last = cal.lastOrNull { it.start?.isBefore(now.minusSeconds(3 * 3600)) == true }
                watchRace = last
                // a Grand Prix's videos, searched by its race name and by its city ("Brazilian" finds nothing, "São Paulo" does)
                suspend fun videosOf(prefix: String, name: String, city: String?): List<YtVideo> = coroutineScope {
                    listOfNotNull(name, city?.let { "$it Grand Prix" }).distinct()
                        .map { q -> async { runCatching { F1Videos.search("$prefix$year ${q.replace(" GP", " Grand Prix")}") }.getOrDefault(emptyList()) } }
                        .flatMap { it.await() }.distinctBy { it.id }
                }
                coroutineScope {
                    async {
                        if (last != null) {
                            val vs = videosOf("Highlights ", last.name, last.locality)
                            val ps = gpPlaces(last.name, last.locality)
                            watch = raceHighlights(vs, ps, year) to qualiHighlights(vs, ps, year)
                        }
                    }
                    async {
                        // this week: the coming (or current) Grand Prix's videos, else the last one's
                        val gps = listOfNotNull(race?.let { it.title to gp?.locality }, last?.let { it.name to it.locality })
                        for ((i, g) in gps.withIndex()) {
                            val w = weekVideos(videosOf("", g.first, g.second), gpPlaces(g.first, g.second), year)
                            if (w.size >= 3 || i == gps.lastIndex) { week = w.take(8); weekFrom = if (i == 0) "" else g.first.replace("Grand Prix", "GP"); break }
                        }
                    }
                    async {
                        val vs = runCatching { F1Videos.search("Race Highlights | $year") }.getOrDefault(emptyList())
                        seasonVids = seasonHighlights(vs, year, cal.map { gpPlaces(it.name, it.locality) })
                    }
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        race?.let { r -> item { RaceCard(r, events, gp, now, onOpen, onSeg) } }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                QuickChip("News", "≡") { onPage("f1news") }
                QuickChip("Videos", "▷") { open("https://www.youtube.com/@Formula1/videos") }
                fav?.let { p ->
                    val pos = drivers.firstOrNull { personKey(it.name.substringAfterLast(' ')) == personKey(p.name.substringAfterLast(' ')) }?.pos
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.tile).clickable { onSeg(3) }.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(p.avatar), null, contentScale = ContentScale.Crop, alignment = BiasAlignment(p.fx * 2 - 1, p.fy * 2 - 1),
                            modifier = Modifier.size(28.dp).clip(CircleShape))
                        Text("  ${p.name.substringAfterLast(' ')}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (pos != null) Text("  P$pos", style = Digits, color = C.fg2, fontSize = 14.sp)
                    }
                    val tpos = teams.firstOrNull { it.name.contains(p.team, true) || p.team.contains(it.name, true) }?.pos
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.tile).clickable { onSeg(3) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(f1TeamColor(p.team)))
                        Text("  ${p.team}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (tpos != null) Text("  P$tpos", style = Digits, color = C.fg2, fontSize = 14.sp)
                    }
                }
                QuickChip("Standings", "▤") { onSeg(3) }
            }
        }

        // the story carousel: the newest F1 stories with a picture
        val hero = stories.orEmpty().filter { it.image != null }.take(6)
        if (hero.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(hero, key = { it.link }) { s ->
                    Box(Modifier.width(300.dp).height(200.dp).clip(RoundedCornerShape(16.dp)).background(C.tile2).clickable { open(s.link) }) {
                        AsyncImage(s.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
                        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                            Text(s.tag.uppercase(Locale.ROOT), Modifier.clip(RoundedCornerShape(4.dp)).background(F1_RED).padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text(s.title, color = Color.White, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, lineHeight = 21.sp, maxLines = 3,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }

        if (week.isNotEmpty()) {
            item { Heading("This week in F1", listOf(weekFrom, "${week.size} videos").filter { it.isNotBlank() }.joinToString(" · "), null) }
            item {
                Spoiler("f1home") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(week, key = { "w" + it.id }) { v -> StoryTile(v) { open(v.url) } }
                    }
                }
            }
        }

        val shorts = feed.filter { it.short }
        if (shorts.isNotEmpty()) {
            item { Heading("F1 Moments", "Short clips from F1", null) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shorts, key = { "s" + it.id }) { v -> MomentCard(v, now) { open(v.url) } }
                }
            }
        }

        val top = stories.orEmpty().filter { it !in hero }.take(4).ifEmpty { stories.orEmpty().take(4) }
        if (top.isNotEmpty()) {
            item { Heading("Top news", "All news ›") { onPage("f1news") } }
            item {
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile)) {
                    top.forEachIndexed { i, s ->
                        if (i > 0) Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(C.line))
                        Row(Modifier.fillMaxWidth().clickable { open(s.link) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (s.image != null) AsyncImage(s.image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
                            else Box(Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(C.tile2))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${s.tag}${s.at?.let { " · " + ago(it, now) } ?: ""}", color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                    Text("See all", Modifier.fillMaxWidth().clickable { onPage("f1news") }.padding(12.dp), color = C.fg2, fontFamily = Inter,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }

        fav?.let { p ->
            item { Heading("My follows", "Manage") { onPage("follow") } }
            item { DriverCard(p, drivers, favLast, onNews = { onPage("f1news") }) }
        }

        val (rh, qh) = watch
        if (rh != null || qh != null) {
            item { Heading("Watch", "All videos ›") { open("https://www.youtube.com/@Formula1/videos") } }
            item {
                Spoiler("f1home") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        rh?.let { v ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).clickable { open(v.url) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(C.tile2)) {
                                    AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Text("RACE HIGHLIGHTS", Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .75f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    PlayDot(Modifier.align(Alignment.Center), 52.dp)
                                    if (v.length.isNotBlank()) Text(v.length, Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = .75f)).padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = Digits, fontSize = 12.sp)
                                }
                                Column(Modifier.padding(14.dp)) {
                                    watchRace?.let { r -> Text("ROUND ${r.round} · ${r.locality.ifBlank { r.name.replace(" Grand Prix", "") }.uppercase(Locale.ROOT)}", color = C.fg3, fontFamily = Inter,
                                        fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                                    Text(v.title.replace(" | ", ". "), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                                        lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        qh?.let { v ->
                            Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile).clickable { open(v.url) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)).background(C.tile2)) {
                                    AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    PlayDot(Modifier.align(Alignment.Center), 28.dp)
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("QUALIFYING HIGHLIGHTS", color = F1_RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                                    Text(v.title.substringAfter(" | "), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (drivers.isNotEmpty()) {
            item { Heading("Standings", "Full standings ›") { onSeg(3) } }
            item { StandingsCard(drivers, teams, fav) }
        }

        if (seasonVids.isNotEmpty()) {
            item { Heading("$year highlights", "View all ›") { open("https://www.youtube.com/@Formula1/search?query=" + Uri.encode("Race Highlights | $year")) } }
            item {
                Spoiler("f1home") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(seasonVids, key = { "h" + it.id }) { v ->
                            Column(Modifier.width(220.dp).clickable { open(v.url) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(C.tile2)) {
                                    AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    PlayDot(Modifier.align(Alignment.BottomStart).padding(8.dp), 30.dp)
                                }
                                Text(v.title.substringAfter(" | ").replace("Grand Prix", "GP"), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Videos from Formula 1 on YouTube, they open in YouTube. News from F1.com, BBC Sport, Autosport, Motorsport.com and RaceFans. Standings from Jolpica.",
                color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp))
        }
    }
}

/* ---------- pieces ---------- */

@Composable
private fun Heading(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title.uppercase(Locale.ROOT), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
            fontSize = 20.sp, letterSpacing = (-0.3).sp)
        if (action != null) Text(action, Modifier.then(if (onAction != null) Modifier.clickable { onAction() } else Modifier).padding(start = 8.dp),
            color = if (onAction != null) C.fg else C.fg3, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    }
}

@Composable
private fun QuickChip(label: String, glyph: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(C.tile).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = C.fg2, fontSize = 13.sp)
        Text("  $label", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Spoiler-free hides results and highlights here until revealed once, for the whole page. */
@Composable
internal fun Spoiler(key: String, content: @Composable () -> Unit) {
    if (Spoilers.on && key !in Spoilers.revealed) {
        Text("Highlights and results hidden · tap to show", Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile)
            .clickable { Spoilers.revealed += key }.padding(16.dp), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    } else content()
}

@Composable
private fun PlayDot(modifier: Modifier, size: Dp) {
    Box(modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = .92f)), contentAlignment = Alignment.Center) {
        Text("▶", color = Color.Black, fontSize = (size.value * .36f).sp)
    }
}

@Composable
private fun StoryTile(v: YtVideo, onClick: () -> Unit) {
    Box(Modifier.size(118.dp, 160.dp).clip(RoundedCornerShape(12.dp)).background(C.tile2).clickable { onClick() }) {
        AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f))))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            val kind = v.title.substringBefore(" | ", "").takeIf { it.length in 3..28 }
            if (kind != null) Text(kind.uppercase(Locale.ROOT), color = Color.White.copy(alpha = .75f), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 8.5.sp, maxLines = 1)
            Text(if (kind != null) v.title.substringAfter(" | ").substringBefore(" | ").replace(Regex("^\\d{4} "), "").replace("Grand Prix", "GP") else v.title,
                color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MomentCard(v: YtVideo, now: Instant, onClick: () -> Unit) {
    Box(Modifier.size(150.dp, 250.dp).clip(RoundedCornerShape(14.dp)).background(C.tile2).clickable { onClick() }) {
        AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f))))
        if (v.published?.let { Duration.between(it, now).toHours() < 48 } == true)
            Text("NEW", Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE5007E)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        PlayDot(Modifier.align(Alignment.TopEnd).padding(8.dp), 26.dp)
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text("F1 MOMENT", color = Color.White.copy(alpha = .75f), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Text(v.title, color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The weekend at a glance: where and which round, then a ticking countdown to the next session, or the sessions as pills while one is live. */
@Composable
private fun RaceCard(race: Event, events: List<Event>, gp: GpInfo?, now0: Instant, onOpen: (Event) -> Unit, onSeg: (Int) -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = Instant.now() } }
    val weekend = remember(race, events) { events.filter { it.code == "F1" && it.title == race.title && Duration.between(it.start, race.start).abs().toDays() <= 5 }.sortedBy { it.start } }
    val live = weekend.firstOrNull { it.inProgress(now) }
    val next = weekend.firstOrNull { !it.started(now) }
    val place = race.title.replace(" Grand Prix", "").replace(" GP", "")
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.tile).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            gp?.country?.let(::flagOf)?.takeIf { it.isNotEmpty() }?.let { Text("$it ", fontSize = 20.sp) }
            Text(place, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Black, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val dates = weekend.firstOrNull()?.let { f -> "${localDate(f.start).dayOfMonth}–${daySub(localDate(race.start)).substringAfter(' ').uppercase(Locale.ROOT)}" }
        Text(listOfNotNull(gp?.round?.let { "ROUND $it" }, dates).joinToString(" · "), color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, letterSpacing = .5.sp, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile2).padding(12.dp)) {
            if (live != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(F1_RED))
                    Text("  LIVE · ${sessName(live.session).uppercase(Locale.ROOT)}", color = F1_RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        weekend.forEach { s ->
                            val state = when { s == live -> "LIVE"; s.over(now) -> "DONE"; s == weekend.firstOrNull { !it.started(now) } -> "NEXT"; else -> "LATER" }
                            Column(Modifier.width(38.dp).clip(RoundedCornerShape(8.dp)).background(if (state == "LIVE") F1_RED else C.tile)
                                .border(1.dp, if (state == "LIVE") F1_RED else C.line, RoundedCornerShape(8.dp)).clickable { onOpen(s) }.padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(sessionCode(s.session), color = if (state == "LIVE") Color.White else if (state == "DONE") C.fg3 else C.fg, fontFamily = Inter,
                                    fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                Text(if (state == "DONE") "✓" else state, color = if (state == "LIVE") Color.White else C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 7.5.sp)
                            }
                        }
                    }
                    DarkButton("Timing ›") { onSeg(2) }
                }
            } else if (next != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${sessName(next.session).uppercase(Locale.ROOT)} · ${dayWord(next.start, now).uppercase(Locale.ROOT)} ${hm(next.start)} YOUR TIME",
                        Modifier.weight(1f), color = F1_RED, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    // the session's alert, as on its own sheet: on with F1 followed, the bell turns it off or on
                    if (AlertChoices.on) AlertBell(next, 20.dp)
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val left = Duration.between(now, next.start).coerceAtLeast(Duration.ZERO)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(left.toDays() to "DAYS", left.toHoursPart().toLong() to "HRS", left.toMinutesPart().toLong() to "MIN", left.toSecondsPart().toLong() to "SEC")
                            .forEachIndexed { i, (n, l) ->
                                Column(Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(C.tile).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("%02d".format(n), style = Digits, color = if (i == 3) F1_RED else C.fg, fontSize = 20.sp)
                                    Text(l, color = C.fg3, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 7.5.sp)
                                }
                            }
                    }
                    DarkButton("Schedule ›") { onSeg(1) }
                }
            } else {
                Text("Weekend over · results in the Weekend tab", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DarkButton(label: String, onClick: () -> Unit) {
    Text(label, Modifier.clip(RoundedCornerShape(10.dp)).background(C.fg).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        color = C.bg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
}

/** Your driver: his car on track behind his name in his team's colour, the season so far and his last race. */
@Composable
private fun DriverCard(p: Person, drivers: List<Standing>, last: String?, onNews: () -> Unit) {
    val me = drivers.firstOrNull { personKey(it.name.substringAfterLast(' ')) == personKey(p.name.substringAfterLast(' ')) }
    val team = me?.team?.takeIf { it.isNotBlank() } ?: p.team
    val col = f1TeamColor(team)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            Image(painterResource(p.photo), null, contentScale = ContentScale.Crop, alignment = BiasAlignment(.35f, 0f), modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to col, 0.42f to col.copy(alpha = .82f), 0.75f to col.copy(alpha = .15f), 1f to Color.Transparent)))
            Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                Text(p.name.substringBeforeLast(' '), color = Color.White.copy(alpha = .9f), fontFamily = Inter, fontStyle = FontStyle.Italic, fontSize = 16.sp)
                Text(p.name.substringAfterLast(' ').uppercase(Locale.ROOT), color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = 1.sp)
                Text(team, color = Color.White.copy(alpha = .85f), fontFamily = Inter, fontSize = 12.sp)
                Text("News", Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Color.White, RoundedCornerShape(14.dp)).clickable { onNews() }
                    .padding(horizontal = 14.dp, vertical = 5.dp), color = Color.White, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            Text("★", Modifier.align(Alignment.TopEnd).padding(12.dp), color = Color.White, fontSize = 18.sp)
        }
        Row(Modifier.fillMaxWidth().background(col.copy(alpha = .28f)).padding(vertical = 10.dp)) {
            listOf("Pos." to (me?.pos ?: "–"), "Points" to (me?.points ?: "–"), "Last race" to (last ?: "–")).forEach { (l, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l, color = Color.White.copy(alpha = .7f), fontFamily = Inter, fontSize = 11.sp)
                    Text(v, style = Digits, color = Color.White, fontSize = 22.sp)
                }
            }
        }
    }
}

/** The top three, and your driver (or his team) marked wherever he is. */
@Composable
private fun StandingsCard(drivers: List<Standing>, teams: List<Standing>, fav: Person?) {
    var cons by remember { mutableStateOf(false) }
    val rows = if (cons) teams else drivers
    fun mine(s: Standing) = fav != null && if (cons) (s.name.contains(fav.team, true) || fav.team.contains(s.name, true))
        else personKey(s.name.substringAfterLast(' ')) == personKey(fav.name.substringAfterLast(' '))
    val shown = (rows.take(3) + rows.filter(::mine)).distinct()
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).padding(10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile2).padding(3.dp)) {
            listOf(false to "Drivers", true to "Constructors").forEach { (c, l) ->
                Text(l, Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (cons == c) C.tile else Color.Transparent).clickable { cons = c }.padding(vertical = 8.dp),
                    color = if (cons == c) C.fg else C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        shown.forEach { s ->
            val m = mine(s)
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (m) F1_RED.copy(alpha = .14f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.pos, Modifier.width(26.dp), style = Digits, color = C.fg, fontSize = 17.sp)
                Box(Modifier.size(width = 4.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)).background(f1TeamColor(if (cons) s.name else s.team)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    val name = if (cons) s.name else "${s.name.first()}. ${s.name.substringAfterLast(' ')}"
                    Text(name + if (m) " ★" else "", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                    if (!cons && s.team.isNotBlank()) Text(s.team + if (m) " · You follow" else "", color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
                    else if (m) Text("You follow", color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
                }
                Text(s.points, style = Digits, color = C.fg, fontSize = 18.sp)
                Text(" PTS", color = C.fg3, fontFamily = Inter, fontSize = 9.sp)
            }
        }
    }
}
