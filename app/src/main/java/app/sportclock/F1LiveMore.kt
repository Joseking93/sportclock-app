package app.sportclock

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The rest of F1 Live, all from F1's own feed: team radio clips, every driver's tyre strategy, the lap chart,
 * speed traps and fastest laps, and the purple/green sector colours the timing tower uses.
 */

val LIVE_TABS = listOf("Timing", "Map", "Radio", "Strategy", "Laps", "Speeds", "Control", "Pits", "Upgrades")

/** Scrollable tabs for when there are too many for a segmented control. */
@Composable
fun ChipTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (i == selected) C.lime else C.tile).clickable { onSelect(i) }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(o, color = if (i == selected) C.onLime else C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }

private fun team(s: F1Snapshot, n: String) = s.drivers[n]?.colour?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: C.fg3

/** F1's sector colours: purple for the session's best, green for the driver's own, yellow otherwise. */
private val PURPLE = Color(0xFFA855F7); private val GREENS = Color(0xFF22C55E); private val YELLOWS = Color(0xFFEAB308)
fun sectorColor(sc: F1Sector) = when { sc.value.isBlank() -> null; sc.overall -> PURPLE; sc.personal -> GREENS; else -> YELLOWS }
/** Mini-sector status codes from the feed: 2051 purple, 2049 green, 2048 yellow, 2064 pit lane. */
private fun segmentColor(code: Int) = when (code) { 2051 -> PURPLE; 2049 -> GREENS; 2048 -> YELLOWS; 2064 -> Color(0xFF3E7BFA); else -> null }

/** Three little sector bars under a driver's name in the tower, with the mini-sectors as ticks. */
@Composable
fun SectorBars(sectors: List<F1Sector>) = Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
    sectors.take(3).forEach { sc ->
        Row(Modifier.width(30.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(sectorColor(sc)?.copy(alpha = .9f) ?: C.tile2)) {
            if (sc.value.isBlank() && sc.segments.isNotEmpty()) sc.segments.forEach { code ->   // sector still running: show its mini-sectors
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 0.5.dp).background(segmentColor(code) ?: C.tile2))
            }
        }
    }
}

/* ---------- team radio ---------- */

@Composable
fun RadioView(s: F1Snapshot) {
    var playing by remember { mutableStateOf<String?>(null) }
    var loadingUrl by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    fun play(url: String) {
        runCatching {
            player.reset()
            if (playing == url) { playing = null; return }
            player.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(url)
            player.setOnPreparedListener { loadingUrl = null; playing = url; it.start() }
            player.setOnCompletionListener { playing = null }
            player.setOnErrorListener { _, _, _ -> playing = null; loadingUrl = null; true }
            loadingUrl = url
            player.prepareAsync()
        }.onFailure { Diag.log("radio: $it"); playing = null; loadingUrl = null }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (s.radio.isEmpty()) item { Text("No team radio yet this session.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        items(s.radio, key = { it.url }) { r ->
            val on = playing == r.url
            Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) C.lime.copy(alpha = .14f) else C.tile)
                .clickable { play(r.url) }.padding(end = 12.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(team(s, r.number)))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (on) C.lime else C.tile2), contentAlignment = Alignment.Center) {
                    if (loadingUrl == r.url) CircularProgressIndicator(color = C.lime, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Icon(if (on) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (on) "Stop" else "Play", tint = if (on) C.onLime else C.fg)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.drivers[r.number]?.name?.ifBlank { null } ?: s.tla(r.number), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(s.drivers[r.number]?.team.orEmpty(), color = C.fg3, fontFamily = Inter, fontSize = 12.sp)
                }
                Text(r.at?.let { hm(it) }.orEmpty(), color = C.fg2, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        item { Text("Team radio from Formula 1's live timing, newest first. Tap to play.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
    }
}

/* ---------- strategy ---------- */

private fun tyreCol(t: String) = when (t.uppercase()) { "SOFT" -> Color(0xFFE53935); "MEDIUM" -> Color(0xFFFDD835); "HARD" -> Color(0xFFEEEEEE)
    "INTERMEDIATE" -> Color(0xFF43A047); "WET" -> Color(0xFF1E88E5); else -> C.fg3 }

@Composable
fun StrategyView(s: F1Snapshot) {
    val order = s.lines.map { it.number }.filter { (s.stints[it]?.isNotEmpty()) == true }
    val most = maxOf(s.totalLaps ?: 0, order.maxOfOrNull { n -> s.stints[n]!!.sumOf { it.laps } } ?: 0, 1)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET").forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(tyreCol(t))); Spacer(Modifier.width(4.dp))
                        Text(t.take(1) + t.drop(1).lowercase().take(if (t == "INTERMEDIATE") 4 else 9), color = C.fg2, fontFamily = Inter, fontSize = 11.sp)
                    }
                }
            }
        }
        if (order.isEmpty()) item { Text("No tyre data yet this session.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)) }
        items(order, key = { "st$it" }) { n ->
            val stints = s.stints[n]!!
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.tla(n), Modifier.width(44.dp), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(C.tile)) {
                    val used = stints.sumOf { it.laps }.coerceAtMost(most)
                    stints.filter { it.laps > 0 }.forEach { st ->
                        Box(Modifier.weight(st.laps.toFloat()).fillMaxHeight().padding(end = 1.dp).background(tyreCol(st.compound).copy(alpha = if (st.new) 1f else .6f)),
                            contentAlignment = Alignment.Center) {
                            if (st.laps >= 6) Text("${st.compound.take(1)} ${st.laps}", color = Color.Black, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                    if (most - used > 0) Spacer(Modifier.weight((most - used).toFloat()))
                }
            }
        }
        item { Text("Each bar is a set of tyres: compound and laps. Paler means a used set.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
    }
}

/* ---------- lap chart ---------- */

@Composable
fun LapChartView(s: F1Snapshot) {
    var all by remember { mutableStateOf(false) }
    val cars = s.lines.map { it.number }.filter { (s.laps[it]?.size ?: 0) > 1 }.let { if (all) it else it.take(10) }
    val laps = cars.maxOfOrNull { s.laps[it]!!.size } ?: 0
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Top 10", true to "Everyone").forEach { (v, l) ->
                FilterChip(all == v, { all = v }, label = { Text(l, fontFamily = Inter) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
            }
        }
        if (laps < 2) { Text("The lap chart builds as the race goes on.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(20.dp)); return@Column }
        val maxPos = cars.flatMap { s.laps[it]!! }.maxOrNull()?.coerceAtLeast(cars.size) ?: 20
        Canvas(Modifier.padding(16.dp).fillMaxWidth().height(380.dp).clip(RoundedCornerShape(14.dp)).background(C.tile)) {
            val left = 26.dp.toPx(); val right = 40.dp.toPx(); val top = 10.dp.toPx(); val bottom = 18.dp.toPx()
            val w = size.width - left - right; val h = size.height - top - bottom
            fun x(lap: Int) = left + w * (lap - 1) / (laps - 1).coerceAtLeast(1)
            fun y(pos: Int) = top + h * (pos - 1) / (maxPos - 1).coerceAtLeast(1)
            val label = android.graphics.Paint().apply { color = C.fg3.toArgb(); textSize = 9.sp.toPx(); isAntiAlias = true }
            for (p in 1..maxPos) {
                drawLine(C.line, Offset(left, y(p)), Offset(left + w, y(p)), 1f)
                if (p == 1 || p % 5 == 0) drawContext.canvas.nativeCanvas.drawText("P$p", 2.dp.toPx(), y(p) + 3.dp.toPx(), label)
            }
            listOf(1, laps / 2, laps).distinct().forEach { l -> drawContext.canvas.nativeCanvas.drawText("L$l", x(l) - 6.dp.toPx(), size.height - 4.dp.toPx(), label) }
            val name = android.graphics.Paint().apply { textSize = 9.sp.toPx(); isAntiAlias = true; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            cars.reversed().forEach { n ->                                    // leaders drawn last, on top
                val ps = s.laps[n]!!
                val col = team(s, n)
                val path = Path().apply { ps.forEachIndexed { i, p -> if (i == 0) moveTo(x(1), y(p)) else lineTo(x(i + 1), y(p)) } }
                drawPath(path, col, style = Stroke(2.2.dp.toPx()))
                name.color = col.toArgb()
                drawContext.canvas.nativeCanvas.drawText(s.tla(n), x(ps.size) + 4.dp.toPx(), y(ps.last()) + 3.dp.toPx(), name)
            }
        }
        Text("Position at the end of every lap, in team colours. A drop and climb back is usually a pit stop.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp))
    }
}

/* ---------- speeds and fastest laps ---------- */

@Composable
fun SpeedsView(s: F1Snapshot) = LazyColumn(Modifier.fillMaxSize()) {
    item { Text("Fastest laps", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp)) }
    if (s.bestLaps.isEmpty()) item { Text("No laps yet.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(horizontal = 20.dp)) }
    items(s.bestLaps.take(10), key = { "bl" + it.number }) { b -> BestRow(s, b, b.value) }
    item { Text("Speed trap", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)) }
    if (s.topSpeeds.isEmpty()) item { Text("No speeds yet.", color = C.fg2, fontFamily = Inter, modifier = Modifier.padding(horizontal = 20.dp)) }
    items(s.topSpeeds.take(10), key = { "ts" + it.number }) { b -> BestRow(s, b, "${b.value} km/h") }
    item { Text("Each driver's best of the session, from Formula 1's timing.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
}

@Composable
private fun BestRow(s: F1Snapshot, b: F1Best, value: String) =
    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.tile).height(42.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(team(s, b.number)))
        Text("${b.pos}", Modifier.width(34.dp).padding(start = 10.dp), style = Digits, fontSize = 17.sp, color = if (b.pos == 1) PURPLE else C.fg2)
        Text(s.tla(b.number), Modifier.weight(1f), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(value, Modifier.padding(end = 12.dp), color = if (b.pos == 1) PURPLE else C.fg, fontFamily = Inter, fontSize = 14.sp)
    }
