package app.sportclock

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant

/* ======================= F1 team radio (OpenF1) ======================= */

/** A finished F1 session's team radio: newest first, tap a clip to hear it. Hidden while spoiler-free hides the session. */
@Composable
fun F1RadioView(e: Event, now: Instant) {
    if (!e.started(now) || Spoilers.hidden(e)) return
    var clips by remember(e.id) { mutableStateOf<List<RadioClip>?>(null) }
    LaunchedEffect(e.id) { clips = runCatching { teamRadio(e.start) }.getOrDefault(emptyList()) }
    val list = clips
    if (list.isNullOrEmpty()) return
    var playing by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }
    fun toggle(c: RadioClip) {
        player[0]?.release(); player[0] = null
        if (playing == c.url) { playing = null; loading = null; return }
        playing = c.url; loading = c.url
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build())
                setDataSource(c.url)
                setOnPreparedListener { it.start(); loading = null }
                setOnCompletionListener { if (playing == c.url) playing = null }
                setOnErrorListener { _, _, _ -> if (playing == c.url) { playing = null; loading = null }; true }
                prepareAsync()
            }.also { player[0] = it }
        }.onFailure { playing = null; loading = null }
    }
    var all by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp)) {
        Text("Team radio", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("${list.size} clips from this session · tap one to play", color = C.fg2, fontFamily = Inter, fontSize = 12.5.sp)
        val shown = list.reversed().let { if (all) it else it.take(12) }
        Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile2)) {
            shown.forEachIndexed { i, c ->
                if (i > 0) Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(1.dp).background(C.line))
                val on = playing == c.url
                Row(Modifier.fillMaxWidth().clickable { toggle(c) }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    val col = c.colour?.let { Color(it) } ?: C.fg3
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) C.lime else col.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                        Text(if (on) (if (loading == c.url) "…" else "■") else "▶", color = if (on) C.onLime else col, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(14.dp).background(col)); Spacer(Modifier.width(6.dp))
                            Text(c.code, style = Digits, color = C.fg, fontSize = 15.sp)
                            Text("  ${c.name}", color = C.fg, fontFamily = Inter, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(c.team, color = C.fg3, fontFamily = Inter, fontSize = 11.5.sp)
                    }
                    val mins = Duration.between(e.start, c.at).toMinutes()
                    Text(if (mins >= 0) "${mins}'" else hm(c.at), style = Digits, color = C.fg2, fontSize = 14.sp)
                }
            }
        }
        if (!all && list.size > 12) Text("Show all ${list.size}", Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.tile2)
            .clickable { all = true }.padding(horizontal = 14.dp, vertical = 8.dp), color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("Team radio from OpenF1 (openf1.org) · minutes from the session start", color = C.fg3, fontFamily = Inter, fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/* ======================= boxing card (Boxerbook) ======================= */

/** The fight behind a boxing row: both records going in, the title at stake, where to watch, and the result once it's in. */
@Composable
fun BoutView(e: Event, now: Instant) {
    var bout by remember(e.id) { mutableStateOf<BoxBout?>(null) }
    LaunchedEffect(e.id) { bout = runCatching { Boxing.bout(e) }.getOrNull() }
    val b = bout ?: return
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile2).padding(14.dp)) {
        if (b.title.isNotBlank()) Text(b.title, color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(b.a to b.aRecord, null, b.b to b.bRecord).forEach { side ->
                if (side == null) Text("v", Modifier.padding(horizontal = 10.dp), color = C.fg3, fontFamily = Inter, fontSize = 14.sp)
                else Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(side.first, color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 2)
                    if (side.second.isNotBlank()) Text(side.second, style = Digits, color = C.fg2, fontSize = 15.sp)
                }
            }
        }
        if (b.aRecord.isNotBlank() || b.bRecord.isNotBlank()) Text("Records going in: wins-losses-draws", Modifier.fillMaxWidth().padding(top = 2.dp),
            color = C.fg3, fontFamily = Inter, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (b.done) {
            if (Spoilers.hidden(e)) Text("Result hidden · tap to show", Modifier.padding(top = 10.dp).clickable { Spoilers.revealed += e.id },
                color = C.lime, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            else Text(b.result(), Modifier.padding(top = 10.dp), color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else {
            val lines = listOfNotNull(
                b.scheduledRounds?.let { "$it rounds" },
                b.venue.takeIf { it.isNotBlank() },
                b.broadcasts.takeIf { it.isNotEmpty() }?.let { "Watch: " + it.distinct().joinToString(", ") },
            )
            lines.forEach { Text(it, color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
        Text("Records and results from Boxerbook (boxerbook.com)", color = C.fg3, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
    }
}
