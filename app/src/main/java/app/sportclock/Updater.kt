package app.sportclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val name: String, val notes: String, val size: Long?)

class ServiceNotSetUp : Exception("The update service isn't switched on yet")

/**
 * In-app updates from the private GitHub releases, through the app service on the website.
 * The APK is the same one attached to the release, signed with the same key, so Android installs it in place.
 * The download itself goes through the browser (see [openDownload]).
 */
object Updater {
    /** True when version [a] ("2.10") is newer than [b] ("2.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }; val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val c = URL("$APP_API/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try {
            if (c.responseCode == 503) throw ServiceNotSetUp()
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            Release(o.getString("version"), o.optString("name"), o.optString("notes"), if (o.isNull("size")) null else o.optLong("size"))
        } finally { c.disconnect() }
    }

    /** Remember what the last check found, so Home can offer it without asking the network. */
    fun remember(ctx: Context, r: Release) = Repo.prefs(ctx).edit().putString("updateVersion", r.version).putLong("updateChecked", System.currentTimeMillis()).apply()
    fun waiting(ctx: Context): String? = Repo.prefs(ctx).getString("updateVersion", null)?.takeIf { newer(it, Diag.version(ctx).first) }
    fun dismissed(ctx: Context) = Repo.prefs(ctx).getString("updateDismissed", null)
    fun dismiss(ctx: Context, v: String) = Repo.prefs(ctx).edit().putString("updateDismissed", v).apply()

    /** At most twice a day, quietly; a failure just waits for the next time. */
    suspend fun checkNow(ctx: Context) {
        if (System.currentTimeMillis() - Repo.prefs(ctx).getLong("updateChecked", 0) < 12 * 3600_000L) return
        runCatching { latest() }.onSuccess { remember(ctx, it) }
    }

    /**
     * Opens the APK link in the phone's browser, which downloads it and hands it to Android's installer.
     * SportClock deliberately does not install APKs itself: Play Protect blocks sideloaded apps that do.
     */
    fun openDownload(ctx: Context) = ctx.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse("$APP_API/apk?k=$APP_KEY")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
