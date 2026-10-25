package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** ESPN's NFL week 9 of the 2026 season, saved as a test resource. */
class NflTest {
    private val games = Nfl.parse(javaClass.getResource("/nfl-week9.json")!!.readText())

    @Test fun readsTheWeek() {
        assertEquals(15, games.size)
        val first = games.first()
        assertEquals("NFL|401873037", first.id)
        assertEquals(Instant.parse("2026-11-06T01:15:00Z"), first.start)
        assertEquals(listOf("Baltimore Ravens", "Jacksonville Jaguars"), first.teams)     // home first, like football
        assertEquals("Jaguars @ Ravens", first.line1())
        assertEquals("NFL · Week 9", first.line2())
        assertTrue(Nfl.sides[first.id]!!.first.logo.startsWith("https://"))
    }

    @Test fun followsAndAlerts() {
        assertEquals(Sport.NFL, tokenSport("t:Kansas City Chiefs"))
        assertEquals(Sport.FOOTBALL, tokenSport("t:Manchester United"))
        assertEquals("Chiefs", short("Kansas City Chiefs"))
        assertTrue(quietByDefault("c:NFL"))                       // a whole NFL week is 16 games: picked only, like a football league
        val game = games.first()
        assertTrue(followed(game, setOf("t:Baltimore Ravens")) && followed(game, setOf("c:NFL")))
    }
}
