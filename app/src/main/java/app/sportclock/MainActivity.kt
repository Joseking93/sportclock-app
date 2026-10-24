package app.sportclock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import app.sportclock.widget.SportClockWidget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SportClockTheme { App() } }
    }
}

enum class Tab(val label: String, val icon: ImageVector) { HOME("Home", Icons.Filled.Home), SCORES("Scores", Icons.Filled.EmojiEvents),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth), MORE("More", Icons.Filled.Menu) }

/** Everything the screens read; changes are written through Repo and mirrored here. */
class AppState(ctx: android.content.Context) {
    var follows by mutableStateOf(Repo.follows(ctx))
    var pinned by mutableStateOf(Repo.pinned(ctx))
    var favs by mutableStateOf(Kind.entries.associateWith { Repo.fav(ctx, it) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { AppState(ctx) }
    val data by Repo.data.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var page by remember { mutableStateOf<String?>(null) }          // "follow" | "about" pushed over More
    var detail by remember { mutableStateOf<Event?>(null) }
    var scores by remember { mutableStateOf(Scores(emptyMap(), null, null)) }

    LaunchedEffect(Unit) { Repo.load(ctx); SportClockWidget().updateAll(ctx) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    // free delayed scores for football that is on now or ended in the last day; asked at most every 65 s
    LaunchedEffect(data) {
        while (true) {
            val t = Instant.now()
            val ids = data?.events.orEmpty().filter { it.fdid != null && it.started(t) && it.start.isAfter(t.minusSeconds(86400)) }.mapNotNull { it.fdid }
            if (ids.isNotEmpty()) scores = Repo.scores(ids)
            delay(65_000)
        }
    }
    fun refreshWidget() = scope.launch { SportClockWidget().updateAll(ctx) }

    BackHandler(enabled = page != null) { page = null }

    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            if (page == null) NavigationBar(containerColor = C.nav, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) },
                        label = { Text(t.label, fontFamily = Inter, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = C.onLime, indicatorColor = C.lime, selectedTextColor = C.lime,
                            unselectedIconColor = C.fg2, unselectedTextColor = C.fg2))
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val d = data
            when {
                page == "follow" -> FollowScreen(st, d?.events.orEmpty(), now, onBack = { page = null }, onChanged = { refreshWidget() })
                page == "about" -> AboutScreen(d, onBack = { page = null })
                d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
                tab == Tab.HOME -> HomeScreen(st, d.events, now, scores.matches,
                    onPin = { h ->
                        val newPin = if (st.pinned == h.token) null else h.token
                        Repo.setPinned(ctx, newPin); st.pinned = newPin; refreshWidget()
                        Toast.makeText(ctx, if (newPin == null) "Unpinned" else "${tokenLabel(newPin)} stays on top", Toast.LENGTH_SHORT).show()
                    },
                    onOpen = { detail = it })
                tab == Tab.SCORES -> ScoresScreen(st, d, now, scores, onOpen = { detail = it })
                tab == Tab.CALENDAR -> CalendarScreen(st, d.events, now, scores.matches, onOpen = { detail = it })
                tab == Tab.MORE -> MoreScreen(onFollow = { page = "follow" }, onAbout = { page = "about" })
            }
        }
    }
    detail?.let { e -> ModalBottomSheet(onDismissRequest = { detail = null }, containerColor = C.tile, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DetailSheet(e, now, e.fdid?.let { scores.matches[it] }, scores.fetchedAt, data?.attribution.orEmpty())
    } }
}

@Composable
fun TopBar() {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
    }
}

@Composable
fun HomeScreen(st: AppState, events: List<Event>, now: Instant, scores: Map<Long, Score>, onPin: (Hero) -> Unit, onOpen: (Event) -> Unit) {
    var filter by remember { mutableStateOf(Filter.FOLLOWING) }
    val hs = remember(events, st.follows, st.pinned, filter, st.favs, now) { heroes(events, st.follows, st.pinned, filter, st.favs, now) }
    var shown by remember { mutableStateOf<String?>(null) }
    val list = remember(events, st.follows, filter, now, shown) { listEvents(events, st.follows, filter, now, shown) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar() }
        item { FilterPills(filter) { filter = it } }
        item { Spacer(Modifier.height(14.dp)); HeroPager(hs, now, st.pinned, onPin, onOpen) { shown = it.event.id } }
        if (list.isEmpty()) item { Text("Nothing else in the next two weeks", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(24.dp)) }
        dayGroups(list, now, scores, onOpen)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun CalendarScreen(st: AppState, events: List<Event>, now: Instant, scores: Map<Long, Score>, onOpen: (Event) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val today = localDate(now)
    var day by remember { mutableStateOf(today) }
    val pool = remember(events, st.follows, all, now) { events.filter { !it.over(now) && (all || followed(it, st.follows)) } }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp, bottom = 10.dp)) {
                Text("Calendar", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Text("Your time · ${zone.id}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        item { FilterPills(if (all) Filter.ALL else Filter.FOLLOWING) { all = it == Filter.ALL } }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0..20).map { today.plusDays(it.toLong()) }) { d ->
                    val has = pool.filter { localDate(it.start) == d }
                    val on = d == day
                    Column(Modifier.width(50.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.lime else Color.Transparent).clickable { day = d }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = if (on) C.onLime else C.fg2, fontFamily = Inter, fontSize = 12.sp)
                        Text("${d.dayOfMonth}", style = Digits, fontSize = 24.sp, color = if (on) C.onLime else if (has.isEmpty()) C.fg3 else C.fg)
                        Row(Modifier.height(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            has.map { it.code }.distinct().take(3).forEach { Dot(if (on) C.onLime else seriesColor(it), 5.dp); Spacer(Modifier.width(2.dp)) }
                        }
                    }
                }
            }
        }
        dayGroups(pool.filter { !localDate(it.start).isBefore(day) && localDate(it.start).isBefore(day.plusDays(14)) }, now, scores, onOpen)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun ScoresScreen(st: AppState, d: Data, now: Instant, scores: Scores, onOpen: (Event) -> Unit) {
    val t = localDate(now)
    val todays = d.events.filter { it.sport == Sport.FOOTBALL && (localDate(it.start) == t || (it.started(now) && !it.over(now))) }
    val mine = todays.filter { followed(it, st.follows) }
    val nextMine = d.events.filter { it.sport == Sport.FOOTBALL && followed(it, st.follows) && !it.started(now) }.take(3)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 8.dp, bottom = 6.dp)) {
                Text("Scores", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                Text("Football today, yours first", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        item { SectionLabel("Your teams") }
        val yours = (mine + nextMine).distinct()
        if (yours.isEmpty()) item { Text("No football you follow today", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        yours.forEach { e -> item(key = "m" + e.id) { EventRow(e, now, e.fdid?.let { scores.matches[it] }, onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) } }
        todays.filter { it !in mine }.groupBy { it.code }.forEach { (code, list) ->
            item(key = "s$code") { SectionLabel(series(code)?.name ?: code) }
            list.forEach { e -> item(key = "o" + e.id) { EventRow(e, now, e.fdid?.let { scores.matches[it] }, onClick = { onOpen(e) }); Spacer(Modifier.height(4.dp)) } }
        }
        item {
            Column(Modifier.padding(20.dp)) {
                val at = scores.fetchedAt?.let { "checked ${hm(it)}" } ?: "not checked yet"
                Text("Scores can lag behind the match · $at", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                Text(d.attribution, color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) = Text(text, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))

@Composable
fun MoreScreen(onFollow: () -> Unit, onAbout: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
        Text("More", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
        MoreItem(Icons.Filled.Star, "Following", "Series, teams and favourites", onFollow)
        MoreItem(Icons.Filled.Info, "About", "Sources, credits and honesty rules", onAbout)
    }
}

@Composable
fun MoreItem(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.lime); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(sub, color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.fg2)
    }
}

@Composable
fun AboutScreen(d: Data?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
        Text("About SportClock", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        listOf(
            "Every time is shown in your phone's own time zone.",
            "\"~\" means an approximate time: broadcasters move these. TBC means no time is published yet, and the app never guesses one.",
            "LIVE is only shown for confirmed start times; an approximate one shows ON NOW.",
            "Football scores are free and can lag behind the match. ${d?.attribution ?: ""}",
            "F1 via Jolpica; fight cards and other series curated by hand.",
            "Photos are from news coverage and Wikimedia Commons, for personal use in this app only.",
            "Fixtures checked: ${d?.generated?.take(16)?.replace('T', ' ') ?: "—"} UTC",
        ).forEach { Text("• $it", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(vertical = 5.dp)) }
    }
}

@Composable
fun DetailSheet(e: Event, now: Instant, score: Score?, fetchedAt: Instant?, attribution: String) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Dot(seriesColor(e.code), 12.dp); Spacer(Modifier.width(10.dp))
            Text(series(e.code)?.name ?: e.comp, color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        Text(e.line1(), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 6.dp))
        Text(e.line2(), color = C.fg2, fontFamily = Inter, fontSize = 15.sp)
        val showScore = score?.home != null && score.away != null
        Text(if (showScore) "${score!!.home}–${score.away}" else timeText(e), style = Digits, color = C.lime, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp,
            modifier = Modifier.padding(top = 8.dp))
        Text(if (e.isTbc) (e.window?.let { "Time not published yet · expected between ${hm(it.first)} and ${hm(it.second)}" } ?: "Time not published yet")
             else "${dayWord(e.start, now)} · ${daySub(localDate(e.start))} · your time", color = C.fg, fontFamily = Inter, fontSize = 15.sp)
        if (e.isApprox) Text("Approximate: broadcasters may move this.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (e.venue.isNotEmpty()) Text(e.venue, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (e.sport == Sport.FOOTBALL) {
            Spacer(Modifier.height(14.dp))
            Text("Scores can lag behind the match${fetchedAt?.let { " · checked ${hm(it)}" } ?: ""}", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            Text(attribution, color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
        }
    }
}
