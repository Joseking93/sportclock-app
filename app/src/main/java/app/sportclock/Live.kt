package app.sportclock

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.glance.appwidget.updateAll
import app.sportclock.widget.SportClockWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant

/**
 * Live match alerts for your team's matches and any match you belled: lineups when they're out, goals and red cards,
 * half-time and full-time, plus a quiet notification that keeps the live score. Nothing runs between matches: an exact
 * alarm wakes the app 70 minutes before kick-off, it checks ESPN every 10 minutes until the lineups are out and every
 * 2.5 minutes while the match is on, and stops at full time. (Android may stretch this to about 9 minutes while the
 * phone sleeps.) Spoiler-free mode keeps the scores out of notifications; lineups still come.
 */
object Live {
    private const val TICK = 7002
    private const val GOALS = "goals"; private const val LINEUPS = "lineups"; private const val SCORE = "live"
    private const val BEFORE = 70L * 60; private const val AFTER = 180L * 60

    fun watched(e: Event, follows: Set<String>) = e.sport == Sport.FOOTBALL && e.teams.size == 2 && !e.isTbc && ESPN_LEAGUE.containsKey(e.code) &&
        (e.teams.any { "t:$it" in follows } || AlertChoices.wants(e))
    private fun inWindow(e: Event, now: Instant) = !now.isBefore(e.start.minusSeconds(BEFORE)) && !now.isAfter(e.start.plusSeconds(AFTER))
    private fun wanted(ctx: Context) = Settings.alertsOn(ctx) && (Settings.liveAlerts(ctx) || Settings.lineupAlerts(ctx))

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(ctx, TICK, Intent(ctx, LiveTick::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Arm the next wake-up: now if a watched match is in its window, else 70 minutes before the next one. */
    fun schedule(ctx: Context, events: List<Event>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (!wanted(ctx)) { am.cancel(pending(ctx)); return }
        AlertChoices.load(ctx)
        val now = Instant.now(); val st = state(ctx)
        val next = events.filter { watched(it, AlertChoices.follows) && it.start.plusSeconds(AFTER).isAfter(now) && it.start.isBefore(now.plusSeconds(8 * 86400)) }
            .filter { st.optJSONObject(alertKey(it))?.optBoolean("ft") != true }          // a finished match needs no more looks
            .minOfOrNull { it.start.minusSeconds(BEFORE) } ?: run { am.cancel(pending(ctx)); return }
        Alerts.setExact(am, (if (next.isAfter(now)) next else now.plusSeconds(20)).toEpochMilli(), pending(ctx))
    }

    suspend fun tick(ctx: Context) {
        val data = Repo.load(ctx, maxAgeMin = 360) ?: return
        if (!wanted(ctx)) return
        AlertChoices.load(ctx)
        val now = Instant.now()
        val st = state(ctx)
        val active = data.events.filter { watched(it, AlertChoices.follows) && inWindow(it, now) }
        var live = false; var waiting = false
        for (e in active) {
            when (runCatching { check(ctx, e, st) }.onFailure { Diag.log("live ${e.title}: $it") }.getOrNull()) {
                "in" -> live = true
                "post" -> {}
                else -> waiting = true                       // before kick-off, or ESPN didn't answer: look again
            }
        }
        save(ctx, st, now)
        runCatching { SportClockWidget().updateAll(ctx) }
        val am = ctx.getSystemService(AlarmManager::class.java)
        when {
            live -> Alerts.setExact(am, now.plusSeconds(150).toEpochMilli(), pending(ctx))
            waiting -> Alerts.setExact(am, now.plusSeconds(600).toEpochMilli(), pending(ctx))
            else -> schedule(ctx, data.events)
        }
    }

    /** One look at one match; returns ESPN's state (pre, in, post) and posts whatever is new since the last look. */
    private suspend fun check(ctx: Context, e: Event, st: JSONObject): String? {
        val key = alertKey(e)
        val s = st.optJSONObject(key) ?: JSONObject().also { st.put(key, it) }
        if (s.optBoolean("ft")) return "post"
        val m = Looks.espnMatch(e) ?: return null
        val o = espnSummary(m.league, m.id)
        val comp = o.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val type = comp.optJSONObject("status")?.optJSONObject("type")
        val state = type?.optString("state").orEmpty()
        val clock = type?.optString("shortDetail").orEmpty()
        val cs = comp.optJSONArray("competitors")
        fun score(side: String) = (0 until (cs?.length() ?: 0)).map { cs!!.getJSONObject(it) }.firstOrNull { it.optString("homeAway") == side }?.optString("score").orEmpty()
        val home = short(e.teams[0]); val away = short(e.teams[1])
        val line = "$home ${score("home")}–${score("away")} $away"
        val code = key.hashCode()
        val spoiler = Settings.spoilerFree(ctx)
        val first = !s.has("seen")
        val seen = s.optJSONArray("seen") ?: org.json.JSONArray().also { s.put("seen", it) }
        val seenIds = (0 until seen.length()).map { seen.getString(it) }.toMutableSet()

        // lineups, once, as soon as both starting elevens are published
        if (Settings.lineupAlerts(ctx) && !s.optBoolean("lineups")) {
            val sheet = runCatching { espnSheet(m.league, m.id) }.getOrNull()
            if (sheet?.home != null && sheet.away != null) {
                fun xi(t: TeamSheet) = "${short(t.team)}${if (t.formation.isNotBlank()) " (${t.formation})" else ""}: " +
                    t.starters.joinToString(", ") { it.name.substringAfterLast(' ') }
                notify(ctx, LINEUPS, code + 1, "Lineups · ${e.line1()}", "${xi(sheet.home)}\n\n${xi(sheet.away)}", "Kick-off ${hm(e.start)}")
                s.put("lineups", true)
            }
        }

        // every key event is marked seen even while alerts are off, so switching them on mid-match never replays old goals
        val ke = o.optJSONArray("keyEvents")
        val fresh = (0 until (ke?.length() ?: 0)).map { i -> i to ke!!.getJSONObject(i) }.filter { (i, k) ->
            val id = k.optString("id").ifEmpty { "$i" }
            (id !in seenIds).also { new -> if (new) { seenIds += id; seen.put(id) } }
        }
        if (Settings.liveAlerts(ctx) && !spoiler) {
            for ((i, k) in fresh) {
                if (first) continue                                             // opened mid-match: don't replay old goals
                val text = k.optString("text"); val minute = k.optJSONObject("clock")?.optString("displayValue").orEmpty()
                val kind = k.optJSONObject("type")?.optString("type").orEmpty()
                when {
                    k.optBoolean("scoringPlay") -> {
                        val at = Regex("""(\d+), [^.]*? (\d+)\.""").find(text)?.groupValues
                        val shown = if (at != null) "$home ${at[1]}–${at[2]} $away" else line
                        notify(ctx, GOALS, code + 10 + i, "⚽ GOAL · $shown", "${scorer(text)} $minute".trim(), e.line1())
                    }
                    kind == "red-card" -> notify(ctx, GOALS, code + 10 + i, "🟥 Red card · ${e.line1()}",
                        "${text.substringBefore(" is shown").trim()} $minute".trim(), line)
                }
            }
            if (!first && !s.optBoolean("ht") && (clock == "HT" || type?.optString("name") == "STATUS_HALFTIME")) {
                notify(ctx, GOALS, code + 2, "Half-time · $line", "Second half next", e.line1()); s.put("ht", true)
            }
            if (state == "in") ongoing(ctx, code + 3, line, clock)
            if (state == "post") {
                cancel(ctx, code + 3)
                notify(ctx, GOALS, code + 4, "Full time · $line", clock.ifEmpty { "FT" }, e.line1())
            }
        } else cancel(ctx, code + 3)
        if (state == "post") s.put("ft", true)
        s.put("score", "${score("home")}–${score("away")}").put("clock", clock).put("state", state).put("event", e.id)
        return state
    }

    /** "Goal! Aston Villa 1, Manchester City 0. Nicolas Jackson (Aston Villa) right footed shot..." -> "Nicolas Jackson". */
    fun scorer(text: String): String = when {
        text.startsWith("Own Goal by", true) -> text.removePrefix("Own Goal by").substringBefore(",").trim() + " (own goal)"
        text.contains(". ") -> text.substringAfter(". ").substringBefore(" (").trim()
        else -> text
    }

    /** The last live score seen for this event, for the widget: (score, clock, state) or null. */
    fun score(ctx: Context, e: Event): Triple<String, String, String>? =
        state(ctx).optJSONObject(alertKey(e))?.takeIf { it.has("score") }?.let { Triple(it.optString("score"), it.optString("clock"), it.optString("state")) }

    private fun state(ctx: Context) = runCatching { JSONObject(Repo.prefs(ctx).getString("liveState", "{}")!!) }.getOrDefault(JSONObject())
    private fun save(ctx: Context, st: JSONObject, now: Instant) {
        // keep two days of matches
        st.keys().asSequence().toList().forEach { k -> val at = st.optJSONObject(k)?.optLong("at", 0) ?: 0; if (at != 0L && now.toEpochMilli() - at > 2 * 86400_000L) st.remove(k) }
        st.keys().forEach { k -> st.optJSONObject(k)?.let { if (!it.has("at")) it.put("at", now.toEpochMilli()) } }
        Repo.prefs(ctx).edit().putString("liveState", st.toString()).apply()
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(GOALS, "Goals and results", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(LINEUPS, "Lineups", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(SCORE, "Live score", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun notify(ctx: Context, channel: String, id: Int, title: String, body: String, sub: String) {
        if (!allowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java); channels(nm)
        nm.notify(id, Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_sportclock).setColor(0xFFC8F53C.toInt())
            .setContentTitle(title).setContentText(body).setSubText(sub).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp(ctx)).setAutoCancel(true).build())
    }

    private fun ongoing(ctx: Context, id: Int, line: String, clock: String) {
        if (!allowed(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java); channels(nm)
        nm.notify(id, Notification.Builder(ctx, SCORE).setSmallIcon(R.drawable.ic_stat_sportclock).setColor(0xFFC8F53C.toInt())
            .setContentTitle(line).setContentText("Live · $clock").setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_EVENT).setContentIntent(openApp(ctx)).build())
    }

    private fun cancel(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)
}

class LiveTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeoutOrNull(45_000) { Live.tick(ctx) } } finally { done.finish() }
        }
    }
}
