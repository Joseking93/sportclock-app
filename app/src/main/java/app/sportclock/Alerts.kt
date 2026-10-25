package app.sportclock

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.glance.appwidget.updateAll
import app.sportclock.widget.SportClockWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Settings that are not follows: alerts and their lead time, and the text size. */
object Settings {
    fun alertsOn(ctx: Context) = Repo.prefs(ctx).getBoolean("alerts", false)
    fun setAlertsOn(ctx: Context, on: Boolean) = Repo.prefs(ctx).edit().putBoolean("alerts", on).apply()
    fun leadMins(ctx: Context) = Repo.prefs(ctx).getInt("lead", 30)
    fun setLeadMins(ctx: Context, m: Int) = Repo.prefs(ctx).edit().putInt("lead", m).apply()
    /** Text size as a font scale; 0 means "use the phone's own setting". */
    fun textScale(ctx: Context) = Repo.prefs(ctx).getFloat("textScale", 0f)
    fun setTextScale(ctx: Context, s: Float) = Repo.prefs(ctx).edit().putFloat("textScale", s).apply()
    /** Goals, red cards, half-time and full-time for watched matches, and the live-score notification. */
    fun liveAlerts(ctx: Context) = Repo.prefs(ctx).getBoolean("liveAlerts", true)
    fun setLiveAlerts(ctx: Context, on: Boolean) = Repo.prefs(ctx).edit().putBoolean("liveAlerts", on).apply()
    fun lineupAlerts(ctx: Context) = Repo.prefs(ctx).getBoolean("lineupAlerts", true)
    /** Safety car, VSC, red flag, green flag, retirements, lead changes and the chequered flag in F1 sessions you follow. */
    fun f1Alerts(ctx: Context) = Repo.prefs(ctx).getBoolean("f1Alerts", true)
    fun setF1Alerts(ctx: Context, on: Boolean) = Repo.prefs(ctx).edit().putBoolean("f1Alerts", on).apply()
    fun setLineupAlerts(ctx: Context, on: Boolean) = Repo.prefs(ctx).edit().putBoolean("lineupAlerts", on).apply()
    /** Hide scores until tapped, everywhere: lists, cards, the widget and notifications. */
    fun spoilerFree(ctx: Context) = Repo.prefs(ctx).getBoolean("spoilerFree", false)
    fun setSpoilerFree(ctx: Context, on: Boolean) { Repo.prefs(ctx).edit().putBoolean("spoilerFree", on).apply(); Spoilers.on = on }
}

/** Spoiler-free mode as Compose state, and the matches revealed this session. */
object Spoilers {
    var on by mutableStateOf(false)
    val revealed = androidx.compose.runtime.mutableStateListOf<String>()
    fun hidden(e: Event) = on && e.id !in revealed
    fun load(ctx: Context) { on = Settings.spoilerFree(ctx) }
}

/**
 * Which events alert. A match you picked (its bell) always does and one you muted never does; anything else
 * alerts when it comes from a follow set to "All". Football competitions start as "Picked only", because a
 * Premier League weekend is ten matches; teams and series (Man Utd, F1, UFC) start as "All".
 * Picks are kept by the football match number, or code + title + session, so a match moved by TV keeps its alert.
 */
fun alertKey(e: Event) = e.fdid?.let { "fd:$it" } ?: "${e.code}|${e.title}|${e.session}"
fun quietByDefault(token: String) = token.startsWith("c:") && series(token.substring(2))?.sport == Sport.FOOTBALL
/** True when this follow alerts for everything; [flipped] holds the follows changed away from their default. */
fun alertsAll(token: String, flipped: Set<String>) = (token in flipped) == quietByDefault(token)
fun wantsAlert(e: Event, follows: Set<String>, flipped: Set<String>, picked: Set<String>, muted: Set<String>): Boolean {
    if (e.isTbc) return false
    val k = alertKey(e)
    if (k in picked) return true
    if (k in muted) return false
    return follows.any { tokenMatches(it, e) && alertsAll(it, flipped) }
}

/** The alert choices as Compose state, so every bell on screen updates together. */
object AlertChoices {
    var on by mutableStateOf(false)
    var follows by mutableStateOf(emptySet<String>())
    var picked by mutableStateOf(emptySet<String>())
    var muted by mutableStateOf(emptySet<String>())
    var flipped by mutableStateOf(emptySet<String>())

    fun load(ctx: Context) {
        val p = Repo.prefs(ctx)
        on = Settings.alertsOn(ctx); follows = Repo.follows(ctx)
        picked = p.getStringSet("alertPicked", emptySet())!!.toSet()
        muted = p.getStringSet("alertMuted", emptySet())!!.toSet()
        flipped = p.getStringSet("alertFlipped", emptySet())!!.toSet()
    }
    private fun save(ctx: Context) = Repo.prefs(ctx).edit().putStringSet("alertPicked", picked).putStringSet("alertMuted", muted)
        .putStringSet("alertFlipped", flipped).apply()

    fun wants(e: Event) = wantsAlert(e, follows, flipped, picked, muted)

    /** The bell: turns this one event's alert on or off, whatever its follow says. Returns the new state. */
    fun toggle(ctx: Context, e: Event): Boolean {
        val k = alertKey(e)
        if (wants(e)) { picked = picked - k; if (wants(e)) muted = muted + k }
        else { muted = muted - k; if (!wants(e)) picked = picked + k }
        save(ctx); return wants(e)
    }

    fun setAll(ctx: Context, token: String, all: Boolean) {
        flipped = if (all == !quietByDefault(token)) flipped - token else flipped + token
        save(ctx)
    }

    /** Forget picks for events that are over, so the lists don't grow for ever. */
    fun prune(ctx: Context, events: List<Event>, now: Instant) {
        if (events.size < 20) return                     // a failed or partial load must not wipe the picks
        val live = events.filter { !it.over(now) }.map { alertKey(it) }.toSet()
        picked = picked intersect live; muted = muted intersect live
        save(ctx)
    }
}

/**
 * "Starting soon" alerts for followed events, as exact alarms owned by the phone, so they arrive with the app closed.
 * Re-armed whenever the app opens, follows or settings change, the phone restarts or the app updates.
 * TBC events never alert: there is no honest time to alert on.
 */
object Alerts {
    private const val CHANNEL = "starting"
    private const val MAX = 40

    fun reschedule(ctx: Context, events: List<Event>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = Repo.prefs(ctx)
        p.getStringSet("alarmCodes", emptySet())!!.forEach { am.cancel(pending(ctx, it.toInt(), null)) }
        if (!Settings.alertsOn(ctx)) { p.edit().putStringSet("alarmCodes", emptySet()).apply(); Live.schedule(ctx, events); return }
        val now = Instant.now(); val lead = Settings.leadMins(ctx).toLong()
        AlertChoices.load(ctx); AlertChoices.prune(ctx, events, now)
        val codes = HashSet<String>()
        events.asSequence().filter { AlertChoices.wants(it) }
            .map { it to it.start.minusSeconds(lead * 60) }
            .filter { (e, at) -> at.isAfter(now) && Duration.between(now, e.start).toDays() < 8 }
            .take(MAX).forEach { (e, at) ->
                val code = e.id.hashCode()
                val title = "${e.line1()} in ${untilText(lead)}"
                val body = "${e.line2()} · ${hm(e.start)} your time"
                setExact(am, at.toEpochMilli(), pending(ctx, code, Intent(ctx, AlertReceiver::class.java).putExtra("title", title).putExtra("body", body).putExtra("code", code)))
                codes += code.toString()
            }
        p.edit().putStringSet("alarmCodes", codes).apply()
        Live.schedule(ctx, events)
    }

    private fun pending(ctx: Context, code: Int, intent: Intent?) = PendingIntent.getBroadcast(ctx, code,
        intent ?: Intent(ctx, AlertReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun notify(ctx: Context, code: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Starting soon", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_sportclock).setColor(0xFFC8F53C.toInt())
            .setContentTitle(title).setContentText(body).setContentIntent(open).setAutoCancel(true).build()
        nm.notify(code, n)
    }

    /**
     * Redraw the widget at the next moment its wording changes: every 5 minutes in the last hour before the next event,
     * every 15 minutes in the three hours before that, otherwise every 30 minutes.
     */
    fun scheduleWidget(ctx: Context, next: Instant?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = Instant.now()
        val mins = next?.let { Duration.between(now, it).toMinutes() } ?: Long.MAX_VALUE
        val step = when { mins in 0..60 -> 5L; mins in 61..180 -> 15L; else -> 30L }
        var at = now.plusSeconds(step * 60)
        if (next != null && mins > 180) at = minOf(at, next.minusSeconds(180 * 60).coerceAtLeast(now.plusSeconds(60)))
        if (next != null && next.isAfter(now) && next.isBefore(at)) at = next.plusSeconds(5)   // flip to "on now" right at the start
        setExact(am, at.toEpochMilli(), PendingIntent.getBroadcast(ctx, 7001, Intent(ctx, WidgetTick::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
    private fun Instant.coerceAtLeast(o: Instant) = if (isBefore(o)) o else this
}

class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) =
        Alerts.notify(ctx, i.getIntExtra("code", 0), i.getStringExtra("title").orEmpty(), i.getStringExtra("body").orEmpty())
}

class WidgetTick : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { try { SportClockWidget().updateAll(ctx) } finally { done.finish() } }
    }
}

/** After a restart or an app update the phone forgets alarms, so arm them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Repo.load(ctx, maxAgeMin = 600)?.let { Alerts.reschedule(ctx, it.events) }
                SportClockWidget().updateAll(ctx)
            } finally { done.finish() }
        }
    }
}
