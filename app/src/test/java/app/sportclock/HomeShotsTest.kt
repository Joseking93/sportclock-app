package app.sportclock

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** 2.34's Home and league pages drawn from the real data.json (and ESPN, BBC and YouTube for the network parts). Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1900dp-xhdpi")
class HomeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    init { TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi")) }
    private val events = parseEvents(File("../../sportclock/data.json").readText()).first
    private val lvMu = events.first { it.teams == listOf("Liverpool", "Manchester United") }

    private fun settle(secs: Int) = repeat(secs * 2) { Thread.sleep(500); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
    private fun save(name: String) {
        val dir = File("build/theme-shots").apply { mkdirs() }
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun st(): AppState {
        val ctx = rule.activity
        Repo.setFollows(ctx, setOf("c:F1", "c:UFC", "c:ESP", "c:ENG", "t:Manchester United", "t:Kenya"))
        AlertChoices.load(ctx)
        return AppState(ctx)
    }

    private fun home(now: Instant, scores: Map<String, Score>, name: String) {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val s = st()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SportClockTheme("night", "dark") { Box(Modifier.fillMaxSize().background(C.bg)) { HomeScreen(s, events, now, scores, {}, {}) } }
        }
        settle(12)
        save(name)
    }

    @Test fun homeLive() = home(lvMu.start.plusSeconds(58 * 60), mapOf(lvMu.id to Score("IN_PLAY", 1, 2, null, "58'")), "home-live.png")
    @Test fun homeFullTime() = home(lvMu.end.plusSeconds(40 * 60), mapOf(lvMu.id to Score("FINISHED", 1, 2, null, "FT")), "home-ft.png")
    @Test fun homeLineups() {
        Lineups.ready[lvMu.id] = MatchSheet(TeamSheet("Liverpool", "4-3-3", listOf(Player("Mohamed Salah", "11", "RW")), emptyList()),
            TeamSheet("Manchester United", "4-2-3-1", listOf(Player("Bruno Fernandes", "8", "AM")), emptyList()), emptyList())
        home(lvMu.start.minusSeconds(40 * 60), emptyMap(), "home-lineups.png")
    }

    @Test fun laLigaOverview() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val s = st()
        val now = Instant.parse("2026-11-21T12:00:00Z")
        rule.setContent {
            SportClockTheme("night", "dark") {
                Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
                    Spacer(Modifier.height(8.dp))
                    LeagueBody("ESP", s, events, now, emptyMap(), {}, {})
                }
            }
        }
        repeat(30) { Thread.sleep(500); rule.waitForIdle() }
        save("league-laliga.png")
    }

    @Test fun premierLeagueOverview() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val s = st()
        val now = Instant.parse("2026-11-21T12:00:00Z")
        rule.setContent {
            SportClockTheme("night", "dark") {
                Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) { LeagueBody("ENG", s, events, now, emptyMap(), {}, {}) }
            }
        }
        repeat(30) { Thread.sleep(500); rule.waitForIdle() }
        save("league-pl.png")
    }

    @Test fun followAlerts() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val s = st()
        LiveKinds.set(rule.activity, "c:ESP", setOf(LiveKind.FT))
        rule.setContent {
            SportClockTheme("night", "dark") { Box(Modifier.fillMaxSize().background(C.bg)) { FollowScreen(s, events, Instant.parse("2026-11-21T12:00:00Z"), {}, {}) } }
        }
        rule.onNodeWithText("Live match alerts").performScrollTo()
        rule.waitForIdle()
        save("follow-alerts.png")
    }

    /** 2.36: the F1 page opened from a notification after the session, drawn from the phone's copy: no white box. */
    @Test fun f1LiveFinished() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        F1Now.snap = parseF1(org.json.JSONObject(javaClass.getResource("/f1-austin-quali.json")!!.readText())); F1Now.at = System.currentTimeMillis()
        val now = Instant.parse("2026-10-24T23:00:00Z")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SportClockTheme("night", "dark") { Box(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) { F1Hub(events, now, {}, {}, action = "Close", start = 2) } }
        }
        settle(4)
        save("f1-live-finished.png")
    }
}
