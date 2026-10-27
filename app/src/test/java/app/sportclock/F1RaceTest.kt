package app.sportclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1's own live-timing state after the 2026 United States GP race, saved as a test resource. */
class F1RaceTest {
    private val snap = parseF1(JSONObject(javaClass.getResource("/f1-austin-race.json")!!.readText()))

    @Test fun lapChartAndStrategy() {
        assertEquals("Race", snap.session)
        val ant = snap.laps["12"]!!
        println("ANT positions: ${ant.take(30)} (${ant.size} laps); stints ${snap.stints["12"]}")
        assertTrue(ant.size > 40 && ant.first() == 1)
        assertTrue(snap.stints.values.all { s -> s.all { it.compound.isNotBlank() } } && snap.stints.size >= 18)
        assertTrue(snap.stints.values.flatten().any { it.laps > 10 })
    }

    @Test fun sectorsSpeedsAndRadio() {
        val s = snap.sectors.values.first()
        assertEquals(3, s.size)
        assertTrue(s.all { it.segments.isNotEmpty() })
        assertTrue(snap.trap.values.all { it in 250..380 })
        println("top speeds: ${snap.topSpeeds.take(3).map { "${snap.tla(it.number)} ${it.value}" }}; best laps: ${snap.bestLaps.take(3).map { "${snap.tla(it.number)} ${it.value}" }}")
        assertEquals(1, snap.bestLaps.first().pos)
        println("radio: ${snap.radio.size} clips, newest ${snap.radio.first().url}")
        assertTrue(snap.radio.isNotEmpty() && snap.radio.all { it.url.startsWith("https://livetiming.formula1.com/static/2026/") && it.url.endsWith(".mp3") })
    }
}
