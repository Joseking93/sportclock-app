package app.sportclock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsAsNumbers() {
        assertTrue(Updater.newer("2.2", "2.1"))
        assertTrue(Updater.newer("2.10", "2.9"))
        assertTrue(Updater.newer("2.1.1", "2.1"))
        assertTrue(Updater.newer("3.0", "2.0.3"))
        assertFalse(Updater.newer("2.1", "2.1"))
        assertFalse(Updater.newer("2.0.3", "2.1"))
        assertFalse(Updater.newer("2.1", "2.1.0"))
    }
}
