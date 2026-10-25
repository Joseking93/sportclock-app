package app.sportclock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/* ---------- More → Updates ---------- */

private sealed interface Up {
    data object Checking : Up
    data class Current(val v: String) : Up
    data class Available(val r: Release) : Up
    data class Failed(val msg: String) : Up
}

@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mine = remember { Diag.version(ctx).first }
    var state by remember { mutableStateOf<Up>(Up.Checking) }
    var opened by remember { mutableStateOf(false) }

    fun check() = scope.launch {
        state = Up.Checking
        state = runCatching { Updater.latest() }.fold(
            { r -> Updater.remember(ctx, r); if (Updater.newer(r.version, mine)) Up.Available(r) else Up.Current(r.version) },
            { e -> Up.Failed(if (e is ServiceNotSetUp) "The update service isn't switched on yet." else "Couldn't reach the update service. Check your connection.") })
    }
    LaunchedEffect(Unit) { check() }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
        Text("Updates", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 20.dp))
        Text("You have SportClock $mine", color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 2.dp))
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.tile).padding(18.dp)) {
            when (val s = state) {
                Up.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = C.lime, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                    Text("Checking…", color = C.fg, fontFamily = Inter)
                }
                is Up.Current -> {
                    Text("You're up to date", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("$mine is the newest version.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp)
                    TextButton(onClick = { check() }, contentPadding = PaddingValues(0.dp)) { Text("Check again", color = C.lime, fontFamily = Inter) }
                }
                is Up.Available -> {
                    Text("SportClock ${s.r.version} is ready", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    s.r.size?.let { Text("%.1f MB".format(it / 1_000_000f), color = C.fg2, fontFamily = Inter, fontSize = 13.sp) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { Diag.log("update open ${s.r.version}"); opened = true; Updater.openDownload(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = C.lime, contentColor = C.onLime)) {
                        Text("Download", fontFamily = Inter, fontWeight = FontWeight.Bold)
                    }
                    Text(if (opened) "When the download finishes, tap it and choose Update. If Play Protect asks, scan or choose Install anyway: it's your own app."
                         else "Your browser downloads it; tap the finished download to install. Your follows, favourites and settings stay as they are.",
                        color = C.fg2, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Notes(s.r.notes)
                }
                is Up.Failed -> {
                    Text(s.msg, color = C.fg, fontFamily = Inter, fontSize = 15.sp)
                    TextButton(onClick = { check() }, contentPadding = PaddingValues(0.dp)) { Text("Try again", color = C.lime, fontFamily = Inter) }
                }
            }
        }
        Text("SportClock checks for a new version twice a day and shows a note on Home when there is one.", color = C.fg3, fontFamily = Inter,
            fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun Notes(md: String) {
    if (md.isBlank()) return
    Text("What's new", color = C.lime, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    md.lines().map { it.trim() }.filter { it.startsWith("- ") }.forEach { line ->
        Text("• " + line.removePrefix("- ").replace("**", ""), color = C.fg2, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
    }
}

/* ---------- More → Report a problem ---------- */

@Composable
fun ReportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var withTrail by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) { Text("‹ Back", color = C.lime, fontFamily = Inter) }
        Text("Report a problem", color = C.fg, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(start = 20.dp))
        Text("Goes straight to the app's GitHub as an issue, with the version and phone model.", color = C.fg2, fontFamily = Inter, fontSize = 14.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(text, { text = it; result = null }, Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 140.dp),
            placeholder = { Text("What happened? e.g. \"Tables: Serie A shows nothing\"", fontFamily = Inter) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.lime, unfocusedBorderColor = C.tile2, focusedTextColor = C.fg, unfocusedTextColor = C.fg, cursorColor = C.lime))
        Row(Modifier.padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(withTrail, { withTrail = it }, colors = CheckboxDefaults.colors(checkedColor = C.lime, checkmarkColor = C.onLime))
            Text("Include the screens I just used", color = C.fg, fontFamily = Inter, fontSize = 14.sp)
        }
        Button(onClick = {
            sending = true
            scope.launch {
                Diag.save(ctx, "report", text.lineSequence().first().take(80), text, withTrail)
                result = when (Diag.sendPending(ctx).first) {
                    Diag.Sent.ALL -> { text = ""; "Sent. Thanks!" }
                    Diag.Sent.NOT_SET_UP -> "Saved on the phone. It goes out once reporting is switched on."
                    else -> "Saved on the phone. It goes out next time you're online."
                }
                sending = false
            }
        }, enabled = text.isNotBlank() && !sending, modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.lime, contentColor = C.onLime)) {
            Text(if (sending) "Sending…" else "Send report", fontFamily = Inter, fontWeight = FontWeight.Bold)
        }
        result?.let { Text(it, color = C.fg, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp)) }
        Text("If the app crashes, it sends a crash report by itself the next time you open it.", color = C.fg3, fontFamily = Inter, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    }
}
