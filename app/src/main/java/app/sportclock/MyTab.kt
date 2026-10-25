package app.sportclock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

/*
 * Your tab: the fourth tab shows whatever you care about most. F1 (the F1 hub), the NFL, a whole football
 * league (Premier League, La Liga, Serie A, Bundesliga, Ligue 1...) or a single team. "Change" in its header picks.
 */

internal val LEAGUE_SHORT = mapOf("ENG" to "PL", "ESP" to "La Liga", "ITA" to "Serie A", "GER" to "Bundesliga", "FRA" to "Ligue 1",
    "UCL" to "UCL", "EFL" to "EFL", "NED" to "Eredivisie", "POR" to "Liga PT")

fun myTabLabel(v: String) = when {
    v == "F1" -> "F1"; v == "NFL" -> "NFL"
    v.startsWith("c:") -> LEAGUE_SHORT[v.substring(2)] ?: series(v.substring(2))?.name ?: v.substring(2)
    v.startsWith("t:") -> short(v.substring(2)).let { if (it.length > 10) it.take(9) + "…" else it }
    else -> "F1"
}

fun myTabIcon(v: String): ImageVector = when {
    v == "NFL" || (v.startsWith("t:") && v.substring(2) in NFL_TEAMS) -> Icons.Filled.SportsFootball
    v.startsWith("c:") -> Icons.Filled.EmojiEvents
    v.startsWith("t:") -> Icons.Filled.Shield
    else -> Icons.Filled.SportsMotorsports
}

@Composable
fun MyTab(choice: String, st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>,
          onOpen: (Event) -> Unit, onPage: (String) -> Unit, onChoose: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val change = { picking = true }
    val team = { t: String -> onPage("team:$t") }
    when {
        choice == "NFL" -> NflHub(events, now, scores, onOpen, team, change)
        choice.startsWith("c:") -> LeagueHub(choice.substring(2), st, events, now, scores, onOpen, team, change)
        choice.startsWith("t:") -> TeamScreen(choice.substring(2), events, now, scores, onBack = null, onOpen = onOpen,
            header = { HubHeader("Your team", "Tap Change to show something else here", null, change) })
        else -> F1Hub(events, now, onOpen, onPage, onChange = change)
    }
    if (picking) MyTabPicker(choice, st, events, onPick = { onChoose(it); picking = false }, onDismiss = { picking = false })
}

/** Title, a line under it and the Change button that every version of your tab shares. */
@Composable
fun HubHeader(title: String, sub: String?, logo: String?, onChange: () -> Unit) =
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) { LogoChip(logo, 30.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        OutlinedButton(onClick = onChange, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Change", color = C.lime, fontFamily = Inter) }
    }

/* ---------- a whole league ---------- */

@Composable
private fun LeagueHub(code: String, st: AppState, events: List<Event>, now: Instant, scores: Map<String, Score>,
                      onOpen: (Event) -> Unit, onTeam: (String) -> Unit, onChange: () -> Unit) {
    var seg by rememberSaveable(code) { mutableIntStateOf(0) }
    val games = remember(events, code, now.epochSecond / 600) {
        events.filter { it.code == code && it.start.isAfter(now.minusSeconds(3 * 86400)) && it.start.isBefore(now.plusSeconds(10 * 86400)) }
    }
    // the table names clubs ESPN's way ("AFC Bournemouth"); open the page under our own name when we know it
    val ours = remember(events, code) { events.filter { it.code == code }.flatMap { it.teams }.distinct() }
    Column(Modifier.fillMaxSize()) {
        HubHeader(series(code)?.name ?: code, "${games.count { !it.started(now) }} matches in the next 10 days", code, onChange)
        Segments(listOf("Matches", "Table"), seg) { seg = it }
        Spacer(Modifier.height(8.dp))
        if (seg == 0) LazyColumn(Modifier.fillMaxSize()) {
            if (games.isEmpty()) item { Text("No matches in the last three days or the next ten.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
            dayGroups(games, now, scores, onOpen)
            item { Spacer(Modifier.height(24.dp)) }
        } else TablesView(st, only = code, onTeam = { espn -> onTeam(ours.firstOrNull { sameTeam(it, espn) } ?: espn) })
    }
}

/* ---------- the NFL ---------- */

@Composable
private fun NflHub(events: List<Event>, now: Instant, scores: Map<String, Score>, onOpen: (Event) -> Unit, onTeam: (String) -> Unit, onChange: () -> Unit) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val games = remember(events, now.epochSecond / 600) { events.filter { it.code == "NFL" && it.start.isAfter(now.minusSeconds(7 * 86400)) } }
    Column(Modifier.fillMaxSize()) {
        HubHeader("NFL", games.firstOrNull { !it.started(now) }?.session?.let { "Next: $it" } ?: "Games and standings", "NFL", onChange)
        Segments(listOf("Games", "Standings"), seg) { seg = it }
        Spacer(Modifier.height(8.dp))
        if (seg == 0) LazyColumn(Modifier.fillMaxSize()) {
            if (games.isEmpty()) item { Text("Loading the NFL schedule… if nothing appears, check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
            dayGroups(games, now, scores, onOpen)
            item { Text("Schedule and scores from ESPN.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
        } else NflStandings(onTeam)
    }
}

@Composable
private fun NflStandings(onTeam: (String) -> Unit) {
    var data by remember { mutableStateOf<List<Pair<String, List<NflRow>>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Nfl.standings() }.onSuccess { data = it }.onFailure { failed = true } }
    val d = data
    LazyColumn(Modifier.fillMaxSize()) {
        when {
            failed && d == null -> item { Text("Couldn't load the standings. Check your connection.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
            d == null -> items(4) { Skeleton(120.dp, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            else -> d.forEach { (division, rows) ->
                item(key = division) {
                    Text(division, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                }
                items(rows, key = { division + it.team }) { r ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.tile)
                        .clickable { onTeam(r.team) }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(short(r.team), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(listOf(r.wins, r.losses).joinToString("–") + if (r.ties.isNotBlank() && r.ties != "0") "–${r.ties}" else "",
                            Modifier.width(64.dp), style = Digits, fontSize = 17.sp, color = C.fg)
                        Text(r.pct, Modifier.width(52.dp), color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                        Text(r.streak, Modifier.width(36.dp), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                    }
                }
            }
        }
        item { Text("Standings from ESPN: wins–losses, win %, streak. Tap a team for its page.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
    }
}

/* ---------- choosing what your tab shows ---------- */

/**
 * The choices: search first (so the keyboard never hides the matches), then F1, the NFL, the leagues and the teams
 * you follow. Typing two letters shows only the matching teams and leagues.
 */
@Composable
fun MyTabChoices(current: String?, follows: Set<String>, events: List<Event>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val teams = remember(events) { (events.filter { it.sport == Sport.FOOTBALL }.flatMap { it.teams } + NFL_TEAMS.keys).distinct().sorted() }
    val leagues = listOf("ENG", "ESP", "ITA", "GER", "FRA", "UCL", "EFL", "NED", "POR")
    LazyColumn(modifier.imePadding()) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Find a team or league (e.g. United, Bundesliga)", fontFamily = Inter) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth())
        }
        if (query.trim().length >= 2) {
            val q = query.trim()
            val ls = leagues.filter { (series(it)?.name ?: it).contains(q, ignoreCase = true) || (LEAGUE_SHORT[it] ?: "").contains(q, ignoreCase = true) }
            val ts = teams.filter { it.contains(q, ignoreCase = true) || short(it).contains(q, ignoreCase = true) }.take(12)
            items(ls, key = { "ql$it" }) { c -> PickRow(series(c)?.name ?: c, "A whole league", c, current == "c:$c") { onPick("c:$c") } }
            items(ts, key = { "qt$it" }) { t -> PickRow(t, if (t in NFL_TEAMS) "NFL team" else "Football team", null, current == "t:$t") { onPick("t:$t") } }
            if (ls.isEmpty() && ts.isEmpty()) item { Text("Nothing called \"$q\". Try part of the name, e.g. \"United\".", color = C.fg2, fontFamily = Inter,
                modifier = Modifier.padding(20.dp)) }
        } else {
            item { PickRow("Formula 1", "Weekend, live timing, standings, season", "F1", current == "F1") { onPick("F1") } }
            item { PickRow("NFL", "Games, scores and standings", "NFL", current == "NFL") { onPick("NFL") } }
            item { SectionLabel("A whole league") }
            items(leagues, key = { "l$it" }) { c -> PickRow(series(c)?.name ?: c, "Matches and the table", c, current == "c:$c") { onPick("c:$c") } }
            val mine = follows.filter { it.startsWith("t:") }.sorted()
            if (mine.isNotEmpty()) {
                item { SectionLabel("Teams you follow") }
                items(mine, key = { "f$it" }) { t -> PickRow(t.substring(2), "One team", null, current == t) { onPick(t) } }
            }
            item { Text("Any other team: type its name in the search box above.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyTabPicker(current: String, st: AppState, events: List<Event>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.tile, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Your tab shows…", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        MyTabChoices(current, st.follows, events, onPick, Modifier.fillMaxWidth().heightIn(max = 680.dp))
    }
}

/**
 * The first screen on a new phone: what do you follow most? The pick becomes your tab and what you follow, so a new
 * person starts with their own team or sport rather than F1, Man Utd and UFC.
 */
@Composable
fun WelcomeScreen(events: List<Event>, onPick: (String) -> Unit) =
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(34.dp); Spacer(Modifier.width(12.dp))
            Text("SportClock", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
        }
        Text("What do you follow most?", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp))
        Text("It gets its own tab, and its fixtures show on Home. You can add more later in More → Following, and change the tab any time.",
            color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        MyTabChoices(null, emptySet(), events, onPick, Modifier.fillMaxWidth().weight(1f))
    }

@Composable
private fun PickRow(title: String, sub: String, logo: String?, on: Boolean, onClick: () -> Unit) =
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (on) C.lime.copy(alpha = .16f) else C.tile2).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) { LogoChip(logo, 24.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (on) C.lime else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
        }
        if (on) Text("Showing", color = C.lime, fontFamily = Inter, fontSize = 12.sp)
    }
