package app.sportclock

import java.time.Duration
import java.time.Instant

/*
 * Fight nights: when the main event actually walks out. A card's listed time is when the first fight of its segment
 * starts; the main event comes last, often after 5am in Kenya, so the useful alert is the walkout, not the card start.
 * UFC: the main card's start plus about 28 minutes for each fight before the main event (walkouts, the fight itself,
 * the decision), with the number of fights from ESPN's card when it lists this one, else five. Boxing: about 55
 * minutes before the end of a "Card start" listing. Both are estimates, shown with "~" and "Estimated".
 */

const val BOUT_MINS = 28L
const val WALK_PREFIX = "WALK|"

/** The walkout estimates for these events' fight cards, as events of their own ("Main event walkout", approx). */
fun walkouts(events: List<Event>, ufcMain: Pair<Instant, Int>?): List<Event> = events.mapNotNull { e ->
    when {
        e.id.startsWith(WALK_PREFIX) || e.isTbc -> null
        e.code == "UFC" && sessName(e.session) == "Main card" -> {
            val n = ufcMain?.takeIf { Math.abs(Duration.between(it.first, e.start).toMinutes()) <= 180 }?.second ?: 5
            walk(e, e.start.plusSeconds((n - 1).coerceAtLeast(0) * BOUT_MINS * 60))
        }
        e.code == "BOX" && e.session == "Card start" && e.mins >= 120 -> walk(e, e.end.minusSeconds(55 * 60))
        else -> null
    }
}

private fun walk(e: Event, at: Instant) = e.copy(id = WALK_PREFIX + e.id, session = "Main event walkout", start = round5(at), mins = 30,
    status = "approx", window = null, fdid = null, movedFrom = null)

private fun round5(i: Instant): Instant = Instant.ofEpochSecond(Math.round(i.epochSecond / 300.0) * 300)

fun isWalkout(e: Event) = e.id.startsWith(WALK_PREFIX)

/** The current UFC card's main-card start and number of fights, from ESPN, kept for 30 minutes. */
object UfcCardSize {
    private var at = 0L; private var v: Pair<Instant, Int>? = null
    suspend fun get(): Pair<Instant, Int>? {
        if (System.currentTimeMillis() - at < 30 * 60_000) return v
        v = runCatching { ufcMainCard() }.getOrNull() ?: v
        at = System.currentTimeMillis()
        return v
    }
}
