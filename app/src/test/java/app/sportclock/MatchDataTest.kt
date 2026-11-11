package app.sportclock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MatchDataTest {
    private fun load(name: String) = parseMatch(JSONObject(javaClass.classLoader!!.getResource(name)!!.readText()))

    @Test fun headerTimelineAndScorers() {
        val d = load("summary-ars-hul.json")
        assertEquals("Arsenal", d.home.name); assertEquals("Hull City", d.away.name)
        assertEquals(1, d.home.score); assertEquals(0, d.away.score); assertEquals("post", d.state)
        assertEquals(MpEvent("", "ft", false, score = "1 - 0"), d.events.first())                    // newest first
        assertTrue(d.events.contains(MpEvent("", "ht", false, score = "0 - 0")))
        val goal = d.events.single { it.kind in GOALS }
        assertEquals("72'", goal.minute); assertTrue(goal.home); assertEquals("1 - 0", goal.score)
        assertEquals("Ødegaard 72'", d.scorers(true)); assertEquals("", d.scorers(false))
        assertEquals(5, d.events.count { it.kind == "yellow" && !it.home })
        val sub = d.events.first { it.kind == "sub" && it.minute == "63'" && it.player == "Mikel Merino" }
        assertEquals("Declan Rice", sub.other)
    }

    @Test fun lineupsSitInTheirFormations() {
        val d = load("summary-ars-hul.json")
        val h = d.homeSide!!; val a = d.awaySide!!
        assertEquals(listOf(1, 4, 2, 3, 1), h.lines.map { it.size })
        assertEquals(listOf("Calafiori", "Magalhães", "Konsa", "Timber"), h.lines[1].map { surname(it.name) })   // left back to right back
        assertEquals(listOf("Tzolis", "Ødegaard", "Madueke"), h.lines[3].map { surname(it.name) })
        assertEquals(listOf(1, 5, 3, 2), a.lines.map { it.size })
        assertEquals(listOf("Stroud", "Mendy", "Egan", "Ajayi", "Coyle"), a.lines[1].map { surname(it.name) })
        val odegaard = h.lines.flatten().single { it.name.endsWith("degaard") }
        assertEquals(1, odegaard.goals)
        assertEquals("63'", h.lines.flatten().single { it.name == "Kai Havertz" }.offAt)
        assertTrue(a.lines.flatten().single { it.name == "Lewie Coyle" }.yellow)
        assertEquals("63'", h.bench.first { it.name.startsWith("Viktor") }.onAt)
        assertNotNull(h.bench.first().onAt)                                                               // those who came on first
    }

    @Test fun statsCommentaryTableAndHeadToHead() {
        val d = load("summary-ars-hul.json")
        assertEquals(66.9, d.possession!!.first, .01)
        val shots = d.stats.first { it.label == "Total shots" }
        assertEquals("21", shots.home); assertEquals("7", shots.away)
        assertEquals("490 (86%)", d.stats.first { it.label == "Accurate passes" }.home)
        assertTrue(d.stats.first { it.label == "Fouls" }.lowerBetter)
        assertTrue(d.commentary.first().text.startsWith("Match ends"))
        assertEquals("goal", d.commentary.first { it.text.startsWith("Goal!") }.kind)
        assertEquals(20, d.table.size); assertEquals("Arsenal", d.table.first().team)
        assertEquals(5, d.meetings.size); assertEquals(5, d.homeForm.size)
        assertEquals("Emirates Stadium", d.venue); assertEquals(60285, d.attendance)
    }

    @Test fun anotherMatchParsesToo() {
        val d = load("summary-avl-mci.json")
        assertTrue(d.home.name.isNotEmpty() && d.away.name.isNotEmpty())
        d.homeSide?.let { s -> assertEquals(11, s.lines.flatten().size) }
        d.awaySide?.let { s -> assertEquals(11, s.lines.flatten().size) }
        assertEquals(d.home.score ?: 0, d.events.count { it.kind in GOALS && it.home })
        assertEquals(d.away.score ?: 0, d.events.count { it.kind in GOALS && !it.home })
    }

    @Test fun formationFallsBackToDepth() {
        val ps = listOf("G", "CD-L", "CD-R", "LB", "RB", "CM", "CM-L", "CM-R", "F", "CF-L", "CF-R").mapIndexed { i, p -> MpPlayer("P$i", "$i", p) }
        assertEquals(listOf(1, 4, 3, 3), formationLines("", ps).map { it.size })
        assertEquals(listOf("LB", "CD-L", "CD-R", "RB"), formationLines("4-3-3", ps)[1].map { it.pos })
    }

    @Test fun clashingKitsGetTellApart() {
        val red = MpTeam("A", "", 0xFFE20520, 0xFF003399, null)
        val alsoRed = MpTeam("B", "", 0xFFDA291C, 0xFFFBE122, null)
        val (h, a) = teamColors(red, alsoRed)
        assertNotEquals(h, a)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFBE122), a)
        val black = MpTeam("C", "", 0xFF000000, 0xFFFFFFFF, null)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE20520), teamColors(red, black).first)
    }
}
