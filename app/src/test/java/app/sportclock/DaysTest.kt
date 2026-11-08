package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** The Scores tab's day pages: our events keep their ids and take ESPN's score, everything else on the boards is added. */
class DaysTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi")) }
    private val day = LocalDate.of(2026, 11, 7)
    private val ko = Instant.parse("2026-11-07T15:00:00Z")
    private fun m(id: String, lg: String, home: String, away: String, at: Instant = ko, h: Int? = 2, a: Int? = 1, state: String = "post") =
        EspnMatch(id, lg, at, home, away, h, a, state, if (state == "post") "FT" else "")
    private val united = Event("ENG|1", Sport.FOOTBALL, "ENG", "Premier League", "Manchester United v Tottenham", "Kick-off", ko, 115, "", "confirmed",
        listOf("Manchester United FC", "Tottenham Hotspur FC"), null, null, null)

    @Test fun ourGamesKeepTheirIdsAndTakeEspnScores() {
        val boards = mapOf("ENG" to listOf(m("701", "eng.1", "Manchester United", "Tottenham Hotspur"), m("702", "eng.1", "Arsenal", "Chelsea", h = 0, a = 0)),
            "FAC" to listOf(m("801", "eng.fa", "Burnley", "Leeds United")),
            "ESP" to listOf(m("901", "esp.1", "Real Madrid", "Sevilla", at = Instant.parse("2026-11-07T22:30:00Z"))))   // 01:30 the next day in Nairobi
        val g = dayGames(day, listOf(united), boards)
        assertEquals(setOf("ENG|1", "ESPN|702", "CUP|801"), g.map { it.event.id }.toSet())
        assertEquals("Manchester United", g.first { it.event.id == "ENG|1" }.home)     // ESPN's name, not "Manchester United FC"
        val mu = g.first { it.event.id == "ENG|1" }.score!!
        assertEquals("FINISHED", mu.status); assertEquals(2, mu.home); assertEquals("701", mu.espnId)
        assertTrue(g.none { it.event.title.contains("Real Madrid") })
        assertEquals("FA Cup", g.first { it.event.id == "CUP|801" }.event.comp)
    }

    @Test fun liveAndUpcomingScores() {
        assertEquals("IN_PLAY", espnScore(m("1", "eng.1", "A", "B", state = "in")).status)
        assertEquals("TIMED", espnScore(m("1", "eng.1", "A", "B", h = null, a = null, state = "pre")).status)
        assertEquals("PAUSED", espnScore(EspnMatch("1", "eng.1", ko, "A", "B", 1, 0, "in", "HT")).status)
    }

    @Test fun dayNamesAndFreshness() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals("Yesterday", dayTab(today.minusDays(1), today)); assertEquals("Today", dayTab(today, today))
        assertEquals("Tomorrow", dayTab(today.plusDays(1), today)); assertEquals("Fri", dayTab(today.minusDays(2), today))
        assertEquals(24 * 3600_000L, DayBoards.maxAgeMs(today.minusDays(3), today, false))
        assertEquals(60_000L, DayBoards.maxAgeMs(today, today, true))
    }

    @Test fun premierLeagueAlwaysFirst() {
        val codes = listOf("KNV", "GER", "UCL", "EFL", "ITA", "XYZ", "ENG", "ESP", "FAC", "FRA")
        assertEquals(listOf("ENG", "ESP", "ITA", "GER", "FRA", "UCL", "EFL", "FAC", "KNV", "XYZ"), codes.sortedBy(::compRank))
        assertEquals(ESPN_COMP.keys, COMP_ORDER.toSet())     // every competition we score has a place
    }
}
