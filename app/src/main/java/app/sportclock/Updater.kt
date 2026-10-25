package app.sportclock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val name: String, val notes: String, val size: Long?)

class ServiceNotSetUp : Exception("The update service isn't switched on yet")

/**
 * In-app updates from the private GitHub releases, through the app service on the website.
 * The APK is the same one attached to the release, signed with the same key, so Android installs it in place.
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

    private fun file(ctx: Context) = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "SportClock.apk")

    /** Downloads the newest APK; [progress] gets 0..1 (or -1 when the size is unknown). */
    suspend fun download(ctx: Context, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        // The service answers with a redirect to a short-lived GitHub link; follow it by hand so the app key stays here.
        val a = URL("$APP_API/apk").openConnection() as HttpURLConnection
        a.instanceFollowRedirects = false; a.connectTimeout = 10_000; a.readTimeout = 20_000
        a.setRequestProperty("X-SportClock-Key", APP_KEY)
        val to = try {
            if (a.responseCode == 503) throw ServiceNotSetUp()
            a.getHeaderField("Location") ?: error("HTTP ${a.responseCode}")
        } finally { a.disconnect() }
        val c = URL(to).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        val out = file(ctx)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    progress(if (total > 0) done.toFloat() / total else -1f)
                }
            } }
        } finally { c.disconnect() }
        out
    }

    /** Android asks once whether SportClock may install apps; after that this opens the normal install screen. */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
    fun askInstallPermission(ctx: Context) = ctx.startActivity(
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun install(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
