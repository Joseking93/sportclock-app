package app.sportclock

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL

class HeroLookLiveTest {
    @Test fun todaysGamesGetBadges() = runBlocking {
        assumeTrue(System.getenv("SC_LIVE") == "1")
        val (events, _) = parseEvents(URL("$SITE/data.json").readText())
        val now = java.time.Instant.now()
        events.filter { it.sport == Sport.FOOTBALL && it.start.isAfter(now.minusSeconds(3600)) && it.start.isBefore(now.plusSeconds(86400)) }.take(8).forEach { e ->
            val m = runCatching { Looks.espnMatch(e) }
            val l = runCatching { Looks.load(e) }
            println("${e.id} ${e.code} ${e.teams} ${e.start} -> match ${m.getOrNull()?.let { "${it.home} v ${it.away}" } ?: m.exceptionOrNull()} look ${l.getOrNull()?.let { "${it.home.name}/${it.away.name}" } ?: l.exceptionOrNull()}")
        }
    }
}
