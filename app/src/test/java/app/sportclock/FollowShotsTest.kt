package app.sportclock

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** The Following page with Kenya followed and "south" typed in the search, from ESPN's real schedule. Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h2300dp-xhdpi")
class FollowShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun followKenya() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        val ctx = rule.activity
        val games = parseNationGames(JSONObject(text("ken-results.json"))) + parseNationGames(JSONObject(text("ken-fixtures.json")))
        val doc = JSONObject().put("Kenya", JSONArray(games.distinctBy { it.id }.map { g ->
            JSONObject().put("id", g.id).put("slug", g.slug).put("league", g.league).put("date", g.date.toString()).put("home", g.home)
                .put("away", g.away).put("venue", g.venue).put("tv", g.timeValid)
        }))
        val now = Instant.parse("2026-11-17T14:00:00Z")
        val events = Nations.toEvents(doc, now)
        Repo.setFollows(ctx, setOf("c:F1", "c:UFC", "t:Manchester United", "t:Kenya"))
        val st = AppState(ctx)
        rule.setContent {
            SportClockTheme("night", "dark") {
                Box(Modifier.fillMaxSize().background(C.bg)) { FollowScreen(st, events, now, {}, {}) }
            }
        }
        rule.onNodeWithText("Add a team or country").performScrollTo().performTextInput("south")
        rule.waitForIdle()
        val dir = File("build/theme-shots").apply { mkdirs() }
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "follow-kenya.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
