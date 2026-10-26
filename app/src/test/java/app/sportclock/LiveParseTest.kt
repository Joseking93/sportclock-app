package app.sportclock

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Goal texts from ESPN's real feed for Aston Villa v Man City, 24 Oct 2026, saved as a test resource. */
class LiveParseTest {
    private val o = JSONObject(javaClass.getResource("/summary-avl-mci.json")!!.readText())
    private val goals = o.getJSONArray("keyEvents").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.optBoolean("scoringPlay") }

    @Test fun everyGoalHasAScorerAndAScore() {
        assertTrue(goals.isNotEmpty())
        goals.forEach { g ->
            val text = g.optString("text")
            val at = Regex("""(\d+), [^.]*? (\d+)\.""").find(text)?.groupValues
            println("${g.optJSONObject("clock")?.optString("displayValue")} ${Live.scorer(text)} -> ${at?.get(1)}–${at?.get(2)}")
            assertTrue(Live.scorer(text).isNotBlank() && Live.scorer(text).length < 40)
            assertTrue(at != null)
        }
    }

    @Test fun scorerWording() {
        assertEquals("Nicolas Jackson", Live.scorer("Goal! Aston Villa 1, Manchester City 0. Nicolas Jackson (Aston Villa) right footed shot from the centre of the box."))
        assertEquals("Marc Guehi (own goal)", Live.scorer("Own Goal by Marc Guehi, Manchester City.  Aston Villa 1, Manchester City 0."))
    }

    @Test fun matchSheetIncludesGoalsCardsAndSubstitutions() {
        val sheet = parseMatchSheet(o)
        assertTrue(sheet.home != null && sheet.away != null)
        assertTrue(sheet.incidents.any { it.type.contains("Goal") && it.text == "Nicolas Jackson" })
        assertTrue(sheet.incidents.any { it.type == "Yellow Card" })
        assertTrue(sheet.incidents.any { it.type == "Substitution" })
    }
}
