package app.sportclock

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Renders each competition's league page and a match sheet to PNGs in build/theme-shots, to look at the themes. Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThemeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-11-03T09:00:00Z")
    private fun ev(code: String, home: String, away: String, h: Long) = Event("$code|$home", Sport.FOOTBALL, code, series(code)?.name ?: code,
        "$home v $away", "Kick-off", now.plusSeconds(h * 3600), 115, "", "confirmed", listOf(home, away), null, null, null)
    private val events = listOf(
        ev("UCL", "Real Madrid", "Liverpool", 30), ev("UCL", "Inter", "Arsenal", 30), ev("UCL", "Bayern München", "Paris Saint-Germain", 54),
        ev("ENG", "Arsenal", "Manchester City", 102), ev("ENG", "Manchester United", "Tottenham Hotspur", 105),
        ev("ESP", "FC Barcelona", "Atlético de Madrid", 130), ev("GER", "FC Bayern München", "Borussia Dortmund", 104),
        ev("ITA", "FC Internazionale Milano", "Juventus", 133), ev("FRA", "Paris Saint-Germain", "Olympique de Marseille", 133),
        ev("UEL", "Manchester United", "Lazio", 80), ev("FAC", "Manchester United", "Burnley", 1500))

    private val gp = Event("F1|sp", Sport.MOTORSPORT, "F1", "Formula 1", "São Paulo GP", "Race", Instant.parse("2026-11-08T17:00:00Z"), 120, "", "confirmed", emptyList(), null, null, null)

    private fun save(name: String) {
        val dir = File("build/theme-shots").apply { mkdirs() }
        // drawn straight from the window: Compose's own capture waits for a hardware redraw that Robolectric never sends
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shots() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val st = AppState(ctx)
        var page by mutableStateOf("league:UCL")
        rule.setContent {
            SportClockTheme("night", "dark") {
                Box(Modifier.fillMaxSize()) {
                    val p = page
                    if (p == "f1hub") F1Hub(listOf(gp), now, {}, {})
                    else if (p == "tyres") Box(Modifier.fillMaxSize()) {
                        CompetitionTheme("F1"); CompBackdrop("F1", Modifier.matchParentSize())
                        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                            listOf(58.dp, 24.dp, 18.dp).forEach { sz ->
                                androidx.compose.foundation.layout.Row { listOf("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET").forEach { Box(Modifier.padding(5.dp)) { TyreBadge(it, sz) } } }
                            }
                        }
                    }
                    else if (p == "worth") Box(Modifier.fillMaxSize()) {
                        CompetitionTheme("F1"); CompBackdrop("F1", Modifier.matchParentSize())
                        // the row as the weekend page has it: inside a scrolling column, with one line on Home above it
                        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            FactOfTheDay(listOf(gp), setOf("c:F1"), now) {}
                            WorthKnowing(gp)
                            androidx.compose.material3.Text("The track", color = C.lime)
                        }
                    }
                    else if (p.startsWith("fact:")) Box(Modifier.fillMaxSize()) {
                        CompetitionTheme("F1"); CompBackdrop("F1", Modifier.matchParentSize())
                        val doc = parseFacts(javaClass.getResource("/f1facts.json")!!.readText())
                        FactCard(doc.facts[p.removePrefix("fact:").toInt()], doc.credit, Modifier.padding(20.dp))
                    }
                    else if (p.startsWith("league:")) LeaguePage(p.removePrefix("league:"), st, events, now, emptyMap(), {}, {}, {})
                    else events.first { it.code == p.removePrefix("sheet:") }.let { e -> DetailSheet(e, now, null, null, events = events) }
                }
            }
        }
        F1Facts.doc = parseFacts(javaClass.getResource("/f1facts.json")!!.readText())
        for (p in listOf("tyres", "f1hub", "worth", "fact:0", "fact:1", "fact:3", "fact:4", "fact:5", "league:UCL", "sheet:UCL", "league:ENG", "league:ESP", "league:GER", "league:ITA", "league:FRA", "league:UEL", "league:FAC", "sheet:ENG")) {
            page = p; rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); save(p.replace(':', '-'))
        }
    }
}
