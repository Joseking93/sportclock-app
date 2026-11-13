package app.sportclock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MoreSourcesTest {
    private fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun predictedLineupsSitInTheirFormations() {
        val xs = parsePredicted(JSONObject(text("tw-lineups.json")))
        assertEquals(20, xs.size)
        val ars = xs.single { twSame(it.club, "Arsenal") }
        assertEquals(listOf(1, 4, 2, 3, 1), ars.side().lines.map { it.size })
        assertEquals("Raya", ars.side().lines[0][0].name); assertEquals("GK", ars.side().lines[0][0].number)
        assertEquals(listOf("Calafiori", "Gabriel", "Konsa", "J.Timber"), ars.side().lines[1].map { it.name })   // left back to right back
        assertEquals(listOf("LB", "CD-L", "CD-R", "RB"), ars.side().lines[1].map { it.pos })
        val hull = xs.single { twSame(it.club, "Hull City") }
        assertEquals(listOf(1, 3, 4, 3), hull.side().lines.map { it.size })
        assertEquals("ST", hull.side().lines[3][1].number)                         // LW, ST, RW
    }

    @Test fun shortClubNamesMatchEspns() {
        assertTrue(twSame("Man Utd", "Manchester United")); assertTrue(twSame("Spurs", "Tottenham Hotspur"))
        assertTrue(twSame("Nott'm Forest", "Nottingham Forest")); assertTrue(twSame("Man City", "Manchester City"))
        assertFalse(twSame("Man City", "Manchester United"))
    }

    @Test fun injuriesCarryStatusAndStandIn() {
        val a = parseAbsences(JSONObject(text("tw-injuries.json")))
        val saka = a.single { it.name == "Saka" }
        assertEquals("injured", saka.status); assertEquals(0, saka.chance); assertEquals("Noni Madueke (RW)", saka.deputy)
        assertTrue(a.any { it.status == "suspended" } || a.any { it.status == "doubtful" })
        assertTrue(a.none { it.deputy == "null" || it.news == "null" })
    }

    @Test fun teamRadioGetsDriverNamesAndColours() {
        val clips = parseRadio(JSONArray(text("openf1-radio.json")), JSONArray(text("openf1-drivers.json")))
        assertEquals(30, clips.size)
        assertEquals(clips.sortedBy { it.at }, clips)
        val nor = clips.first { it.number == 1 }
        assertEquals("NOR", nor.code); assertEquals("Lando Norris", nor.name); assertEquals("McLaren", nor.team); assertEquals(0xFFF47600, nor.colour)
        assertTrue(nor.url.endsWith(".mp3"))
    }

    @Test fun boxingResultsMatchOurRows() {
        val res = parseBoxResults(JSONObject(text("bb-results.json")))
        val orobio = findBout("Orobio v Cedeno", Instant.parse("2026-11-13T02:30:00Z"), res)!!
        assertEquals("Jhon Orobio", orobio.a)
        assertEquals("Orobio won by TKO in round 3", orobio.result())
        assertEquals("Baumgardner won by unanimous decision", findBout("Baumgardner v Turhan", Instant.parse("2026-11-09T03:30:00Z"), res)!!.result())
        val bs = findBout("Billam-Smith v Clarke", Instant.parse("2026-11-07T21:30:00Z"), res)!!
        assertEquals("Chris Billam-Smith", bs.a); assertEquals("Clarke won by TKO in round 7", bs.result())
        assertNull(findBout("Fury v Joshua", Instant.parse("2026-11-07T21:30:00Z"), res))
    }

    @Test fun boxingScheduleHasTitlesTimesAndRecords() {
        val s = parseBoxSchedule(JSONObject(text("bb-schedule.json")))
        val k = s.single { it.a.endsWith("Kabayel") }
        assertEquals("WBC heavyweight title", k.title); assertEquals(LocalDate.parse("2026-11-28"), k.date)
        assertEquals(Instant.parse("2026-11-28T21:00:00Z"), k.start); assertEquals("31-0-0", k.aRecord); assertFalse(k.done)
        assertNotNull(findBout("Kabayel v Hysa", Instant.parse("2026-11-28T21:00:00Z"), s))
    }
}
