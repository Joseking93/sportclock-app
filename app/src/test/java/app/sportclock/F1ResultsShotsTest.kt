package app.sportclock

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import coil.Coil
import coil.ImageLoader
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** The F1 Results page's four tabs from live Jolpica and OpenF1 data. Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h1500dp-xhdpi")
class F1ResultsShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(File("build/theme-shots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun settle(n: Int = 16) = repeat(n) { rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); Thread.sleep(500) }

    @Test fun results() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).allowHardware(false).crossfade(false).build())
        var tab by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            SportClockTheme("night", "dark") {
                Box(Modifier.fillMaxSize().background(C.bg)) { CompetitionTheme("F1"); key(tab) { F1ResultsView(Instant.now(), startTab = tab) } }
            }
        }
        if (System.getenv("SC_ALL") == "1") for ((i, name) in listOf("races", "drivers", "teams", "awards").withIndex()) { tab = i; settle(if (i == 0) 30 else 10); save("f1-results-$name") }
        // the Ferrari filter with its head-to-head
        tab = 1; settle(30)
        rule.onNodeWithText("Team").performClick(); settle(2)
        rule.onAllNodes(hasText("Ferrari") and hasClickAction()).onFirst().performClick(); settle(8)
        save("f1-results-ferrari")
    }
}
