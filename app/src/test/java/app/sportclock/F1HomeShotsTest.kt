package app.sportclock

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** The F1 home page from today's real data (the site's events, ESPN-free sources, F1's YouTube). Run with SC_SHOTS=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h4200dp-xhdpi")
class F1HomeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun f1Home() {
        assumeTrue(System.getenv("SC_SHOTS") == "1")
        Coil.setImageLoader(ImageLoader.Builder(rule.activity).allowHardware(false).crossfade(false).build())
        AlertChoices.load(rule.activity); AlertChoices.on = true
        val events = parseEvents(httpGet("$SITE/data.json")).first
        val now = Instant.now()
        rule.setContent {
            SportClockTheme("night", "dark") {
                Box(Modifier.fillMaxSize().background(C.bg)) {
                    CompetitionTheme("F1")
                    F1Home(events, now, {}, {}, {})
                }
            }
        }
        repeat(40) { rule.mainClock.advanceTimeBy(500); rule.waitForIdle(); Thread.sleep(500) }
        val dir = File("build/theme-shots").apply { mkdirs() }
        val bmp = rule.runOnIdle {
            val v = rule.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "f1-home.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
