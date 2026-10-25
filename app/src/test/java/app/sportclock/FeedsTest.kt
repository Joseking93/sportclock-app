package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Fixture-to-ESPN matching, with the name differences seen in the real feeds on 24-25 Oct 2026. */
class FeedsTest {
    private val events = parseEvents(java.io.File("../../sportclock/data.json").readText()).first
    private fun espn(home: String, away: String, at: String) =
        EspnMatch(home + away, "x", Instant.parse(at), home, away, 1, 0, "in", "45'")

    @Test fun matchesAcrossNameStyles() {
        val e = events.first { it.code == "GER" && it.teams.any { t -> "Schalke" in t } }
        val pool = listOf(espn("Bayern Munich", "Mainz", e.start.toString()), espn("FC Cologne", "Schalke 04", e.start.toString()))
        assertEquals("FC Cologne", matchEspn(e, pool)?.home)
    }

    @Test fun ignoresTheSameClubsOnAnotherDay() {
        val e = events.first { it.code == "GER" && it.teams.any { t -> "Schalke" in t } }
        assertNull(matchEspn(e, listOf(espn("FC Cologne", "Schalke 04", e.start.plusSeconds(86400).toString()))))
    }

    @Test fun easternDateForLateKickoffs() {
        assertEquals("20261024", espnDate(Instant.parse("2026-10-25T02:00:00Z")))   // 22:00 in New York
        assertEquals("20261025", espnDate(Instant.parse("2026-10-25T14:00:00Z")))
    }
}
