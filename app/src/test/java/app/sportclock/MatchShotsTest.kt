package app.sportclock

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** Renders the match page's tabs from ESPN's real data to PNGs in build/theme-shots. Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1300dp-xhdpi")
class MatchShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val dir = File("build/theme-shots").apply { mkdirs() }
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun football(id: String, home: String, away: String, start: Instant) = Event("ENG|$id", Sport.FOOTBALL, "ENG", "Premier League",
        "$home v $away", "Kick-off", start, 115, "", "confirmed", listOf(home, away), null, null, null)

    @Test fun matchPage() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val played = football("ars", "Arsenal", "Hull City", Instant.parse("2026-11-07T15:00:00Z"))
        val ft = Score("FINISHED", 1, 0, null, "FT", "401878760", "eng.1")
        // a match still to come, from ESPN's board for the next Premier League weekend
        val next = runBlocking { espnScoreboard("eng.1", "20261121") }.first()
        val upcoming = football("next", next.home, next.away, next.start)
        val pre = Score("TIMED", null, null, null, "", next.id, "eng.1")
        var page by mutableStateOf("Facts")
        rule.setContent {
            SportClockTheme("night", "dark") {
                Box(Modifier.fillMaxSize().background(C.tile)) {
                    val p = page
                    key(p) {
                        CompetitionTheme("ENG"); CompBackdrop("ENG", Modifier.matchParentSize())
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp)) {
                            if (p == "pre") MatchPage(upcoming, Instant.now(), pre, null, {}, {}) { AlertRow(upcoming, Instant.now()) }
                            else MatchPage(played, Instant.now(), ft, null, {}, {}, startTab = p) {}
                        }
                    }
                }
            }
        }
        for (p in listOf("Facts", "Lineup", "Stats", "Commentary", "Table", "H2H", "pre")) {
            page = p
            // the page loads ESPN's summary for real: give it time
            repeat(16) { rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); Thread.sleep(400) }
            save("match-" + p.lowercase())
        }
    }
}
