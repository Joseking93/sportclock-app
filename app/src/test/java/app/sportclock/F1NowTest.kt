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

    @Test fun raceControlTimesReadInThePhonesTime() {
        val brazil = java.time.ZoneOffset.ofHours(-3)
        assertEquals("SESSION WILL RESUME AT ${hm(Instant.parse("2026-11-06T15:49:00Z"))}",
            trackTimes("SESSION WILL RESUME AT 12:49", "2026-11-06T15:47:44", brazil))
        assertEquals("RED FLAG INFRINGEMENT (${hm(Instant.parse("2026-11-06T15:42:00Z"))})",
            trackTimes("RED FLAG INFRINGEMENT (12:42:08)", "2026-11-06T15:49:33", brazil))
        // lap times are not clock times
        val lap = "CAR 23 (ALB) TIME 1:32.736 DELETED"
        assertEquals(lap, trackTimes(lap, "2026-11-06T15:43:45", brazil))
        assertEquals("TIME 12:34.567", trackTimes("TIME 12:34.567", "2026-11-06T15:43:45", brazil))
        assertEquals("RESUME AT 12:49", trackTimes("RESUME AT 12:49", "2026-11-06T15:47:44", null))
    }

    @Test fun aStoppedSessionIsARedFlagWhateverTheStatusSays() {
        fun snap(status: String, flags: String) = parseF1(JSONObject("""{"SessionStatus":{"Status":"$status"},"TrackStatus":{"Status":"1"},
            "RaceControlMessages":{"Messages":[$flags]}}"""))
        val green = """{"Utc":"2026-11-06T15:30:00","Category":"Flag","Flag":"GREEN","Message":"GREEN LIGHT - PIT EXIT OPEN"}"""
        val red = """{"Utc":"2026-11-06T15:42:08","Category":"Flag","Flag":"RED","Message":"RED FLAG"}"""
        assertEquals("red", flagKind(snap("Inactive", "$green,$red")))
        assertEquals("5", snap("Inactive", "$green,$red").track)
        assertEquals("waiting", flagKind(snap("Inactive", "$green,$red,$green")))
        assertEquals("green", flagKind(snap("Started", "$green,$red,$green")))
        assertEquals("waiting", flagKind(snap("Inactive", "")))
        assertEquals("chequered", flagKind(snap("Finished", "$green,$red")))
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
