package app.sportclock

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Which live alerts each football follow sends: United everything, La Liga only full time. Your clubs and countries
 * start with everything; a league's matches start with nothing (ten games a weekend), so choosing anything for a
 * league starts watching all its games. A match you belled with nothing chosen for it gets everything, as before.
 * The Live alerts and Lineup alerts switches in Settings still turn each kind off everywhere.
 */

enum class LiveKind(val label: String) { LINEUPS("Lineups"), GOALS("Goals"), REDS("Red cards"), HT("Half-time"), FT("Full time") }

fun defaultKinds(token: String): Set<LiveKind> = if (token.startsWith("t:") && tokenSport(token) == Sport.FOOTBALL) LiveKind.entries.toSet() else emptySet()

/** The alerts a match sends: everything chosen for any follow it belongs to, or everything when you belled it and chose nothing. */
fun liveKindsFor(e: Event, follows: Set<String>, stored: Map<String, Set<LiveKind>>, belled: Boolean): Set<LiveKind> {
    val k = follows.filter { tokenSport(it) == Sport.FOOTBALL && tokenMatches(it, e) }.flatMap { stored[it] ?: defaultKinds(it) }.toSet()
    return if (belled && k.isEmpty()) LiveKind.entries.toSet() else k
}

/** "t:Manchester United|GOALS,FT" per follow changed from its default; "c:ESP|" means nothing. */
internal fun parseKinds(raw: Set<String>): Map<String, Set<LiveKind>> = raw.mapNotNull { r ->
    val i = r.lastIndexOf('|').takeIf { it > 0 } ?: return@mapNotNull null
    r.substring(0, i) to r.substring(i + 1).split(',').mapNotNull { n -> LiveKind.entries.firstOrNull { it.name == n } }.toSet()
}.toMap()
internal fun kindsToRaw(m: Map<String, Set<LiveKind>>): Set<String> = m.map { (t, ks) -> "$t|" + ks.joinToString(",") { it.name } }.toSet()

object LiveKinds {
    var stored by mutableStateOf(emptyMap<String, Set<LiveKind>>())
    fun load(ctx: Context) { stored = parseKinds(Repo.prefs(ctx).getStringSet("liveKinds", emptySet())!!) }
    fun kinds(token: String) = stored[token] ?: defaultKinds(token)
    fun set(ctx: Context, token: String, kinds: Set<LiveKind>) {
        stored = if (kinds == defaultKinds(token)) stored - token else stored + (token to kinds)
        Repo.prefs(ctx).edit().putStringSet("liveKinds", kindsToRaw(stored)).apply()
    }
    fun forEvent(e: Event) = liveKindsFor(e, AlertChoices.follows, stored, AlertChoices.wants(e))
}

/** One football follow and its five alert chips, for More › Following. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveKindsRow(ctx: Context, token: String, onChanged: () -> Unit) {
    val ks = LiveKinds.kinds(token)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.tile)
        .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
        Text(if (token.startsWith("c:")) series(token.substring(2))?.name ?: token.substring(2) else token.substring(2),
            color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(when { ks.isEmpty() -> "No live alerts"; ks.size == LiveKind.entries.size -> "Every live alert"; else -> ks.joinToString(", ") { it.label } },
            color = C.fg2, fontFamily = Inter, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveKind.entries.forEach { k ->
                val on = k in ks
                FilterChip(on, { LiveKinds.set(ctx, token, if (on) ks - k else ks + k); onChanged() }, label = { Text(k.label, fontFamily = Inter, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.lime, selectedLabelColor = C.onLime, labelColor = C.fg))
            }
        }
    }
}
