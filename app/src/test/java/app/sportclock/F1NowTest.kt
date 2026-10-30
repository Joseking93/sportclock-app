package app.sportclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.Instant

/** The live panel's clock and flag colours. */
class F1NowTest {
    @Test fun clockCountsDownWhileRunning() {
        val s = parseF1(JSONObject("""{"SessionStatus":{"Status":"Started"},"TrackStatus":{"Status":"6"},
            "ExtrapolatedClock":{"Utc":"2026-10-30T22:35:16.9385013Z","Remaining":"00:24:43","Extrapolating":true}}"""))
        assertEquals(Duration.ofSeconds(24 * 60 + 43), s.clockLeft)
        assertEquals("24:33", clock(s.left(Instant.parse("2026-10-30T22:35:26.94Z"))!!))
        assertEquals("1:00:00", clock(Duration.ofHours(1)))
        // a red flag stops the clock: it reads what F1 last stamped
        val stopped = s.copy(clockRunning = false)
        assertEquals("24:43", clock(stopped.left(Instant.parse("2026-10-30T23:00:00Z"))!!))
    }

    @Test fun flagsColourTheApp() {
        fun snap(status: String, track: String) = parseF1(JSONObject("""{"SessionStatus":{"Status":"$status"},"TrackStatus":{"Status":"$track"}}"""))
        assertEquals("green", flagKind(snap("Started", "1"))); assertEquals("yellow", flagKind(snap("Started", "2")))
        assertEquals("sc", flagKind(snap("Started", "4"))); assertEquals("vsc", flagKind(snap("Started", "6"))); assertEquals("vsc", flagKind(snap("Started", "7")))
        assertEquals("red", flagKind(snap("Started", "5"))); assertEquals("red", flagKind(snap("Aborted", "1")))
        assertEquals("chequered", flagKind(snap("Finished", "1"))); assertEquals("waiting", flagKind(snap("Inactive", "1"))); assertEquals("waiting", flagKind(null))
        assertEquals(null, flagPalette("green")); assertEquals(null, flagPalette("chequered"))
        assertEquals(flagPalette("yellow"), flagPalette("vsc"))
        assertEquals(Color(0xFFC62828), flagPalette("red")!!.tile)
    }
}
