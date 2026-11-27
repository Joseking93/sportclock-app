package app.sportclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** F1's own live-timing state after qualifying for the 2026 United States GP, saved as a test resource. */
class F1LiveTest {
    private val snap = parseF1(JSONObject(javaClass.getResource("/f1-austin-quali.json")!!.readText()))

    @Test fun readsTheSession() {
        assertEquals("United States Grand Prix", snap.meeting)
        assertEquals("Qualifying", snap.session)
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), snap.start)       // 16:00 local, GMT-5
        assertTrue(snap.over && !snap.live && !snap.racing)
        assertEquals(9, snap.circuitKey)
    }

    @Test fun towerIsInOrderWithNamesAndPoleHasNoGap() {
        assertEquals(22, snap.lines.size)
        assertEquals((1..22).toList(), snap.lines.map { it.pos })
        assertEquals("ANT", snap.tla(snap.lines[0].number))
        assertEquals("", snap.lines[0].gap)
        assertTrue(snap.lines[1].gap.startsWith("+"))
        assertTrue(snap.lines.all { snap.drivers[it.number]?.tla?.length == 3 })
    }

    @Test fun matchesOurQualifyingNotTheRace() {
        val e = parseEvents(java.io.File("../../sportclock/data.json").readText()).first
        val quali = e.first { it.code == "F1" && it.session == "Qualifying" && it.start == Instant.parse("2026-10-24T21:00:00Z") }
        val race = e.first { it.code == "F1" && it.session == "Race" && it.start == Instant.parse("2026-10-25T20:00:00Z") }
        assertTrue(snap.isFor(quali)); assertTrue(!snap.isFor(race))
    }

    @Test fun flaggedSectorsFollowYellowAndClear() {
        fun m(flag: String, text: String) = F1Msg("", "Flag", flag, text, null, null)
        assertEquals(mapOf(8 to "DOUBLE YELLOW", 7 to "YELLOW"),
            flaggedSectors(listOf(m("DOUBLE YELLOW", "DOUBLE YELLOW IN TRACK SECTOR 8"), m("YELLOW", "YELLOW IN TRACK SECTOR 7"))))
        assertEquals(mapOf(8 to "DOUBLE YELLOW"), flaggedSectors(listOf(m("DOUBLE YELLOW", "DOUBLE YELLOW IN TRACK SECTOR 8"),
            m("YELLOW", "YELLOW IN TRACK SECTOR 7"), m("CLEAR", "CLEAR IN TRACK SECTOR 7"))))
        assertEquals(emptyMap<Int, String>(), flaggedSectors(snap.messages))     // all cleared by the end of qualifying
        assertEquals(emptyMap<Int, String>(), flaggedSectors(listOf(m("YELLOW", "YELLOW IN TRACK SECTOR 3"), m("GREEN", "GREEN LIGHT - PIT EXIT OPEN"))))
    }

    @Test fun upgradesUrlMatchesTheFiaNaming() {
        assertEquals("https://www.fia.com/system/files/decision-document/2026_united_states_grand_prix_-_car_presentation_submissions.pdf",
            fiaUpgradesUrl("United States Grand Prix", 2026))
        assertTrue(fiaUpgradesUrl("São Paulo Grand Prix", 2026).contains("2026_sao_paulo_grand_prix_-_"))
    }

    /** 2.36: the last snapshot is kept on the phone and opens at once; a fresh enough one is reused, not fetched again. */
    @Test fun keptOnThePhone() = kotlinx.coroutines.runBlocking {
        val dir = kotlin.io.path.createTempDirectory("f1").toFile()
        java.io.File(dir, "f1-live.json").writeText(javaClass.getResource("/f1-austin-quali.json")!!.readText())
        F1Now.snap = null; F1Now.at = 0
        F1Now.init(dir)
        assertEquals("Qualifying", F1Now.snap?.session)
        assertTrue(F1Now.age() < 60_000)
        assertEquals("Qualifying", F1Now.refresh(60_000).session)            // no network: the copy is young enough
    }
}
