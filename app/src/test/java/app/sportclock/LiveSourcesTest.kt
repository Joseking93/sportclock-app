package app.sportclock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real free sources; runs only with SC_LIVE=1 so normal builds stay offline. */
class LiveSourcesTest {
    private val live = System.getenv("SC_LIVE") == "1"
    private val events by lazy { parseEvents(File("../../sportclock/data.json").readText()).first }

    @Test fun racingWeekends() = runBlocking {
        assumeTrue(live)
        for (code in listOf("F1", "INDY", "NAS")) {
            val w = espnRacing(code)!!
            println("$code: ${w.name} · " + w.sessions.joinToString { "${it.name}(${it.state}, ${it.rows?.size}) ${it.rows?.firstOrNull()?.let { r -> "${r.name} ${r.time}" } ?: ""}" })
            assertTrue(w.sessions.isNotEmpty())
        }
        val m = motoGp()!!
        println("MotoGP: ${m.name} · " + m.sessions.joinToString { "${it.name}(${it.state})" })
        val done = m.sessions.last { it.state == "post" }
        val rows = motoGpRows(done.id!!)
        println("  ${done.name}: " + rows.take(3).joinToString { "${it.pos} ${it.name} ${it.time}" })
        assertTrue(rows.isNotEmpty())
    }

    @Test fun ufc() = runBlocking {
        assumeTrue(live)
        val c = ufcCard()!!
        println("UFC: ${c.name} · ${c.bouts.size} bouts")
        c.bouts.take(3).forEach { println("  ${it.a} (${it.aRecord}) v ${it.b} (${it.bRecord}) · ${it.weight} · winner ${it.winner} · ${it.method} R${it.round} ${it.clock}") }
        assertTrue(c.bouts.isNotEmpty())
    }

    @Test fun teamAndExtras() = runBlocking {
        assumeTrue(live)
        val lg = leagueFor("Manchester United", events)!!
        val id = espnTeamId(lg, "Manchester United")!!
        val t = teamInfo(lg, id)
        println("Team: ${t.name} · ${t.standing} · form ${t.form()} · ${t.results.size} results, ${t.fixtures.size} fixtures, next ${t.fixtures.firstOrNull()?.let { "${it.home} v ${it.away}" }}")
        assertTrue(t.fixtures.isNotEmpty())
        val e = events.first { it.teams.contains("Manchester United") && it.code == "ENG" && it.start.isAfter(java.time.Instant.parse("2026-10-25T00:00:00Z")) }
        val x = matchExtras(e)!!
        println("Extras ${e.title}: ${x.homeName} ${x.homeForm.map { it.result }} · ${x.awayName} ${x.awayForm.map { it.result }} · H2H ${x.series} ${x.meetings.take(2).map { "${it.home} ${it.homeScore}-${it.awayScore} ${it.away}" }}")
        assertTrue(x.homeForm.isNotEmpty())
    }

    @Test fun f1LiveTiming() = runBlocking {
        assumeTrue(live)
        val f = f1Snapshot()
        println("F1: ${f.meeting} · ${f.session} (${f.type}) · start ${f.start} · status ${f.status} · ${trackWord(f.track)} · lap ${f.lap}/${f.totalLaps}")
        println("  top: " + f.lines.take(4).joinToString { "${it.pos} ${f.tla(it.number)} ${it.gap} ${it.tyre} pits=${it.pits}" })
        println("  ${f.drivers.size} drivers, ${f.messages.size} messages, flagged now ${flaggedSectors(f.messages)}, weather ${f.weather}")
        assertTrue(f.lines.size >= 20 && f.messages.isNotEmpty())
        val tmp = kotlin.io.path.createTempDirectory().toFile()
        val map = circuitMap(tmp, f.circuitKey!!, f.year)
        println("  map ${map.name}: ${map.x.size} points, ${map.corners.size} corners, ${map.sectors.size} marshal sectors")
        assertTrue(map.x.size > 100)
        val (race, stops) = jolpicaPitStops(); println("  pit stops: $race ${stops.size}, first ${stops.firstOrNull()}")
        val pdf = fiaUpgradesPdf(tmp, f.meeting, f.year); println("  upgrades PDF ${fiaUpgradesUrl(f.meeting, f.year)} -> ${pdf.length()} bytes")
        assertTrue(pdf.length() > 10_000)
    }

    @Test fun f1Weekend() = runBlocking {
        assumeTrue(live)
        val race = events.first { it.code == "F1" && it.session == "Race" && it.title == "United States Grand Prix" }
        val g = gpInfo(race)!!
        println("GP: round ${g.round} ${g.circuit} ${g.locality} ${g.lat},${g.lon} ${g.wiki}")
        println("Last year: ${lastYear(g)}")
        println("Circuit key: ${circuitKey(race)}")
        val res = weekendResults(race, g)
        println("Results: " + res.joinToString { "${it.name}(${it.state}) ${it.rows?.firstOrNull()?.name ?: ""}" })
        val weekend = events.filter { it.code == "F1" && it.title == race.title }.map { it.start }
        println("Forecast: " + forecast(g.lat, g.lon, weekend))
        val later = events.first { it.code == "F1" && it.session == "Race" && it.start.isAfter(race.start.plusSeconds(86400 * 5)) }
        println("Next GP: ${later.title} -> ${gpInfo(later)?.circuit}, key ${circuitKey(later)}, last year ${gpInfo(later)?.let { lastYear(it) }}")
        assertTrue(g.round == 18 && res.isNotEmpty())
    }

    @Test fun f1Season() = runBlocking {
        assumeTrue(live)
        val l = season(2026)
        println("Season: ${l.size} races; " + l.take(3).joinToString { "${it.round} ${it.name} ${it.winner}" } + " ... " + l.last().let { "${it.round} ${it.name} ${it.winner}" })
        println("Current GP: " + currentGp(events, java.time.Instant.parse("2026-10-25T12:00:00Z"))?.let { "${it.title} ${it.start}" })
        assertTrue(l.size > 20 && l.count { it.winner != null } >= 15)
    }

    @Test fun nflTeamAndStandings() = runBlocking {
        assumeTrue(live)
        val lg = leagueFor("Kansas City Chiefs", events)!!
        val t = teamInfo(lg, espnTeamId(lg, "Kansas City Chiefs")!!)
        println("NFL team: ${t.name} · ${t.standing} · form ${t.form()} · ${t.results.size} results, ${t.fixtures.size} fixtures, next ${t.fixtures.firstOrNull()?.let { "${it.away} at ${it.home}" }}")
        val st = Nfl.standings(); println("Standings: ${st.size} divisions, ${st.first().first}: " + st.first().second.joinToString { "${short(it.team)} ${it.wins}-${it.losses}" })
        assertTrue(t.results.isNotEmpty() && t.fixtures.isNotEmpty() && st.size == 8)
    }

    @Test fun f1RaceWeek() = runBlocking {
        assumeTrue(live)
        val fia = fiaDocs()!!
        println("FIA: ${fia.name} · ${fia.docs.size} docs; stewards: " + fia.docs.filter { it.stewards }.take(4).joinToString(" | ") { "${it.number} ${it.title} ${it.at}" })
        assertTrue(fia.docs.size > 10 && fia.docs.all { it.url.endsWith(".pdf") })
        val t = titleFight()!!
        println("Title (${t.asOf}): ${t.leader.name} ${t.leader.points}; " + t.rest.take(3).joinToString { "${it.name} -${it.behind}${if (it.alive) "" else " (out)"}" } +
            " · ${t.racesLeft} races, ${t.sprintsLeft} sprints, ${t.available} pts left · ${t.clinchNote}")
        assertTrue(t.available > 0)
    }
    @Test fun cupRunsForBigClubs() = runBlocking {
        assumeTrue(live)
        val now = java.time.Instant.now()
        for (team in listOf("Manchester United", "Arsenal", "FC Bayern München", "Real Madrid CF", "FC Internazionale Milano")) {
            val name = events.flatMap { it.teams }.firstOrNull { it == team } ?: events.flatMap { it.teams }.distinct().first { sameTeam(it, team) }
            val code = events.filter { name in it.teams }.map { it.code }.first { it != "UCL" && it in ESPN_LEAGUE }
            val id = espnTeamId("soccer/" + ESPN_LEAGUE.getValue(code), name)!!
            val ties = cupTies(code, id, now)
            println("$name ($code, $id): " + cupRuns(code, id, ties, now).joinToString(" | ") { "${it.cup.name}: ${it.line}" })
        }
    }
}
