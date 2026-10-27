package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Fixture-to-ESPN matching, with the name differences seen in the real feeds on 24-25 Oct 2026. */
class FeedsTest {
    private val events by lazy { parseEvents(java.io.File("../../sportclock/data.json").readText()).first }
    /** Köln v Schalke as our feed names it; built here so the test does not depend on the day's fixtures. */
    private val kolnSchalke by lazy { events.first { it.sport == Sport.FOOTBALL && it.teams.size == 2 }
        .copy(code = "GER", teams = listOf("1. FC Köln", "FC Schalke 04"), start = Instant.parse("2026-10-24T13:30:00Z"))
    }
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

    @Test fun easternDateForLateKickoffs() {
        assertEquals("20261024", espnDate(Instant.parse("2026-10-25T02:00:00Z")))   // 22:00 in New York
        assertEquals("20261025", espnDate(Instant.parse("2026-10-25T14:00:00Z")))
    }

    @Test fun parsesCupFixturesWithTheirRoundAndTeams() {
        val json = """{"events":[{"id":"123","name":"Arsenal at Fleetwood Town","date":"2026-10-27T20:00Z","season":{"slug":"fourth-round"},"competitions":[{"venue":{"fullName":"Highbury"},"competitors":[{"homeAway":"home","team":{"displayName":"Fleetwood Town"}},{"homeAway":"away","team":{"displayName":"Arsenal"}}]}]}]}"""
        val event = parseCupEvents(json, "CARABAO").single()

        assertEquals("CARABAO|123", event.id)
        assertEquals(listOf("Fleetwood Town", "Arsenal"), event.teams)
        assertEquals("Fourth Round", event.session)
        assertEquals("Carabao Cup · Fourth Round", event.line2())
        assertEquals(Instant.parse("2026-10-27T20:00:00Z"), event.start)
        assertTrue(followed(event, setOf("c:CARABAO")))
        assertTrue(tokenMatches("t:Arsenal", event))
        assertTrue(tokenMatches("t:Arsenal FC", event))
        assertTrue(followed(event, setOf("t:Arsenal FC")))
    }

    @Test fun followedClubMatchesCommonNameVariantsAcrossCompetitions() {
        val manCity = Event("cup", Sport.FOOTBALL, "CARABAO", "Carabao Cup", "Man City v Arsenal", "Third Round",
            Instant.parse("2026-10-01T19:00:00Z"), 120, "", "confirmed", listOf("Man City", "Arsenal"), null, null, null)
        assertTrue(tokenMatches("t:Manchester City", manCity))
        assertTrue(followed(manCity, setOf("t:Manchester City")))
    }

    @Test fun cupCompetitionsUseTheirEspnFeeds() {
        assertEquals("eng.fa", ESPN_LEAGUE["FACUP"])
        assertEquals("eng.league_cup", ESPN_LEAGUE["CARABAO"])
    }
}
