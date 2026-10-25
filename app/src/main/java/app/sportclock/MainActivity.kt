package app.sportclock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
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
        setContent {
            // text size: the user's choice in Settings, or the phone's own when "Phone" is chosen
            var textScale by remember { mutableFloatStateOf(Settings.textScale(this)) }
            val dens = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(dens.density, if (textScale > 0f) textScale else dens.fontScale)
            ) { SportClockTheme { App(onTextScale = { textScale = it }) } }
        }
    }
}

enum class Tab(val label: String, val icon: ImageVector) { HOME("Home", Icons.Filled.Home), SCORES("Scores", Icons.Filled.EmojiEvents),
    CALENDAR("Calendar", Icons.Filled.CalendarMonth), F1("F1", Icons.Filled.SportsMotorsports), MORE("More", Icons.Filled.Menu) }

/** Everything the screens read; changes are written through Repo and mirrored here. */
class AppState(ctx: android.content.Context) {
    var follows by mutableStateOf(Repo.follows(ctx))
    var pinned by mutableStateOf(Repo.pinned(ctx))
    var favs by mutableStateOf(Kind.entries.associateWith { Repo.fav(ctx, it) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(onTextScale: (Float) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = remember { AppState(ctx) }
    remember { AlertChoices.load(ctx); Spoilers.load(ctx) }
    val data by Repo.data.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var page by remember { mutableStateOf<String?>(null) }          // "follow" | "about" | "settings" | "update" | "report" pushed over More
    var detail by remember { mutableStateOf<Event?>(null) }
    var scores by remember { mutableStateOf(Scores(emptyMap(), null, null)) }

    LaunchedEffect(Unit) { Repo.load(ctx)?.let { Alerts.reschedule(ctx, it.events) }; SportClockWidget().updateAll(ctx) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    // Last time's crash (if any) goes out as a report, then a quiet update check, at most twice a day.
    var updateWaiting by remember { mutableStateOf(Updater.waiting(ctx)) }
    LaunchedEffect(Unit) {
        val (_, crashes) = Diag.sendPending(ctx)
        if (crashes > 0) Toast.makeText(ctx, "SportClock crashed last time. A report was sent.", Toast.LENGTH_LONG).show()
        Updater.checkNow(ctx); updateWaiting = Updater.waiting(ctx)
    }
    LaunchedEffect(st.follows) { AlertChoices.follows = st.follows }
    LaunchedEffect(tab, page) { Diag.log("screen ${page ?: tab.name.lowercase()}") }
    LaunchedEffect(detail) { detail?.let { Diag.log("open ${it.id} ${it.title}") } }
    // Scores from ESPN for today's and yesterday's football: every 2 minutes while a match is on, otherwise every 15.
    LaunchedEffect(data) {
        while (true) {
            val t = Instant.now()
            val pool = data?.events.orEmpty().filter { it.sport == Sport.FOOTBALL && it.started(t) && it.start.isAfter(t.minusSeconds(30 * 3600)) }
            val live = pool.any { it.inProgress(t) }
            if (pool.isNotEmpty()) scores = Repo.scores(if (live) pool.filter { it.inProgress(t) || scores.matches[it.id] == null } else pool)
                .let { fresh -> if (live) Scores(scores.matches + fresh.matches, fresh.fetchedAt, fresh.error) else fresh }
            delay(if (live) 120_000 else 900_000)
        }
    }
    fun refreshWidget() = scope.launch { SportClockWidget().updateAll(ctx) }
    fun rearm() { data?.let { Alerts.reschedule(ctx, it.events) }; refreshWidget() }

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
                page == "follow" -> FollowScreen(st, d?.events.orEmpty(), now, onBack = { page = null }, onChanged = { rearm() })
                page == "settings" -> SettingsScreen(onBack = { page = null }, onChanged = { rearm() }, onTextScale = onTextScale)
                page == "about" -> AboutScreen(d, onBack = { page = null })
                page == "news" -> Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { page = null }, modifier = Modifier.statusBarsPadding().padding(start = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
                    NewsScreen(st, now)
                }
                page == "update" -> UpdateScreen(onBack = { page = null; updateWaiting = Updater.waiting(ctx) })
                page == "report" -> ReportScreen(onBack = { page = null })
                page == "f1live" || page?.startsWith("upgrades:") == true -> F1Page(page!!, onBack = { page = null })
                page?.startsWith("team:") == true -> TeamScreen(page!!.removePrefix("team:"), d?.events.orEmpty(), now, scores.matches,
                    onBack = { page = null }, onOpen = { detail = it })
                d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.lime) }
                tab == Tab.HOME -> HomeScreen(st, d.events, now, scores.matches,
                    onPin = { h ->
                        val newPin = if (st.pinned == h.token) null else h.token
                        Repo.setPinned(ctx, newPin); st.pinned = newPin; refreshWidget()
                        Toast.makeText(ctx, if (newPin == null) "Unpinned" else "${tokenLabel(newPin)} stays on top", Toast.LENGTH_SHORT).show()
                    },
                    onOpen = { detail = it }, onTeam = { page = "team:$it" })
                tab == Tab.SCORES -> ScoresHub(st, d, now, scores, onOpen = { detail = it }, onTeam = { page = "team:$it" })
                tab == Tab.CALENDAR -> CalendarScreen(st, d.events, now, scores.matches, onOpen = { detail = it })
                tab == Tab.F1 -> F1Hub(d.events, now, onOpen = { detail = it }, onPage = { page = it })
                tab == Tab.MORE -> MoreScreen(updateWaiting, onFollow = { page = "follow" }, onSettings = { page = "settings" }, onAbout = { page = "about" },
                    onUpdate = { page = "update" }, onReport = { page = "report" }, onNews = { page = "news" })
            }
            val u = updateWaiting
            if (page == null && tab == Tab.HOME && u != null && Updater.dismissed(ctx) != u) {
                Row(Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(50)).background(C.lime)
                    .clickable { page = "update" }.padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("SportClock $u is ready · Update", color = C.onLime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    TextButton(onClick = { Updater.dismiss(ctx, u); updateWaiting = null }) { Text("✕", color = C.onLime, fontFamily = Inter) }
                }
            }
        }
    }
    detail?.let { e -> ModalBottomSheet(onDismissRequest = { detail = null }, containerColor = C.tile, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DetailSheet(e, now, scores.matches[e.id], scores.fetchedAt, onTeam = { detail = null; page = "team:$it" },
            events = data?.events.orEmpty(), onPage = { detail = null; page = it })
    } }
}

@Composable
fun TopBar() {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Wordmark()
    }
}

@Composable
fun HomeScreen(st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>, onPin: (Hero) -> Unit, onOpen: (Event) -> Unit, onTeam: (String) -> Unit = {}) {
    var filter by remember { mutableStateOf(Filter.FOLLOWING) }
    val hs = remember(events, st.follows, st.pinned, filter, st.favs, now) { heroes(events, st.follows, st.pinned, filter, st.favs, now) }
    var shown by remember { mutableStateOf<String?>(null) }
    val list = remember(events, st.follows, filter, now, shown) { listEvents(events, st.follows, filter, now, shown) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopBar() }
        item { FilterPills(filter) { filter = it } }
        item { Spacer(Modifier.height(14.dp)); HeroPager(hs, now, st.pinned, onPin, onOpen) { shown = it.event.id } }
        item { LastResults(st, events, now, scores, onTeam) }
        if (list.isEmpty()) item { Text("Nothing else in the next two weeks", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(24.dp)) }
        dayGroups(list, now, scores, onOpen)
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun CalendarScreen(st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>, onOpen: (Event) -> Unit) {
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
fun SectionLabel(text: String) = Text(text, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp,
    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))

@Composable
fun MoreScreen(updateWaiting: String?, onFollow: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit, onUpdate: () -> Unit, onReport: () -> Unit,
               onNews: () -> Unit = {}) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
        Text("More", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
        MoreItem(Icons.Filled.Newspaper, "News", "BBC Sport headlines for what you follow", onNews)
        MoreItem(Icons.Filled.Star, "Following", "Series, teams and favourites", onFollow)
        MoreItem(Icons.Filled.Settings, "Settings", "Alerts and text size", onSettings)
        MoreItem(Icons.Filled.SystemUpdate, "Updates", updateWaiting?.let { "SportClock $it is ready to install" } ?: "You have ${remember { Diag.version(ctx).first }}", onUpdate)
        MoreItem(Icons.Filled.BugReport, "Report a problem", "Sends it to the app's GitHub", onReport)
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
            "Football scores, lineups, form and tables are fetched by your phone from ESPN; so are F1, IndyCar and NASCAR results and UFC cards. MotoGP results come from MotoGP.com, F1 standings, race times and pit stops from Jolpica (Ergast), news from BBC Sport.",
            "F1 Live reads Formula 1's own live timing feed; track maps come from MultiViewer's circuit data, upgrades from the FIA's Car Presentation Submissions.",
            "Fixtures: ${d?.attribution ?: ""}; F1 calendar via Jolpica; fight cards and other series curated by hand.",
            "Photos are from news coverage and Wikimedia Commons, for personal use in this app only.",
            "Fixtures checked: ${d?.generated?.take(16)?.replace('T', ' ') ?: "—"} UTC",
        ).forEach { Text("• $it", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(vertical = 5.dp)) }
    }
}

@Composable
fun DetailSheet(e: Event, now: Instant, score: Score?, fetchedAt: Instant?, onTeam: (String) -> Unit = {},
                events: List<Event> = emptyList(), onPage: (String) -> Unit = {}) {
    val today = localDate(now)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoChip(e.code, 34.dp); Spacer(Modifier.width(12.dp))
            Text(series(e.code)?.name ?: e.comp, color = C.fg2, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Text(e.line1(), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(top = 10.dp))
        if (e.sport != Sport.FOOTBALL) Text(e.line2(), color = C.fg2, fontFamily = Inter, fontSize = 15.sp)
        val showScore = score?.home != null && score.away != null && !Spoilers.hidden(e)
        Text(if (showScore) "${score!!.home}–${score.away}" else timeText(e), style = Digits, color = C.lime, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp,
            modifier = Modifier.padding(top = 6.dp))
        val d = localDate(e.start)
        val dayLine = if (d == today || d == today.plusDays(1)) "${dayTitle(d, today)} · ${daySub(d)}" else "${dayTitle(d, today)} ${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
        Text(when {
            showScore -> "${score!!.detail.ifEmpty { "Live" }} · kick-off ${hm(e.start)}, $dayLine"
            e.isTbc -> e.window?.let { "Time not published yet · expected between ${hm(it.first)} and ${hm(it.second)}" } ?: "Time not published yet"
            else -> "$dayLine · ${hm(e.start)}, your time"
        }, color = C.fg, fontFamily = Inter, fontSize = 15.sp)
        if (e.isApprox) Text("Approximate: broadcasters may move this.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (e.sport != Sport.FOOTBALL && e.venue.isNotEmpty()) Text(e.venue, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (!e.isTbc && e.start.isAfter(now)) {
            val ctx = LocalContext.current
            val on = AlertChoices.on && AlertChoices.wants(e)
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.tile2).padding(start = 14.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    !AlertChoices.on -> "Alerts are off. Turn them on in More → Settings."
                    on -> "Alert ${Settings.leadMins(ctx)} min before ${if (e.sport == Sport.FOOTBALL) "kick-off" else "the start"}"
                    else -> "No alert for this one · tap the bell"
                }, Modifier.weight(1f), color = if (on) C.fg else C.fg2, fontFamily = Inter, fontSize = 14.sp)
                if (AlertChoices.on) AlertBell(e, 24.dp)
            }
        }
        if (e.sport == Sport.FOOTBALL) {
            if (score?.home != null && Spoilers.hidden(e)) TextButton(onClick = { Spoilers.revealed += e.id }, contentPadding = PaddingValues(0.dp)) {
                Text("Score hidden · tap to show", color = C.lime, fontFamily = Inter)
            }
            TeamLinks(e, onTeam)
            if (!Spoilers.hidden(e) || !e.started(now)) MatchSheetView(e, score, now)
            MatchExtrasView(e)
            Text("Scores can lag behind the match${fetchedAt?.let { " · checked ${hm(it)}" } ?: ""}", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
                modifier = Modifier.padding(top = 12.dp))
        }
        if (e.code == "F1") F1WeekendView(e, events, now, onPage)
        DetailActions(e, now)
    }
}
