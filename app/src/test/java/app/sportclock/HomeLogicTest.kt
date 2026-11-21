package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** 2.34: the card through a match, the week strip, league pages, walkouts, alert kinds and win chances. */
class HomeLogicTest {
    init { TimeZone.setDefault(TimeZone.getTimeZone("Africa/Nairobi")) }
    private val events = parseEvents(File("../../sportclock/data.json").readText()).first
    private val favs = Kind.entries.associateWith<Kind, Person?> { null }
    private val follows = setOf("c:F1", "t:Manchester United", "c:UFC")
    private fun ev(code: String, title: String, session: String, start: String, mins: Long = 120, teams: List<String> = emptyList(), status: String = "confirmed") =
        Event("$code|$title|$session|$start", if (code == "UFC" || code == "BOX") Sport.COMBAT else if (teams.isEmpty()) Sport.MOTORSPORT else Sport.FOOTBALL,
            code, code, title, session, Instant.parse(start), mins, "", status, teams, null, null, null)

    @Test fun cardStaysWithTheMatch() {
        val lvMu = events.first { it.teams == listOf("Liverpool", "Manchester United") }
        // 30 minutes in: Home keeps United's card on the match, the widget (no follow) moves on to the next one
        val live = lvMu.start.plusSeconds(30 * 60)
        val home = heroes(events, follows, null, Filter.FOLLOWING, favs, live, follow = true)
        assertEquals(lvMu.id, home.first { it.token == "t:Manchester United" }.event.id)
        assertEquals(HeroState.LIVE, heroState(lvMu, live, Score("IN_PLAY", 1, 0, null, "30'")))
        val widget = heroes(events, follows, null, Filter.FOLLOWING, favs, live)
        assertTrue(widget.first { it.token == "t:Manchester United" }.event.id != lvMu.id)
        // the live card comes before everything still to come
        assertEquals("t:Manchester United", home.first().token)
        // ESPN says full time before our listed end: the card shows the result
        assertEquals(HeroState.DONE, heroState(lvMu, lvMu.start.plusSeconds(110 * 60), Score("FINISHED", 1, 2, null, "FT")))
        // an hour after the end it's still there with the result; four hours after, it has moved on
        val after = lvMu.end.plusSeconds(3600)
        assertEquals(lvMu.id, heroes(events, follows, null, Filter.FOLLOWING, favs, after, follow = true).first { it.token == "t:Manchester United" }.event.id)
        assertEquals(HeroState.DONE, heroState(lvMu, after, null))
        val later = lvMu.end.plusSeconds(4 * 3600)
        assertTrue(heroes(events, follows, null, Filter.FOLLOWING, favs, later, follow = true).first { it.token == "t:Manchester United" }.event.id != lvMu.id)
        assertEquals(HeroState.NEXT, heroState(lvMu, lvMu.start.minusSeconds(60), null))
    }

    @Test fun ufcPrelimsOnNowKeepTheCard() {
        val now = Instant.parse("2026-10-24T15:30:00Z")              // UFC 333's early prelims are on
        val ufc = heroes(events, follows, null, Filter.FOLLOWING, favs, now, follow = true).first { it.token == "c:UFC" }
        assertEquals("Early prelims", ufc.event.session)
        assertEquals(HeroState.LIVE, heroState(ufc.event, now, null))
    }

    @Test fun weekStripJumpsToTheDay() {
        val list = listOf(ev("F1", "A", "Race", "2026-11-21T12:00:00Z"), ev("F1", "B", "Race", "2026-11-21T15:00:00Z"), ev("UFC", "C", "Main card", "2026-11-23T18:00:00Z"))
        assertEquals(0, dayGroupIndex(list, LocalDate.of(2026, 11, 21)))
        assertEquals(3, dayGroupIndex(list, LocalDate.of(2026, 11, 23)))            // heading, two events, then the next heading
        assertNull(dayGroupIndex(list, LocalDate.of(2026, 11, 22)))
    }

    @Test fun walkoutEstimates() {
        val ufc = ev("UFC", "Volkanovski v Evloev", "Main card", "2026-10-24T18:00:00Z", 180)
        val box = ev("BOX", "Dalton Smith v Puello", "Card start", "2026-10-24T18:00:00Z", 255)
        val w = walkouts(listOf(ufc, box, ev("UFC", "X", "Prelims", "2026-10-24T16:00:00Z")), null)
        assertEquals(2, w.size)
        // five fights: four before the main event at 28 minutes each, rounded to five minutes
        assertEquals(Instant.parse("2026-10-24T19:50:00Z"), w[0].start)
        assertEquals("Main event walkout", w[0].session); assertTrue(w[0].isApprox && isWalkout(w[0]))
        // ESPN lists six on the main card: one more fight first
        assertEquals(Instant.parse("2026-10-24T20:20:00Z"), walkouts(listOf(ufc), Instant.parse("2026-10-24T18:00:00Z") to 6)[0].start)
        // boxing: 55 minutes before the listing ends (22:15)
        assertEquals(Instant.parse("2026-10-24T21:20:00Z"), w[1].start)
        // never a walkout of a walkout, and none for a card with no time yet
        assertTrue(walkouts(w, null).isEmpty())
        assertTrue(walkouts(listOf(ufc.copy(status = "tbc")), null).isEmpty())
        // the real file: every UFC main card gets one
        assertEquals(events.count { it.code == "UFC" && sessName(it.session) == "Main card" && !it.isTbc }, walkouts(events, null).count { it.code == "UFC" })
    }

    @Test fun alertKindsPerFollow() {
        val m = ev("ESP", "Barcelona v Real Madrid", "Kick-off", "2026-11-21T20:00:00Z", 115, listOf("FC Barcelona", "Real Madrid CF"))
        val mu = ev("ENG", "Liverpool v Manchester United", "Kick-off", "2026-11-22T16:30:00Z", 115, listOf("Liverpool", "Manchester United"))
        val f = setOf("t:Manchester United", "c:ESP", "c:ENG")
        // clubs everything, leagues nothing by default
        assertEquals(LiveKind.entries.toSet(), liveKindsFor(mu, f, emptyMap(), false))
        assertTrue(liveKindsFor(m, f, emptyMap(), false).isEmpty())
        // La Liga only full time; United just goals
        val stored = mapOf("c:ESP" to setOf(LiveKind.FT), "t:Manchester United" to setOf(LiveKind.GOALS))
        assertEquals(setOf(LiveKind.FT), liveKindsFor(m, f, stored, false))
        assertEquals(setOf(LiveKind.GOALS), liveKindsFor(mu, f, stored, false))
        // a belled match with nothing chosen gets everything
        assertEquals(LiveKind.entries.toSet(), liveKindsFor(m, f, emptyMap(), true))
        // stored and read back
        val raw = kindsToRaw(stored + ("c:ENG" to emptySet()))
        assertEquals(stored + ("c:ENG" to emptySet()), parseKinds(raw))
    }

    @Test fun winChanceFromPrices() {
        assertEquals(Triple(69, 18, 13), impliedChances("-270", "+425", "+650"))
        assertEquals(Triple(50, 0, 50), impliedChances("EVEN", "", "EVEN"))
        assertNull(impliedChances("", "+300", "+200"))
    }

    @Test fun leaguePageBits() {
        val clubs = listOf("Arsenal", "Manchester City", "Chelsea", "Liverpool", "Aston Villa", "Tottenham Hotspur", "Newcastle United", "Brighton & Hove Albion",
            "Brentford", "Fulham", "Manchester United", "Everton", "West Ham United", "AFC Bournemouth", "Crystal Palace", "Nottingham Forest", "Wolverhampton Wanderers",
            "Leeds United", "Burnley", "Sunderland")
        val rows = clubs.mapIndexed { i, c -> TableRow(i + 1, c, "12", "0", "${40 - i}") }
        // United are 11th: the top four, then 10th to 12th; Man City (2nd) is not taken for United
        assertEquals(listOf(1, 2, 3, 4, 10, 11, 12), tableSnippet(rows, setOf("t:Manchester United")).map { it.rank })
        assertEquals(listOf(1, 2, 3, 4), tableSnippet(rows, setOf("t:Chelsea FC")).map { it.rank })
        assertTrue(sameClubStrict("Real Madrid CF", "Real Madrid") && sameClubStrict("Bournemouth", "AFC Bournemouth"))
        assertTrue(!sameClubStrict("Manchester United", "Manchester City") && !sameClubStrict("Newcastle United", "West Ham United"))
        assertEquals("3rd · Premier League", shortStanding("3rd in English Premier League"))
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val round = leagueRound(events, "ENG", now)
        assertTrue(round.isNotEmpty() && round.all { it.code == "ENG" })
        assertTrue(round.last().start.isBefore(round.first().start.plusSeconds(4 * 86400 + 1)))
        assertTrue(kenyaTv("ENG")!!.contains("SuperSport"))
    }

    @Test fun lineupLineSaysWhoStarts() {
        val e = ev("ENG", "Liverpool v Manchester United", "Kick-off", "2026-11-22T16:30:00Z", 115, listOf("Liverpool", "Manchester United"))
        val mu = TeamSheet("Manchester United", "4-2-3-1", listOf(Player("Bruno Fernandes", "8", "AM")), listOf(Player("Kobbie Mainoo", "37", "CM")))
        val lfc = TeamSheet("Liverpool", "4-3-3", listOf(Player("Mohamed Salah", "11", "RW")), emptyList())
        val bruno = person("bruno-fernandes")
        assertEquals("4-2-3-1 · Fernandes starts", lineupLine(MatchSheet(lfc, mu, emptyList()), e, "t:Manchester United", bruno))
        assertEquals("Both elevens are out · tap to see", lineupLine(MatchSheet(lfc, mu, emptyList()), e, "c:ENG", bruno))
    }
}
