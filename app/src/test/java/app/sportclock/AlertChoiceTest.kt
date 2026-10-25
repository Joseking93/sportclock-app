package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Which events alert, against the real data.json for the weekend of 24-25 Oct 2026. */
class AlertChoiceTest {
    private val events = parseEvents(File("../../sportclock/data.json").readText()).first
    private val now = Instant.parse("2026-10-24T06:00:00Z")
    private val follows = setOf("c:ENG", "t:Manchester United", "c:F1", "c:UFC")
    private val none = emptySet<String>()
    private fun upcoming(code: String) = events.filter { it.code == code && !it.isTbc && it.start.isAfter(now) }

    @Test fun premierLeagueIsPickedOnlyByDefault() {
        val pl = upcoming("ENG")
        val alerting = pl.filter { wantsAlert(it, follows, none, none, none) }
        println("PL upcoming ${pl.size}, alerting by default: " + alerting.joinToString { it.title })
        assertTrue(pl.size > 5)
        assertTrue(alerting.all { e -> e.teams.contains("Manchester United") })   // only via the followed team
        assertTrue(upcoming("F1").all { wantsAlert(it, follows, none, none, none) })
    }

    @Test fun pickAndMuteOverrideTheFollow() {
        val other = upcoming("ENG").first { !it.teams.contains("Manchester United") }
        assertFalse(wantsAlert(other, follows, none, none, none))
        assertTrue(wantsAlert(other, follows, none, setOf(alertKey(other)), none))
        val race = upcoming("F1").first()
        assertFalse(wantsAlert(race, follows, none, none, setOf(alertKey(race))))
        assertTrue(wantsAlert(race, emptySet(), none, setOf(alertKey(race)), none))    // a pick works without following
    }

    @Test fun switchingAFollowToAllOrPicked() {
        assertFalse(alertsAll("c:ENG", none)); assertTrue(alertsAll("c:ENG", setOf("c:ENG")))
        assertTrue(alertsAll("c:F1", none)); assertFalse(alertsAll("c:F1", setOf("c:F1")))
        assertTrue(alertsAll("t:Manchester United", none))
        val other = upcoming("ENG").first { !it.teams.contains("Manchester United") }
        assertTrue(wantsAlert(other, follows, setOf("c:ENG"), none, none))
    }

    @Test fun keysSurviveAMoveAndTbcNeverAlerts() {
        val m = upcoming("ENG").first()
        assertEquals(alertKey(m), alertKey(m.copy(start = m.start.plusSeconds(3 * 86400))))
        events.firstOrNull { it.isTbc }?.let { assertFalse(wantsAlert(it, follows, none, setOf(alertKey(it)), none)) }
    }
}
