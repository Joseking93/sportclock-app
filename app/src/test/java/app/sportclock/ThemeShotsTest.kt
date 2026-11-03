package app.sportclock

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
                    if (p.startsWith("league:")) LeaguePage(p.removePrefix("league:"), st, events, now, emptyMap(), {}, {}, {})
                    else events.first { it.code == p.removePrefix("sheet:") }.let { e -> DetailSheet(e, now, null, null, events = events) }
                }
            }
        }
        for (p in listOf("league:UCL", "sheet:UCL", "league:ENG", "league:ESP", "league:GER", "league:ITA", "league:FRA", "league:UEL", "league:FAC", "sheet:ENG")) {
            page = p; rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); save(p.replace(':', '-'))
        }
    }
}
