package app.sportclock

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The bell on a match seen only on the Scores boards (Kenya v South Africa when Kenya isn't followed) must stay on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoardAlertTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.now()
    private fun game(id: String, title: String, hours: Long, code: String = "ENG") = Event(id, Sport.FOOTBALL, code, "x", title, "Kick-off",
        now.plusSeconds(hours * 3600), 120, "", "confirmed", title.split(" v "), null, null, null)

    @Test fun aBelledBoardMatchKeepsItsAlert() {
        val ours = (1..25).map { game("ENG|$it", "Club $it v Other $it", 24L + it) }
        val board = game("CUP|401912345", "Kenya v South Africa", 3, "ACQ")
        AlertChoices.load(ctx); AlertChoices.on = true
        assertFalse(AlertChoices.wants(board))
        assertTrue(AlertChoices.toggle(ctx, board))
        // what rearming did before: reload the choices and prune against our own events only
        Alerts.reschedule(ctx, ours)
        AlertChoices.load(ctx)
        assertTrue("the pick survived the prune", AlertChoices.wants(board))
        assertEquals(listOf(board.id), AlertChoices.extras.map { it.id })
        // and the bell turns it off again
        assertFalse(AlertChoices.toggle(ctx, board))
        AlertChoices.load(ctx)
        assertFalse(AlertChoices.wants(board)); assertTrue(AlertChoices.extras.isEmpty())
    }
}
