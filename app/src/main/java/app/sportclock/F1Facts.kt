package app.sportclock

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * "Worth knowing": F1 facts for the race weekend, as swipeable cards on F1 > Weekend and race sheets, and one line on
 * Home in race week. The site's daily job works them out from F1DB (scripts/build_facts.py in the sportclock repo), so
 * the phone only fetches one small file.
 */

data class FactRow(val name: String, val sub: String, val value: Double, val text: String, val top: Boolean)
data class Fact(val id: String, val kind: String, val tag: String, val title: String, val unit: String, val rows: List<FactRow>,
                val value: String, val body: String, val note: String, val scope: String, val drivers: List<String>)
data class FactsDoc(val release: String, val nextDate: LocalDate?, val credit: String, val facts: List<Fact>)

private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun parseFacts(json: String): FactsDoc {
    val o = JSONObject(json)
    val facts = o.optJSONArray("facts").objects().map { f ->
        Fact(f.optString("id"), f.optString("kind"), f.optString("tag"), f.optString("title"), f.optString("unit"),
            f.optJSONArray("rows").objects().map { FactRow(it.optString("name"), it.optString("sub"), it.optDouble("value", 0.0), it.optString("text"), it.optBoolean("top")) },
            f.optString("value"), f.optString("body"), f.optString("note"), f.optString("scope"),
            f.optJSONArray("drivers")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
    }
    val next = o.optJSONObject("next")?.optString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return FactsDoc(o.optString("release"), next, o.optString("credit", "F1DB (CC BY 4.0)"), facts)
}

/**
 * The cards for [race]. Facts about the next circuit go when the file is for another race (F1DB takes a day or so to
 * catch up after a Grand Prix), and the ones about your favourite driver come first.
 */
fun factsFor(doc: FactsDoc?, race: Event?, favourite: String?): List<Fact> {
    if (doc == null || race == null) return emptyList()
    val same = doc.nextDate != null && Duration.between(doc.nextDate.atStartOfDay().toInstant(ZoneOffset.UTC), race.start).abs().toDays() <= 3
    val key = favourite?.let(::personKey)
    return doc.facts.filter { same || it.scope != "gp" }.sortedBy { f -> if (key != null && f.drivers.any { personKey(it) == key }) 0 else 1 }
}

object F1Facts {
    var doc by mutableStateOf<FactsDoc?>(null)

    /** Kept on the phone so the cards appear at once; fetched again after six hours. */
    suspend fun load(ctx: Context) = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "f1facts.json")
        if (doc == null && f.exists()) runCatching { doc = parseFacts(f.readText()) }
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3600_000L) return@withContext
        runCatching {
            val body = httpGet("$SITE/f1facts.json")
            val d = parseFacts(body)                                       // a bad file never replaces a good one
            f.writeText(body); doc = d
        }.onFailure { Diag.log("f1 facts: $it") }
    }
}

/** One card: bars for a comparison, a single big number otherwise. */
@Composable
fun FactCard(f: Fact, credit: String, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth().heightIn(min = 256.dp)
    .clip(RoundedCornerShape(18.dp)).background(C.tile2).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
    Text(f.tag, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(f.title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp))
    if (f.kind == "bars") {
        val max = f.rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
        f.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(Modifier.padding(vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = if (r.top) C.fg else C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.sub.isNotBlank()) Text(r.sub, color = C.fg3, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(56.dp)) {
                    Box(Modifier.width((56 * (r.value / max)).toInt().coerceAtLeast(6).dp).height(if (r.top) 22.dp else 14.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (r.top) C.lime else C.outline))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.End) {
                    Text(r.text, style = Digits, color = if (r.top) C.lime else C.fg, fontSize = if (r.top) 32.sp else 22.sp, lineHeight = if (r.top) 34.sp else 24.sp, maxLines = 1)
                    if (r.top) Text(f.unit, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f.value, style = Digits, color = C.lime, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, lineHeight = 64.sp)
            Spacer(Modifier.width(8.dp))
            Text(f.unit, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(f.body, color = C.fg, fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
    }
    Spacer(Modifier.weight(1f, fill = false))
    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(f.note, Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Data: ${credit.substringBefore(" (")}", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The swipeable row. Facts give away past results, so spoiler-free mode hides them until tapped. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorthKnowing(race: Event, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { F1Facts.load(ctx) }
    val doc = F1Facts.doc
    val facts = remember(doc, race.id) { factsFor(doc, race, Repo.fav(ctx, Kind.DRIVER)?.name) }
    if (doc == null || facts.isEmpty()) return
    Column(modifier) {
        Text("Worth knowing", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Reveal("facts:${doc.release}") {
            val state = rememberPagerState(pageCount = { facts.size })
            // every card measured, so the row keeps one height as you swipe
            HorizontalPager(state, pageSpacing = 10.dp, beyondViewportPageCount = facts.size, verticalAlignment = Alignment.Top) { i -> FactCard(facts[i], doc.credit) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                facts.indices.forEach { i ->
                    val on = i == state.currentPage
                    Box(Modifier.padding(horizontal = 2.dp).size(if (on) 16.dp else 5.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(if (on) C.lime else C.outline))
                }
            }
        }
    }
}

/** Home in race week, for anyone following F1: one fact, a new one each day. Tapping it opens the race. */
@Composable
fun FactOfTheDay(events: List<Event>, follows: Set<String>, now: Instant, onOpen: (Event) -> Unit) {
    val ctx = LocalContext.current
    if ("c:F1" !in follows) return
    val race = remember(events, now.epochSecond / 3600) { currentGp(events, now) } ?: return
    if (Duration.between(now, race.start).toDays() > 6) return
    LaunchedEffect(Unit) { F1Facts.load(ctx) }
    val doc = F1Facts.doc ?: return
    if (Spoilers.on) return
    val facts = remember(doc, race.id) { factsFor(doc, race, Repo.fav(ctx, Kind.DRIVER)?.name) }
    if (facts.isEmpty()) return
    val f = facts[(localDate(now).dayOfYear % facts.size)]
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).clickable { onOpen(race) }.padding(14.dp)) {
        Text("WORTH KNOWING · ${f.tag}", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(f.title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
