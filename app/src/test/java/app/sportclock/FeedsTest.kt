package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Fixture-to-ESPN matching, with the name differences seen in the real feeds on 24-25 Oct 2026. */
class FeedsTest {
    private val kolnSchalke = Event("match", Sport.FOOTBALL, "GER", "Bundesliga", "Köln v Schalke", "Kick-off",
        Instant.parse("2026-10-24T13:30:00Z"), 120, "", "confirmed", listOf("1. FC Köln", "FC Schalke 04"), null, null, null)
    private fun espn(home: String, away: String, at: String) =
        EspnMatch(home + away, "x", Instant.parse(at), home, away, 1, 0, "in", "45'")

    @Test fun matchesAcrossNameStyles() {
        val e = kolnSchalke
        val pool = listOf(espn("Bayern Munich", "Mainz", e.start.toString()), espn("FC Cologne", "Schalke 04", e.start.toString()))
        assertEquals("FC Cologne", matchEspn(e, pool)?.home)
    }

    @Test fun ignoresTheSameClubsOnAnotherDay() {
        val e = kolnSchalke
        assertNull(matchEspn(e, listOf(espn("FC Cologne", "Schalke 04", e.start.plusSeconds(86400).toString()))))
    }

    @Test fun requiresBothTeamsToMatch() {
        val e = kolnSchalke
        assertNull(matchEspn(e, listOf(espn("FC Cologne", "Bayern Munich", e.start.toString()))))
    }

    @Test fun cupCompetitionsUseESPNScoreFeeds() {
        assertEquals("eng.fa", ESPN_LEAGUE["FAC"])
        assertEquals("eng.league_cup", ESPN_LEAGUE["CAR"])
        assertEquals(false, "FAC" in ESPN_TABLE_CODES)
        assertEquals(false, "CAR" in ESPN_TABLE_CODES)
    }

    @Test fun easternDateForLateKickoffs() {
        assertEquals("20261024", espnDate(Instant.parse("2026-10-25T02:00:00Z")))   // 22:00 in New York
        assertEquals("20261025", espnDate(Instant.parse("2026-10-25T14:00:00Z")))
    }
}
