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
}
