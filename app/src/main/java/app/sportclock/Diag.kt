package app.sportclock

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The app service on the website (functions/api/app in the sportclock repo): updates and bug reports. */
const val APP_API = "$SITE/api/app"
internal const val APP_KEY = "BF7o_Cc6Dyy-SaBNu4bcU8kn"

class SportClockApp : Application() {
    override fun onCreate() { super.onCreate(); Diag.install(this) }
}

/**
 * Crash and bug reports. A crash is written to the phone the moment it happens and sent as a GitHub issue
 * the next time the app opens; a report from More → Report a problem goes the same way. Anything that
 * cannot be sent yet (offline, service not set up) waits in files/reports and is retried on the next start.
 */
object Diag {
    private val trail = ArrayDeque<String>()
    private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** A breadcrumb: the last 40 go with a crash, so a report says what the app was doing. */
    fun log(msg: String) = synchronized(trail) {
        trail.addLast("${HMS.format(Instant.now())} $msg")
        while (trail.size > 40) trail.removeFirst()
    }
    private fun trailText() = synchronized(trail) { trail.joinToString("\n") }

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                save(app, "crash", "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(120), sw.toString(), includeTrail = true)
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "reports").apply { mkdirs() }
    fun pending(ctx: Context) = dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()

    fun version(ctx: Context): Pair<String, Long> = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
        it.versionName.orEmpty() to (if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong())
    }

    fun save(ctx: Context, kind: String, title: String, text: String, includeTrail: Boolean) {
        val (name, code) = version(ctx)
        val o = JSONObject().put("kind", kind).put("title", title).put("text", text)
            .put("version", name).put("code", code)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            .put("at", Instant.now().toString()).put("trail", if (includeTrail) trailText() else "")
        File(dir(ctx), "${System.currentTimeMillis()}-$kind.json").writeText(o.toString())
    }

    enum class Sent { ALL, SOME_WAITING, NOT_SET_UP, OFFLINE }

    /** Send whatever is waiting; returns how it went and how many crashes went out. */
    suspend fun sendPending(ctx: Context): Pair<Sent, Int> = withContext(Dispatchers.IO) {
        var crashes = 0
        for (f in pending(ctx)) {
            val code = runCatching { post("$APP_API/report", f.readText()) }.getOrElse { return@withContext Sent.OFFLINE to crashes }
            when {
                code in 200..299 -> { if (f.name.endsWith("-crash.json")) crashes++; f.delete() }
                code == 503 -> return@withContext Sent.NOT_SET_UP to crashes
                code == 400 || code == 413 -> f.delete()                      // never going to go through
                else -> return@withContext Sent.SOME_WAITING to crashes
            }
        }
        Sent.ALL to crashes
    }

    private fun post(url: String, body: String): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-SportClock-Key", APP_KEY)
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.responseCode
        } finally { c.disconnect() }
    }
}
