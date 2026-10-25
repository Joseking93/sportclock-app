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
        if (!Settings.alertsOn(ctx)) { p.edit().putStringSet("alarmCodes", emptySet()).apply(); return }
        val now = Instant.now(); val lead = Settings.leadMins(ctx).toLong()
        val follows = Repo.follows(ctx)
        val codes = HashSet<String>()
        events.asSequence().filter { !it.isTbc && followed(it, follows) }
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
