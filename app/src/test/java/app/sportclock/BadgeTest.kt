package app.sportclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.luminance
import org.junit.Test

class BadgeTest {
    private fun dist(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color) =
        Math.sqrt(((a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) + (a.blue - b.blue) * (a.blue - b.blue)).toDouble())

    @Test fun playerNamesMatchAcrossSpellings() {
        assertEquals(personKey("Vinícius Júnior"), personKey("Vinicius Junior"))
        assertEquals(personKey("Virgil van Dijk"), personKey("Virgil Van Dijk"))
        assertEquals(personKey("Martin Ødegaard").isNotEmpty(), true)
        assertNotEquals(personKey("Bruno Fernandes"), personKey("Bruno Guimarães"))
    }

    @Test fun clashingClubColoursAreSeparated() {
        // ESPN's colours on 25 Oct 2026: Man Utd da020e/4169E1, Bournemouth f42727/B57EDC, both red
        val (h, a) = sideColors(TeamSide("360", "Manchester United", "", "da020e", "4169E1"), TeamSide("349", "AFC Bournemouth", "", "f42727", "B57EDC"))
        assertTrue(dist(h, a) >= 0.3)
        // Leeds is white first: too light for a backdrop, so its second colour is used
        val (l, _) = sideColors(TeamSide("357", "Leeds United", "", "ffffff", "FFCD00"), TeamSide("363", "Chelsea", "", "144992", "FFFFFF"))
        assertTrue(l.luminance() < 0.8f)
    }
}
