package app.sportclock

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class F1HomeTest {
    private fun vids(name: String) = parseVideos(JSONObject(javaClass.classLoader!!.getResource(name)!!.readText()))

    @Test fun placesMatchAcrossSpellings() {
        assertEquals("sao paulo", gpPlace("São Paulo Grand Prix"))
        assertEquals("las vegas", gpPlace("Las Vegas GP"))
        assertTrue(gpPlace("Race Highlights | 2026 Barcelona-Catalunya Grand Prix").contains(gpPlace("Barcelona Grand Prix")))
    }

    @Test fun watchFindsTheLastRacesHighlights() {
        val vs = vids("yt-saopaulo.json")
        val race = raceHighlights(vs, gpPlaces("Brazilian Grand Prix", "São Paulo"), 2026)!!
        assertEquals("Race Highlights | 2026 Sao Paulo Grand Prix", race.title)
        assertEquals("https://www.youtube.com/watch?v=${race.id}", race.url)
        assertEquals("Qualifying Highlights | 2026 Sao Paulo Grand Prix", qualiHighlights(vs, listOf("sao paulo"), 2026)!!.title)
        assertNull(raceHighlights(vs, listOf("las vegas"), 2026))
        assertNull(raceHighlights(vs, gpPlaces("Brazilian Grand Prix"), 2026))          // the race name alone misses it
    }

    @Test fun thisWeekIsTheWeekendsOtherVideos() {
        val w = weekVideos(vids("yt-saopaulo.json"), gpPlaces("Brazilian Grand Prix", "São Paulo"), 2026)
        assertTrue(w.size >= 3)
        assertTrue(w.any { it.title.startsWith("FP1 Highlights") })
        assertTrue(w.none { it.title.startsWith("Race Highlights") || it.title.contains("2024") || it.title.contains("2025") })
        // before a race weekend the preview counts, though its title has no year; last year's videos don't
        val vegas = weekVideos(vids("yt-vegas.json"), gpPlaces("Las Vegas Grand Prix", "Las Vegas"), 2026)
        assertTrue(vegas.none { it.title.contains("2025") || it.title.contains("2024") || it.title.contains("2023") })
        assertTrue(recentAgo("3 days ago")); assertTrue(recentAgo("5 hours ago")); assertFalse(recentAgo("3 weeks ago")); assertFalse(recentAgo("1 year ago"))
    }

    @Test fun seasonHighlightsAreNewestFirst() {
        val cal = listOf("Australian Grand Prix" to "Melbourne", "Chinese Grand Prix" to "Shanghai", "Miami Grand Prix" to "Miami",
            "United States Grand Prix" to "Austin", "Mexico City Grand Prix" to "Mexico City", "Brazilian Grand Prix" to "São Paulo").map { gpPlaces(it.first, it.second) }
        val s = seasonHighlights(vids("yt-season.json"), 2026, cal)
        assertTrue(s.size >= 15)
        assertTrue(s.all { it.title.startsWith("Race Highlights") && it.title.contains("2026") })
        assertEquals("Race Highlights | 2026 Sao Paulo Grand Prix", s.first().title)
    }

    @Test fun shortsAreMarkedInTheFeed() {
        val f = vids("yt-feed.json")
        assertEquals(15, f.size)
        assertTrue(f.any { it.short } && f.any { !it.short })
        assertTrue(f.filter { it.short }.all { it.url.contains("/shorts/") })
        assertTrue(f.all { it.published != null })
    }

    @Test fun flagsCodesAndColours() {
        assertEquals("🇺🇸", flagOf("USA")); assertEquals("🇧🇷", flagOf("Brazil")); assertEquals("", flagOf("Atlantis"))
        assertEquals("FP1", sessionCode("Practice 1")); assertEquals("SQ", sessionCode("Sprint Qualifying")); assertEquals("R", sessionCode("Race"))
        assertNotEquals(f1TeamColor("Red Bull"), f1TeamColor("RB F1 Team"))
    }
}
