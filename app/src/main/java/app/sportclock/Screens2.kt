package app.sportclock

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.Duration
import java.time.Instant

/* ---------- competition logos: on a white tile, so dark logos (Premier League, Champions League) read on the dark app ---------- */
fun logoFor(code: String): Int? = when (code) {
    "F1" -> R.drawable.logo_f1; "MGP" -> R.drawable.logo_mgp; "NAS" -> R.drawable.logo_nas; "INDY" -> R.drawable.logo_indy
    "ENG" -> R.drawable.logo_eng; "EFL" -> R.drawable.logo_efl; "ESP" -> R.drawable.logo_esp; "ITA" -> R.drawable.logo_ita
    "GER" -> R.drawable.logo_ger; "FRA" -> R.drawable.logo_fra; "NED" -> R.drawable.logo_ned; "POR" -> R.drawable.logo_por
    "UCL" -> R.drawable.logo_ucl; "UFC" -> R.drawable.logo_ufc; else -> null
}

@Composable
fun LogoChip(code: String, size: Dp = 22.dp) {
    val res = logoFor(code)
    if (res == null) { Dot(seriesColor(code), (size.value * 0.45f).dp); return }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Color.White).padding(size / 9), contentAlignment = Alignment.Center) {
        Image(painterResource(res), series(code)?.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/* ---------- segmented control ---------- */
@Composable
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.tile).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp)).background(if (i == selected) C.lime else Color.Transparent)
                .clickable { onSelect(i) }, contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) C.onLime else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp, bottom = 10.dp)) {
        Text(title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        sub?.let { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp) }
    }
}

/* ---------- Scores · Tables · F1 ---------- */
@Composable
fun ScoresHub(st: AppState, d: Data, now: Instant, scores: Scores, onOpen: (Event) -> Unit) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(listOf("Scores", "Tables", "F1 standings")[seg], listOf("Football, yours first", "League tables", "Drivers and teams")[seg])
        Segments(listOf("Scores", "Tables", "F1"), seg) { seg = it }
        Spacer(Modifier.height(8.dp))
        when (seg) {
            0 -> ScoresList(st, d, now, scores, onOpen)
            1 -> TablesView(st)
            else -> F1View()
        }
    }
}

@Composable
private fun ScoresList(st: AppState, d: Data, now: Instant, scores: Scores, onOpen: (Event) -> Unit) {
    val today = localDate(now)
    val recent = d.events.filter { it.sport == Sport.FOOTBALL && (localDate(it.start) == today || localDate(it.start) == today.minusDays(1) || it.inProgress(now)) }
    val mine = recent.filter { followed(it, st.follows) }
    val nextMine = d.events.filter { it.sport == Sport.FOOTBALL && followed(it, st.follows) && !it.started(now) }.take(2)
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionLabel("Your teams") }
        val yours = (mine + nextMine).distinct().sortedBy { it.start }
        if (yours.isEmpty()) item { Text("No football you follow today", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        yours.forEach { e -> item(key = "m" + e.id) { EventRow(e, now, scores.matches[e.id], onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) } }
        listOf(today to "Today", today.minusDays(1) to "Yesterday").forEach { (day, label) ->
            val list = recent.filter { it !in mine && localDate(it.start) == day }
            if (list.isNotEmpty()) {
                item(key = "d$day") { SectionLabel(label) }
                list.groupBy { it.code }.forEach { (code, l) ->
                    item(key = "c$day$code") {
                        Row(Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LogoChip(code, 20.dp); Spacer(Modifier.width(8.dp))
                            Text(series(code)?.name ?: code, color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    l.forEach { e -> item(key = "o" + e.id) { EventRow(e, now, scores.matches[e.id], onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) } }
                }
            }
        }
        item {
            Text("Scores from ESPN · ${scores.fetchedAt?.let { "checked ${hm(it)}" } ?: "checking…"}", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
                modifier = Modifier.padding(20.dp))
        }
    }
}

@Composable
private fun TablesView(st: AppState) {
    val comps = remember(st.follows) {
        val followedComps = st.follows.filter { it.startsWith("c:") }.map { it.substring(2) }.filter { it in ESPN_LEAGUE }
        (listOf("ENG", "UCL") + followedComps + ESPN_LEAGUE.keys).distinct()
    }
    var code by rememberSaveable { mutableStateOf("ENG") }
    var table by remember { mutableStateOf<Table?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(code) { Diag.log("table $code"); table = null; failed = false; runCatching { espnTable(code) }.onSuccess { table = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failed = true } }
    val myTeams = st.follows.filter { it.startsWith("t:") }.map { it.substring(2) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            comps.forEach { c ->
                FilterChip(c == code, { code = c }, label = { Text(series(c)?.name ?: c, fontFamily = Inter) }, leadingIcon = { LogoChip(c, 18.dp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
            }
        }
        val t = table
        when {
            failed -> Text("Couldn't load the table. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
            t == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
            else -> LazyColumn {
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text("#", Modifier.width(28.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                        Text("Team", Modifier.weight(1f), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                        listOf("P", "GD", "Pts").forEach { Text(it, Modifier.width(40.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp) }
                    }
                }
                items(t.rows) { r ->
                    val mine = myTeams.any { nameWords(it).intersect(nameWords(r.team)).size >= 2 || it == r.team }
                    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (mine) Color(0x26C8F53C) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.rank}", Modifier.width(28.dp), style = Digits, fontSize = 17.sp, color = if (mine) C.lime else C.fg2)
                        Text(short(r.team), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = if (mine) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.played, Modifier.width(40.dp), style = Digits, fontSize = 17.sp, color = C.fg2)
                        Text(r.gd, Modifier.width(40.dp), style = Digits, fontSize = 17.sp, color = C.fg2)
                        Text(r.points, Modifier.width(40.dp), style = Digits, fontSize = 17.sp, color = C.fg)
                    }
                }
                item { Text("Tables from ESPN", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable
private fun F1View() {
    var teams by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<Pair<String, List<Standing>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(teams) { data = null; failed = false; runCatching { f1Standings(teams) }.onSuccess { data = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failed = true } }
    Column {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Drivers", true to "Teams").forEach { (t, label) ->
                FilterChip(teams == t, { teams = t }, label = { Text(label, fontFamily = Inter) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
            }
        }
        val d = data
        when {
            failed -> Text("Couldn't load the standings. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp))
            d == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
            else -> LazyColumn {
                item { Text(d.first, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(d.second) { s ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile)
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.pos, Modifier.width(30.dp), style = Digits, fontSize = 20.sp, color = if (s.pos == "1") C.lime else C.fg2)
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            if (s.team.isNotEmpty()) Text(s.team, color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                        }
                        Text(s.points, style = Digits, fontSize = 20.sp, color = C.fg)
                        Text(" pts", color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                    }
                }
                item { Text("Standings from Jolpica (Ergast)", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

/* ---------- News ---------- */
@Composable
fun NewsScreen(st: AppState, now: Instant) {
    val ctx = LocalContext.current
    var stories by remember { mutableStateOf<List<Story>?>(null) }
    var tag by rememberSaveable { mutableStateOf("All") }
    LaunchedEffect(st.follows) { stories = runCatching { news(st.follows) }.getOrDefault(emptyList()) }
    val tags = listOf("All") + (stories.orEmpty().map { it.tag }.distinct())
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("News", "Headlines for what you follow") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { t -> FilterChip(t == tag, { tag = t }, label = { Text(t, fontFamily = Inter) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg)) }
            }
        }
        when (val s = stories) {
            null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) } }
            else -> {
                if (s.isEmpty()) item { Text("No headlines right now. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
                items(s.filter { tag == "All" || it.tag == tag }, key = { it.link }) { n ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile)
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link))) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (n.image != null) AsyncImage(n.image, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width = 104.dp, height = 70.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(n.title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${n.tag} · BBC Sport${n.at?.let { " · " + ago(it, now) } ?: ""}", color = C.fg2, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

fun ago(t: Instant, now: Instant): String {
    val m = Duration.between(t, now).toMinutes()
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 24 * 60 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
}

/* ---------- Settings: alerts and text size ---------- */
@Composable
fun SettingsScreen(onBack: () -> Unit, onChanged: () -> Unit, onTextScale: (Float) -> Unit) {
    val ctx = LocalContext.current
    var alerts by remember { mutableStateOf(Settings.alertsOn(ctx)) }
    var lead by remember { mutableIntStateOf(Settings.leadMins(ctx)) }
    var scale by remember { mutableFloatStateOf(Settings.textScale(ctx)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        alerts = granted; Settings.setAlertsOn(ctx, granted); onChanged()
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
            }
            Text("Settings", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 20.dp))
        }
        item { SectionLabel("Alerts") }
        item {
            ToggleRow(C.lime, "Starting-soon alerts", "For everything you follow, even with the app closed", alerts) { on ->
                if (on && Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { alerts = on; Settings.setAlertsOn(ctx, on); onChanged() }
            }
        }
        item {
            Text("How long before", color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 60).forEach { m ->
                    FilterChip(lead == m, { lead = m; Settings.setLeadMins(ctx, m); onChanged() }, label = { Text(if (m == 60) "1 hour" else "$m min", fontFamily = Inter) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
                }
            }
        }
        item { SectionLabel("Text size") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.85f to "Small", 0.95f to "Medium", 1.1f to "Large", 0f to "Phone").forEach { (v, label) ->
                    FilterChip(scale == v, { scale = v; Settings.setTextScale(ctx, v); onTextScale(v) }, label = { Text(label, fontFamily = Inter) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
                }
            }
            Text("\"Phone\" follows your phone's own text size. The others ignore it.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp))
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

/* ---------- lineups and goals in the match sheet ---------- */
@Composable
fun MatchSheetView(e: Event, score: Score?, now: Instant) {
    var sheet by remember(e.id) { mutableStateOf<MatchSheet?>(null) }
    var looked by remember(e.id) { mutableStateOf(false) }
    val minsToKick = Duration.between(now, e.start).toMinutes()
    LaunchedEffect(e.id, score?.espnId) {
        if (minsToKick > 75) return@LaunchedEffect
        val (lg, id) = (score?.league to score?.espnId).let { (l, i) ->
            if (l != null && i != null) l to i
            else ESPN_LEAGUE[e.code]?.let { league -> runCatching { matchEspn(e, espnScoreboard(league, espnDate(e.start))) }.getOrNull()?.let { league to it.id } } ?: (null to null)
        }
        if (lg != null && id != null) sheet = runCatching { espnSheet(lg, id) }.getOrNull()
        looked = true
    }
    Column(Modifier.padding(top = 14.dp)) {
        when {
            minsToKick > 75 -> Text("Lineups usually appear about an hour before kick-off.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            !looked -> Text("Looking for lineups…", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            sheet == null || (sheet!!.home == null && sheet!!.goals.isEmpty()) -> Text("Lineups not announced yet.", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            else -> {
                val s = sheet!!
                if (s.goals.isNotEmpty()) {
                    Text("Goals", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    s.goals.forEach { g -> Text("${g.minute}  ${g.text}", color = C.fg, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp)) }
                    Spacer(Modifier.height(10.dp))
                }
                if (s.home != null && s.away != null) {
                    Text("Lineups", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(Modifier.padding(top = 6.dp)) {
                        listOf(s.home, s.away).forEach { t ->
                            Column(Modifier.weight(1f)) {
                                Text(short(t.team), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (t.formation.isNotEmpty()) Text(t.formation, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
                                t.starters.forEach { p -> Text("${p.number.padStart(2)}  ${p.name}", color = C.fg, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (t.subs.isNotEmpty()) Text("Subs: " + t.subs.joinToString(", ") { it.name.substringAfterLast(' ') }, color = C.fg3, fontFamily = Inter,
                                    fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, end = 6.dp))
                            }
                        }
                    }
                }
                Text("Lineups and goals from ESPN", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
