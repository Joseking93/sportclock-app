package app.sportclock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NationsTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val now = Instant.parse("2026-11-17T12:00:00Z")

    private fun kenyaDoc(): JSONObject {
        val games = parseNationGames(JSONObject(text("ken-results.json"))) + parseNationGames(JSONObject(text("ken-fixtures.json")))
        return JSONObject().put("Kenya", JSONArray(games.distinctBy { it.id }.map { g ->
            JSONObject().put("id", g.id).put("slug", g.slug).put("league", g.league).put("date", g.date.toString()).put("home", g.home)
                .put("away", g.away).put("venue", g.venue).put("tv", g.timeValid)
        }))
    }

    @Test fun countriesAreFollowable() {
        assertEquals("2848", NATIONS["Kenya"])
        assertTrue(isNation("England")); assertTrue(isNation("South Africa")); assertTrue(isNation("Brazil"))
        assertFalse(isNation("Manchester United")); assertFalse(isNation("Arsenal"))
        assertTrue(NATIONS.size > 200)
    }

    @Test fun kenyasGamesBecomeEvents() {
        val es = Nations.toEvents(kenyaDoc(), now)
        val tonight = es.single { it.start == Instant.parse("2026-11-17T16:00:00Z") }
        assertEquals("Kenya v South Africa", tonight.title)
        assertEquals(listOf("Kenya", "South Africa"), tonight.teams)
        assertEquals("ACQ", tonight.code); assertEquals("Africa Cup of Nations qualifying", tonight.comp)
        assertTrue(tonight.id.startsWith("CUP|")); assertEquals("Nyayo National Stadium", tonight.venue)
        assertTrue(tokenMatches("t:Kenya", tonight))
        // the March qualifiers are there, and nothing older than four months
        assertTrue(es.any { it.title == "Eritrea v Kenya" && it.start.toString().startsWith("2027-03-24") })
        assertTrue(es.none { it.start.isBefore(now.minusSeconds(121L * 86400)) })
        assertTrue(es.any { it.code == "FRI" && it.title == "Rwanda v Kenya" })
    }

    @Test fun competitionsMapToOurCodes() {
        assertEquals("ACQ", intlCode("caf.nations_qual")); assertEquals("WCA", intlCode("fifa.worldq.caf"))
        assertEquals("CHAN", intlCode("caf.championship")); assertEquals("INT", intlCode("cecafa.cup"))
        assertEquals("International football", cup("INT")!!.name)
        // the rare ones are scored, but only fetched on a day one of our games needs them
        assertTrue("WCA" in ESPN_COMP); assertFalse("WCA" in BOARD_COMP); assertTrue("AFCON" in BOARD_COMP)
        assertFalse(ESPN_COMP.containsKey("INT"))
        assertTrue(compRank("ENG") < compRank("AFCON")); assertTrue(compRank("ACQ") < compRank("FRI"))
    }

    @Test fun aBelledBoardMatchSurvivesARestart() {
        val e = Event("CUP|401912345", Sport.FOOTBALL, "ACQ", "Africa Cup of Nations qualifying", "Kenya v South Africa", "Kick-off",
            Instant.parse("2026-11-17T16:00:00Z"), 120, "Nyayo National Stadium", "confirmed", listOf("Kenya", "South Africa"), null, null, null)
        val back = eventsFromJson(eventsToJson(listOf(e))).single()
        assertEquals(e, back)
        assertEquals(alertKey(e), alertKey(back))
        assertTrue(eventsFromJson("[{\"id\":\"x\"}]").isEmpty())                   // a damaged entry is skipped, not a crash
    }
}
