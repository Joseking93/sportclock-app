package app.sportclock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Cup ties from ESPN's per-club cup schedules (saved 28 Oct 2026), and how each club's cup run reads. */
class CupsTest {
    private fun res(n: String) = javaClass.getResource("/$n")!!.readText()
    private val now = Instant.parse("2026-10-28T08:00:00Z")

    @Test fun arsenalOutOnPenalties() {
        val ties = parseCupTies("LC", res("cup-arsenal-lc.json"))
        val last = ties.maxBy { it.date }
        assertEquals("Fleetwood Town", last.home)
        assertTrue(last.homeWon && !last.awayWon)
        assertEquals("1", last.homeScore); assertEquals("1", last.awayScore)
        val lc = cupRuns("ENG", "359", ties, now).first { it.cup.code == "LC" }
        assertEquals(false, lc.alive)
        assertTrue(lc.line, lc.line.startsWith("Out · 1–1 v Fleetwood") && lc.line.contains("penalties"))
    }

    @Test fun unitedKnockedOutByBrighton() {
        val ties = parseCupTies("LC", res("cup-united-lc.json"))
        val lc = cupRuns("ENG", "360", ties, now).first { it.cup.code == "LC" }
        assertEquals(false, lc.alive)
        assertTrue(lc.line, lc.line.startsWith("Out · lost 2–3 to Brighton"))
        // no FA Cup tie yet: Premier League clubs join in January
        assertEquals(null, cupRuns("ENG", "360", ties, now).first { it.cup.code == "FAC" }.alive)
        // not in Europe this season, so no Europa or Conference League row
        assertTrue(cupRuns("ENG", "360", ties, now).none { it.cup.code == "UEL" || it.cup.code == "UECL" })
    }

    @Test fun cityStillInWithTheNextTie() {
        val ties = parseCupTies("LC", res("cup-city-lc-fixture.json"))
        val next = ties.first { it.state == "pre" }
        assertEquals(null, next.homeScore)
        val lc = cupRuns("ENG", "382", ties, now).first { it.cup.code == "LC" }
        assertEquals(true, lc.alive)
        assertTrue(lc.line, lc.line.startsWith("Still in · Fourth Round v Brighton (home)"))
    }

    @Test fun tiesBecomeEventsUnderOurNames() {
        val ties = parseCupTies("LC", res("cup-city-lc-fixture.json"))
        val doc = JSONObject().put("Manchester City", JSONObject().put("id", "382").put("ties", JSONArray(ties.map {
            JSONObject().put("cup", it.cup).put("id", it.id).put("date", it.date.toString()).put("homeId", it.homeId).put("home", it.home)
                .put("awayId", it.awayId).put("away", it.away).put("state", it.state).put("round", it.round)
        })))
        val e = Cups.toEvents(doc).first()
        assertEquals("LC", e.code)
        assertEquals("Carabao Cup · Fourth Round", e.comp)
        assertTrue(e.teams.contains("Manchester City"))
        assertTrue(ESPN_COMP.containsKey(e.code))
    }

    @Test fun clubLookupPicksTheRightClub() {
        fun best(ours: String, vararg espn: String) = espn.maxBy { clubScore(it, ours) }
        assertEquals("Bayern Munich", best("FC Bayern München", "Bayer Leverkusen", "Bayern Munich"))
        assertEquals("Real Madrid", best("Real Madrid CF", "Atlético Madrid", "Real Madrid", "Real Sociedad"))
        assertEquals("Internazionale", best("FC Internazionale Milano", "AC Milan", "Internazionale"))
        assertEquals("Manchester United", best("Manchester United", "Manchester City", "Manchester United", "Newcastle United"))
    }

    @Test fun lastSeasonsTiesDontCount() {
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), seasonStart(now))
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), seasonStart(Instant.parse("2027-02-10T12:00:00Z")))
    }
}
