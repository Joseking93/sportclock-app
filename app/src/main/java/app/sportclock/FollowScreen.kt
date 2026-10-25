package app.sportclock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FollowScreen(st: AppState, events: List<Event>, now: Instant, onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf<Kind?>(null) }
    var query by remember { mutableStateOf("") }
    fun set(token: String, on: Boolean) { Repo.setFollow(ctx, token, on); st.follows = Repo.follows(ctx); onChanged() }
    fun nextFor(token: String) = events.firstOrNull { tokenMatches(token, it) && !it.over(now) }
    val allTeams = remember(events) { (events.flatMap { it.teams } + NFL_TEAMS.keys).distinct().sorted() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.fg) }
                Column {
                    Text("Following", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text("${st.follows.size} followed", color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.lime.copy(alpha = .12f))
                .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = C.lime); Spacer(Modifier.width(12.dp))
                Text("Everything switched on here gets its own NEXT UP card on Home, soonest first, and fills your Following list and the widget. " +
                    "Switch a series or team off to take its card away.", color = C.fg, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        item { SectionLabel("★ Favourites") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Kind.DRIVER to "Driver", Kind.FIGHTER to "Fighter", Kind.PLAYER to "Player").forEach { (k, role) ->
                    val p = st.favs[k]
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(C.tile).clickable { picking = k }.padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (p != null) Image(painterResource(p.avatar), null, contentScale = ContentScale.Crop, alignment = BiasAlignment(p.fx * 2 - 1, p.fy * 2 - 1),
                            modifier = Modifier.size(60.dp).clip(CircleShape).border(2.dp, C.lime, CircleShape))
                        else Box(Modifier.size(60.dp).clip(CircleShape).background(C.tile2))
                        Text(p?.name ?: "None", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(if (p == null) role else "$role · ${p.team}", color = C.fg2, fontFamily = Inter, fontSize = 12.sp, maxLines = 1)
                        Text("Change", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        item { Text("Their photo leads Next up when their sport is on next. A player shows only when his club is playing; otherwise, or with None, the card shows both clubs' badges.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }

        listOf("Motorsport" to listOf("F1", "MGP", "NAS", "INDY"), "Fight" to listOf("UFC", "BOX")).forEach { (label, codes) ->
            item { SectionLabel(label) }
            codes.forEach { code ->
                item {
                    val token = "c:$code"; val on = token in st.follows
                    ToggleRow(seriesColor(code), series(code)?.name ?: code, nextFor(token)?.let { "Next: ${sessName(it.session)} · ${if (it.isTbc) "date set, time TBC" else dayWord(it.start, now) + " " + hm(it.start)}" } ?: "No dates yet", on) { set(token, it) }
                }
            }
        }

        item { SectionLabel("NFL") }
        item { ToggleRow(seriesColor("NFL"), "NFL", "Every game; or add single teams below (e.g. Chiefs)", "c:NFL" in st.follows) { set("c:NFL", it) } }
        item { SectionLabel("Teams") }
        st.follows.filter { it.startsWith("t:") }.sorted().forEach { token ->
            item { ToggleRow(if (token.substring(2) in NFL_TEAMS) seriesColor("NFL") else seriesColor("ENG"), token.substring(2), nextFor(token)?.let { "Next: ${it.line1()} · ${dayWord(it.start, now)} ${hm(it.start)}" } ?: "No dates yet", true) { set(token, it) } }
        }
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Add a team", fontFamily = Inter) }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth())
        }
        if (query.length >= 2) {
            allTeams.filter { it.contains(query, ignoreCase = true) && "t:$it" !in st.follows }.take(8).forEach { team ->
                item { ToggleRow(C.fg3, team, "Tap to follow", false) { set("t:$team", it); query = "" } }
            }
        }

        item { SectionLabel("Football competitions") }
        item {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SERIES.filter { it.sport == Sport.FOOTBALL }.forEach { s ->
                    val on = "c:${s.code}" in st.follows
                    FilterChip(on, { set("c:${s.code}", !on) }, label = { Text(s.name, fontFamily = Inter) },
                        leadingIcon = { Dot(s.color, 8.dp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }

    picking?.let { kind ->
        ModalBottomSheet(onDismissRequest = { picking = null }, containerColor = C.tile) {
            Text("Choose your ${kind.name.lowercase()}", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.heightIn(max = 560.dp), contentPadding = PaddingValues(12.dp)) {
                item {
                    Column(Modifier.padding(6.dp).clip(RoundedCornerShape(14.dp)).clickable {
                        Repo.clearFav(ctx, kind); st.favs = Kind.entries.associateWith { Repo.fav(ctx, it) }; onChanged(); picking = null
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(C.tile2).border(2.dp, if (st.favs[kind] == null) C.lime else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center) { Text("—", color = C.fg2, fontFamily = Inter, fontSize = 22.sp) }
                        Text(if (kind == Kind.PLAYER) "None (badges)" else "None", color = C.fg, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center,
                            maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                items(PEOPLE.filter { it.kind == kind }) { p ->
                    Column(Modifier.padding(6.dp).clip(RoundedCornerShape(14.dp)).clickable {
                        Repo.setFav(ctx, p); st.favs = Kind.entries.associateWith { Repo.fav(ctx, it) }; onChanged(); picking = null
                    }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(p.avatar), null, contentScale = ContentScale.Crop, alignment = BiasAlignment(p.fx * 2 - 1, p.fy * 2 - 1),
                            modifier = Modifier.size(72.dp).clip(CircleShape).border(2.dp, if (st.favs[kind]?.id == p.id) C.lime else Color.Transparent, CircleShape))
                        Text(p.name, color = C.fg, fontFamily = Inter, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(dot: Color, title: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 1.dp).fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(6.dp)).background(C.tile)
        .clickable { onToggle(!on) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(dot, 10.dp); Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = C.fg2, fontFamily = Inter, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(on, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = C.lime, checkedThumbColor = C.onLime))
    }
}
