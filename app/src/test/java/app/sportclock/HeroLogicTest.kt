package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.TimeZone

/** The NEXT UP logic against the real data.json, at 18:30 Nairobi on Sat 24 Oct 2026. */
class HeroLogicTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi")) }
    private val events = parseEvents(File("../../sportclock/data.json").readText()).first
    private val now = Instant.parse("2026-10-24T15:30:00Z")
    private val follows = setOf("c:F1", "t:Manchester United", "c:UFC")
    private val favs = Kind.entries.associateWith<Kind, Person?> { null }

    private fun show(hs: List<Hero>) = hs.joinToString(" | ") { "${it.token} ${it.event.line1()} ${timeText(it.event)} ${dayWord(it.event.start, now)}" }

    @Test fun cardsAreSoonestFirst() {
        val hs = heroes(events, follows, null, Filter.FOLLOWING, favs, now)
        println("FOLLOWING: " + show(hs))
        assertEquals(listOf("c:UFC", "c:F1", "t:Manchester United"), hs.map { it.token })
        assertEquals("19:00", timeText(hs[0].event))
    }

    @Test fun pinnedCardComesFirst() {
        val hs = heroes(events, follows, "t:Manchester United", Filter.FOLLOWING, favs, now)
        println("PINNED: " + show(hs))
        assertEquals("t:Manchester United", hs[0].token)
        assertEquals("17:00", timeText(hs[0].event))
    }

    @Test fun motorsportPillShowsF1() {
        val hs = heroes(events, follows, null, Filter.MOTORSPORT, favs, now)
        assertEquals(listOf("c:F1"), hs.map { it.token })
        val list = listEvents(events, follows, Filter.MOTORSPORT, now, hs[0].event.id)
        println("MOTORSPORT LIST: " + list.take(5).joinToString(" | ") { "${it.line1()} ${timeText(it)} ${dayWord(it.start, now)} ${tonightCue(it, now) ?: ""}" })
        assertTrue(list.all { it.code == "F1" })
    }

    @Test fun rowStates() {
        val early = events.first { it.code == "UFC" && it.session == "Early prelims" }
        assertEquals(Pill.Live, pillFor(early, now))
        val p3 = events.first { it.code == "F1" && it.session == "Practice 3" && it.start.isAfter(now) }
        assertEquals(Pill.In("in 2h"), pillFor(p3, now))
        val tbc = events.first { it.isTbc }
        assertEquals("TBC", timeText(tbc))
        assertEquals(null, pillFor(tbc, now))
    }
}
