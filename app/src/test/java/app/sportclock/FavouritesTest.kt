package app.sportclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Who leads a card: the NFL player on NFL games, a boxer only on their own fight. */
class FavouritesTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private fun ev(code: String, sport: Sport, title: String, teams: List<String> = emptyList()) =
        Event("$code|$title", sport, code, code, title, "Main event", now, 120, "", "confirmed", teams, null, null, null)
    private val fury = Person(Kind.BOXER, "tyson-fury", "Tyson Fury", "Boxing", 0, 0, .5f, .3f, "Fury")
    private val canelo = Person(Kind.BOXER, "canelo-alvarez", "Canelo Álvarez", "Boxing", 0, 0, .5f, .3f, "Canelo")
    private val price = Person(Kind.BOXER, "lauren-price", "Lauren Price", "Boxing", 0, 0, .5f, .3f)
    private val mahomes = Person(Kind.NFL_PLAYER, "patrick-mahomes", "Patrick Mahomes", "Chiefs", 0, 0, .5f, .3f)
    private val bruno = Person(Kind.PLAYER, "bruno-fernandes", "Bruno Fernandes", "Man Utd", 0, 0, .5f, .3f)

    @Test fun boxersShowOnlyOnTheirOwnFight() {
        assertTrue(billedOn(fury, ev("BOX", Sport.COMBAT, "Fury v Joshua")))
        assertFalse(billedOn(fury, ev("BOX", Sport.COMBAT, "Bivol v Callum Smith")))
        assertTrue(billedOn(canelo, ev("BOX", Sport.COMBAT, "Canelo v Mbilli")))
        assertTrue(billedOn(price, ev("BOX", Sport.COMBAT, "Lauren Price v Kotaskova")))
        val favs = mapOf(Kind.BOXER to fury)
        assertEquals(fury, heroPerson("c:BOX", ev("BOX", Sport.COMBAT, "Fury v Joshua"), favs))
        assertNull(heroPerson("c:BOX", ev("BOX", Sport.COMBAT, "Kabayel v Hysa"), favs))
    }

    @Test fun nflGamesTakeTheNflPlayer() {
        val game = ev("NFL", Sport.NFL, "Buffalo Bills at Kansas City Chiefs", listOf("Kansas City Chiefs", "Buffalo Bills"))
        val favs = mapOf(Kind.NFL_PLAYER to mahomes, Kind.PLAYER to bruno)
        assertEquals(mahomes, heroPerson("t:Kansas City Chiefs", game, favs))
        assertEquals(mahomes, heroPerson("c:NFL", game, favs))
        assertEquals(bruno, heroPerson("t:Manchester United", ev("ENG", Sport.FOOTBALL, "Man Utd v Spurs", listOf("Manchester United", "Tottenham Hotspur")), favs))
    }

    @Test fun nflRostersAreGroupedFootballRostersFlat() {
        val nfl = JSONObject("""{"athletes":[{"position":"offense","items":[{"displayName":"Patrick Mahomes","fullName":"Patrick Mahomes"}]},
            {"position":"defense","items":[{"displayName":"Chris Jones"}]}]}""")
        assertEquals(setOf("patrick mahomes", "chris jones"), rosterNames(nfl))
        val soccer = JSONObject("""{"athletes":[{"displayName":"Bruno Fernandes"},{"displayName":"Kobbie Mainoo"}]}""")
        assertEquals(setOf("bruno fernandes", "kobbie mainoo"), rosterNames(soccer))
        assertEquals(emptySet<String>(), rosterNames(JSONObject("{}")))
    }

    @Test fun everyKindHasAName() {
        assertEquals(listOf("Driver", "Fighter", "Player", "NFL player", "Boxer"), Kind.entries.map { it.role() })
        assertTrue(Kind.BOXER.greyscale && Kind.FIGHTER.greyscale && !Kind.NFL_PLAYER.greyscale)
    }
}
