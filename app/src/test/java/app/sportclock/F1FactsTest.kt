package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The facts file as the site publishes it (saved after the Mexican GP, round 19 of 2026). */
class F1FactsTest {
    private val doc = parseFacts(javaClass.getResource("/f1facts.json")!!.readText())
    private fun race(start: String) = Event("F1|x", Sport.MOTORSPORT, "F1", "Formula 1", "São Paulo GP", "Race", Instant.parse(start), 120, "", "confirmed", emptyList(), null, null, null)

    @Test fun readsTheFile() {
        assertEquals("v2026.19.0", doc.release)
        assertEquals("2026-11-08", doc.nextDate.toString())
        val f = doc.facts.first { it.id.startsWith("starts-lewis-hamilton") }
        assertEquals("bars", f.kind)
        assertEquals("400", f.rows.first { it.top }.text)
        assertEquals("gp", f.scope)
        val big = doc.facts.first { it.kind == "big" }
        assertTrue(big.value.isNotBlank() && big.rows.isEmpty())
    }

    @Test fun circuitFactsOnlyForTheirRace() {
        val brazil = factsFor(doc, race("2026-11-08T17:00:00Z"), null)
        assertTrue(brazil.any { it.scope == "gp" })
        val vegas = factsFor(doc, race("2026-11-22T04:00:00Z"), null)      // F1DB hasn't caught up: Interlagos facts must not show for Las Vegas
        assertFalse(vegas.any { it.scope == "gp" })
        assertTrue(vegas.isNotEmpty())
        assertTrue(factsFor(null, race("2026-11-08T17:00:00Z"), null).isEmpty())
    }

    @Test fun yourDriverComesFirst() {
        val f = factsFor(doc, race("2026-11-08T17:00:00Z"), "Fernando Alonso")
        assertTrue(f.first().drivers.contains("Fernando Alonso"))
        assertEquals(doc.facts.size, f.size)
    }
}
