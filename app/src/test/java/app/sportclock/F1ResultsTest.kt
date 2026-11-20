package app.sportclock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The Results page's sums, checked against the 2026 season as Jolpica had it on 20 Nov (after round 20, São Paulo). */
class F1ResultsTest {
    private fun j(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())
    private val season by lazy {
        val races = parseRaces((0..400 step 100).map { j("j-results-$it.json") })
        val sprints = parseRaces(listOf(j("j-sprint-0.json"), j("j-sprint-100.json")), "SprintResults").associate { it.round to it.rows }
        val poles = parseRaces(listOf(j("j-poles.json")), "QualifyingResults").mapNotNull { r -> r.rows.firstOrNull()?.let { r.round to it } }.toMap()
        val (after, drivers) = parseDriverStandings(j("j-drivers.json"))
        val faces = parseFaces(JSONArray(javaClass.classLoader!!.getResource("of1-drivers-latest.json")!!.readText()))
        F1Season(2026, races, sprints, poles, drivers, parseTeamStandings(j("j-teams.json")), faces, after)
    }

    @Test fun pagesMergeIntoWholeRaces() {
        assertEquals(20, season.races.size)
        assertEquals((1..20).toList(), season.races.map { it.round })
        assertTrue(season.races.all { it.rows.size == 22 })             // no race split across Jolpica's pages loses a driver
        val br = season.races.last()
        assertEquals("Brazilian Grand Prix", br.name); assertEquals(71, br.laps)
        assertEquals(listOf("Norris", "Verstappen", "Hadjar"), br.rows.take(3).map { it.family })
        assertEquals("1:35:23.504", br.rows[0].time); assertEquals("+14.602", br.rows[1].time)
        assertEquals(6, season.sprints.size)                                     // China, Miami, Canada, Britain, Netherlands, Singapore
        assertEquals(20, season.afterRound)
    }

    @Test fun formMatchesTheRedesign() {
        // the reel's own numbers, last five rounds (AZE, SIN, USA, MEX, BRA)
        fun f(family: String) = form(season.drivers.first { it.family == family }.id, season.races).map { it.row?.posText }
        assertEquals(listOf("3", "6", "4", "2", "9"), f("Hamilton"))
        assertEquals(listOf("2", "3", "1", "3", "5"), f("Antonelli"))
        assertEquals(listOf("AZE", "BHR", "SIN", "USA", "MEX", "BRA").takeLast(5), season.races.takeLast(5).map(::raceCode).takeLast(5).let { it })
    }

    @Test fun standingsAndTeams() {
        val d = season.drivers
        assertEquals("Antonelli", d[0].family); assertEquals(390.0, d[0].points, 0.0)
        assertEquals("Hamilton", d[3].family); assertEquals(261.0, d[3].points, 0.0)
        assertEquals("Mercedes", season.teams[0].name); assertEquals(652.0, season.teams[0].points, 0.0)
        // Ferrari's last five Grands Prix, as the reel's Teams tab shows them (sprints count in the total only)
        val fer = season.teams.first { it.name == "Ferrari" }.name
        assertEquals(listOf(27.0, 26.0, 30.0, 30.0, 6.0), season.races.takeLast(5).map { teamRoundPoints(fer, it.round, season) })
    }

    @Test fun ferrariHeadToHead() {
        val lec = season.drivers.first { it.family == "Leclerc" }; val ham = season.drivers.first { it.family == "Hamilton" }
        val h = headToHead(lec, ham, season.races)
        assertEquals(20, h.together)
        assertEquals(10, h.raceA); assertEquals(10, h.raceB)            // the reel: 10 v 10
        assertEquals(1, h.winsA); assertEquals(1, h.winsB)
        assertEquals(6, h.podiumsA); assertEquals(7, h.podiumsB)
        assertEquals(17, h.pointsFinA); assertEquals(19, h.pointsFinB)
        assertEquals(1, h.bestA); assertEquals(1, h.bestB)
        assertEquals(3, h.retA); assertEquals(1, h.retB)
    }

    @Test fun awardsAndFaces() {
        val a = awards(season)
        assertTrue(a.any { it.title == "Most wins" } && a.any { it.title == "Pole positions" } && a.any { it.title == "Fastest laps" })
        assertTrue(a.all { it.leaders.size in 1..3 })
        assertEquals(20, season.poles.size)
        assertTrue(season.faces["NOR"]!!.startsWith("https://media.formula1.com/"))
    }
}
