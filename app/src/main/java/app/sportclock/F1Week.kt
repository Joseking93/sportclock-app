package app.sportclock

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.time.Instant

/*
 * Race week on the F1 weekend page: the title fight, the stewards' documents for the Grand Prix, and the latest news.
 * Kept for the session, so moving between tabs doesn't fetch them again.
 */

private object WeekCache {
    var news: List<Story>? = null; var fia: FiaEvent? = null; var title: TitleFight? = null; var at = 0L
}

private suspend fun <T> fresh(get: () -> T?, put: (T) -> Unit, load: suspend () -> T?): T? {
    if (System.currentTimeMillis() - WeekCache.at < 15 * 60_000) get()?.let { return it }
    return runCatching { load() }.getOrNull()?.also { put(it); WeekCache.at = System.currentTimeMillis() }
}

@Composable
private fun WeekHeading(t: String, more: String? = null, onMore: (() -> Unit)? = null) =
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t, Modifier.weight(1f), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (more != null && onMore != null) Text(more, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onMore).padding(4.dp))
    }

/** The race-week block: who can still win the title, what the stewards decided, and the news. */
@Composable
fun F1RaceWeek(onPage: (String) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(WeekCache.title) }
    var fia by remember { mutableStateOf(WeekCache.fia) }
    var news by remember { mutableStateOf(WeekCache.news) }
    var loading by remember { mutableStateOf(true) }
    var allDocs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { title = fresh({ WeekCache.title }, { WeekCache.title = it }) { titleFight() } }
            launch { fia = fresh({ WeekCache.fia }, { WeekCache.fia = it }) { fiaDocs() } }
            launch { news = fresh({ WeekCache.news }, { WeekCache.news = it }) { f1News() } }
        }
        loading = false
    }

    // the title fight
    WeekHeading("Title fight")
    val t = title
    if (t == null) { if (loading) Skeleton(90.dp, Modifier.padding(top = 6.dp)) else Text("Standings unavailable right now.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp) }
    else Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("1", Modifier.width(22.dp), style = Digits, fontSize = 18.sp, color = C.lime)
            Text(t.leader.name, Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${t.leader.points}", style = Digits, fontSize = 18.sp, color = C.fg)
        }
        t.rest.take(4).forEachIndexed { i, c ->
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 2}", Modifier.width(22.dp), style = Digits, fontSize = 16.sp, color = C.fg3)
                Text(c.name, Modifier.weight(1f), color = if (c.alive) C.fg else C.fg3, fontFamily = Inter, fontSize = 14.sp)
                Text(if (c.alive) "−${c.behind}" else "−${c.behind} · out", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        Text("${t.racesLeft} Grands Prix${if (t.sprintsLeft > 0) " and ${t.sprintsLeft} sprints" else ""} left · ${t.available} points still available",
            color = C.fg2, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        t.clinchNote?.let { Text(it, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text("Standings from Jolpica, ${t.asOf}", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }

    // the stewards and the FIA
    WeekHeading("Stewards and FIA", if ((fia?.docs?.size ?: 0) > 6) (if (allDocs) "Fewer" else "All ${fia!!.docs.size}") else null) { allDocs = !allDocs }
    val f = fia
    if (f == null) { if (loading) repeat(3) { Skeleton(44.dp, Modifier.padding(top = 4.dp)) } else Text("FIA documents unavailable right now.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp) }
    else {
        Text(f.name, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
        val shown = if (allDocs) f.docs else (f.docs.filter { it.stewards }.take(4) + f.docs.filter { !it.stewards }.take(2)).sortedByDescending { it.number ?: 0 }
        shown.forEach { d ->
            Row(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (d.stewards) C.live.copy(alpha = .10f) else C.tile2)
                .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url))) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.stewards) "⚖️" else "📄", Modifier.width(26.dp), fontSize = 15.sp)
                Column(Modifier.weight(1f)) {
                    Text(d.title, color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(d.number?.let { "Doc $it" }, d.at?.let { "${dayWord(it, Instant.now())} ${hm(it)}" }).joinToString(" · "),
                        color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
                }
            }
        }
    }

    // the news
    WeekHeading("Latest F1 news", "All news") { onPage("f1news") }
    val n = news
    if (n == null) { if (loading) repeat(3) { Skeleton(64.dp, Modifier.padding(top = 4.dp)) } }
    else n.take(6).forEach { s -> NewsRow(s) }
}

@Composable
fun NewsRow(s: Story) {
    val ctx = LocalContext.current
    Row(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile2)
        .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link))) } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.image != null) { AsyncImage(s.image, null, Modifier.size(width = 76.dp, height = 50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(s.title, color = C.fg, fontFamily = Inter, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(s.tag, s.at?.let { dayWord(it, Instant.now()) + " " + hm(it) }).joinToString(" · "), color = C.fg3, fontFamily = Inter, fontSize = 11.sp)
        }
    }
}

/** Every F1 story from the five feeds. */
@Composable
fun F1NewsScreen() {
    var news by remember { mutableStateOf(WeekCache.news) }
    LaunchedEffect(Unit) { news = fresh({ WeekCache.news }, { WeekCache.news = it }) { f1News() } ?: emptyList() }
    val n = news
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Text("F1 news", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
        if (n == null) items(6) { Skeleton(64.dp, Modifier.padding(top = 4.dp)) }
        else items(n, key = { it.link }) { NewsRow(it) }
        item { Text("From F1.com, BBC Sport, Autosport, Motorsport.com and RaceFans.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(vertical = 16.dp)) }
    }
}
