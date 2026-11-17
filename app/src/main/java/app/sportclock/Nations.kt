package app.sportclock

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/*
 * National teams you can follow like a club: Kenya, England, Brazil… Their games come from ESPN's all-competitions team
 * schedule (qualifiers, tournaments and friendlies in one list), fetched for the followed countries only, kept for three
 * hours and merged into the events everything else reads: Home, NEXT UP, Following, the widget and the alerts.
 * Ids are "CUP|<ESPN game id>", the same as the Scores boards use, so a game is never listed twice.
 */

/** Every national team ESPN lists in the men's international competitions, by its ESPN name, with its ESPN id. */
val NATIONS: Map<String, String> = mapOf(
    "Afghanistan" to "5780", "Albania" to "585", "Algeria" to "624", "American Samoa" to "4886", "Andorra" to "587", "Angola" to "653",
    "Anguilla" to "8942", "Antigua and Barbuda" to "2638", "Argentina" to "202", "Armenia" to "579", "Aruba" to "2642", "Australia" to "628",
    "Austria" to "474", "Azerbaijan" to "581", "Bahamas" to "2640", "Bahrain" to "4381", "Bangladesh" to "5783", "Barbados" to "2637",
    "Belarus" to "583", "Belgium" to "459", "Belize" to "2641", "Benin" to "2844", "Bermuda" to "2643", "Bhutan" to "5784", "Bolivia" to "204",
    "Bonaire" to "19314", "Bosnia-Herzegovina" to "452", "Botswana" to "4245", "Brazil" to "205", "British Virgin Islands" to "2644",
    "Brunei Darussalam" to "10525", "Bulgaria" to "462", "Burkina Faso" to "2845", "Burundi" to "5779", "Cambodia" to "5518", "Cameroon" to "656",
    "Canada" to "206", "Cape Verde" to "2597", "Cayman Islands" to "2646", "Central African Republic" to "10528", "Chad" to "8941", "Chile" to "207",
    "China" to "658", "Chinese Taipei" to "14561", "Colombia" to "208", "Comoros" to "8601", "Congo" to "4276", "Congo DR" to "2850",
    "Cook Islands" to "4884", "Costa Rica" to "214", "Croatia" to "477", "Cuba" to "2647", "Curaçao" to "11678", "Cyprus" to "445",
    "Czechia" to "450", "Denmark" to "479", "Djibouti" to "5775", "Dominica" to "13582", "Dominican Republic" to "2649", "Ecuador" to "209",
    "Egypt" to "2620", "El Salvador" to "2650", "England" to "448", "Equatorial Guinea" to "8938", "Eritrea" to "5774", "Estonia" to "444",
    "Eswatini" to "6686", "Ethiopia" to "5777", "Faroe Islands" to "447", "Fiji" to "4862", "Finland" to "458", "France" to "478",
    "French Guiana" to "10532", "Gabon" to "4231", "Gambia" to "7368", "Georgia" to "584", "Germany" to "481", "Ghana" to "4469",
    "Gibraltar" to "16721", "Greece" to "455", "Grenada" to "2651", "Guadeloupe" to "7657", "Guam" to "10529", "Guatemala" to "2652",
    "Guinea" to "2847", "Guinea-Bissau" to "8602", "Guyana" to "2653", "Haiti" to "2654", "Honduras" to "215", "Hong Kong" to "1928",
    "Hungary" to "480", "Iceland" to "470", "India" to "4385", "Indonesia" to "4895", "Iran" to "469", "Iraq" to "4375", "Israel" to "461",
    "Italy" to "162", "Ivory Coast" to "4789", "Jamaica" to "1038", "Japan" to "627", "Jordan" to "2917", "Kazakhstan" to "2619", "Kenya" to "2848",
    "Kosovo" to "18272", "Kuwait" to "841", "Kyrgyz Republic" to "6724", "Laos" to "7348", "Latvia" to "456", "Lebanon" to "4388",
    "Lesotho" to "6640", "Liberia" to "4205", "Libya" to "2621", "Liechtenstein" to "589", "Lithuania" to "460", "Luxembourg" to "582",
    "Macau" to "6722", "Madagascar" to "5533", "Malawi" to "4325", "Malaysia" to "2405", "Maldives" to "4390", "Mali" to "2849", "Malta" to "453",
    "Martinique" to "2728", "Mauritania" to "8940", "Mauritius" to "5534", "Mexico" to "203", "Moldova" to "483", "Mongolia" to "8663",
    "Montenegro" to "6775", "Montserrat" to "2655", "Morocco" to "2869", "Mozambique" to "8939", "Myanmar" to "6770", "Namibia" to "6725",
    "Nepal" to "5785", "Netherlands" to "449", "New Caledonia" to "4888", "New Zealand" to "2666", "Nicaragua" to "2658", "Niger" to "8937",
    "Nigeria" to "657", "North Korea" to "4860", "North Macedonia" to "463", "Northern Ireland" to "586", "Norway" to "464", "Oman" to "2841",
    "Pakistan" to "5781", "Palestine" to "6167", "Panama" to "2659", "Papua New Guinea" to "4885", "Paraguay" to "210", "Peru" to "211",
    "Philippines" to "7347", "Poland" to "471", "Portugal" to "482", "Puerto Rico" to "11766", "Qatar" to "4398", "Republic of Ireland" to "476",
    "Romania" to "473", "Russia" to "454", "Rwanda" to "2851", "Samoa" to "4887", "San Marino" to "588", "Sao Tome and Principe" to "11808",
    "Saudi Arabia" to "655", "Scotland" to "580", "Senegal" to "654", "Serbia" to "6757", "Seychelles" to "6726", "Sierra Leone" to "8600",
    "Singapore" to "4384", "Sint Maarten" to "18243", "Slovakia" to "468", "Slovenia" to "472", "Solomon Islands" to "4861", "Somalia" to "5776",
    "South Africa" to "467", "South Korea" to "451", "South Sudan" to "14075", "Spain" to "164", "Sri Lanka" to "5782",
    "St. Kitts and Nevis" to "2662", "St. Lucia" to "2661", "St. Martin" to "10596", "St. Vincent and the Grenadines" to "13584", "Sudan" to "4319",
    "Suriname" to "2664", "Sweden" to "466", "Switzerland" to "475", "Syria" to "4380", "Tahiti" to "4863", "Tajikistan" to "6723",
    "Tanzania" to "5778", "Thailand" to "4396", "Timor-Leste" to "8664", "Togo" to "4356", "Tonga" to "4883", "Trinidad and Tobago" to "2627",
    "Tunisia" to "659", "Turkmenistan" to "7507", "Turks and Caicos Islands" to "2665", "Türkiye" to "465", "US Virgin Islands" to "2645",
    "Uganda" to "4211", "Ukraine" to "457", "United Arab Emirates" to "4397", "United States" to "660", "Uruguay" to "212", "Uzbekistan" to "2570",
    "Vanuatu" to "4864", "Venezuela" to "213", "Vietnam" to "7349", "Wales" to "578", "Yemen" to "6014", "Zambia" to "4277", "Zimbabwe" to "4214"
)

fun isNation(team: String) = team in NATIONS

/** One national-team game as ESPN's team schedule lists it. */
data class NationGame(val id: String, val slug: String, val league: String, val date: Instant, val home: String, val away: String, val venue: String,
                      val timeValid: Boolean)

private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseNationGames(o: JSONObject): List<NationGame> = o.optJSONArray("events").objs().mapNotNull { ev ->
    val c = ev.optJSONArray("competitions").objs().firstOrNull() ?: return@mapNotNull null
    val cs = c.optJSONArray("competitors").objs()
    val h = cs.firstOrNull { it.optString("homeAway") == "home" } ?: return@mapNotNull null
    val a = cs.firstOrNull { it.optString("homeAway") == "away" } ?: return@mapNotNull null
    val date = runCatching { Instant.parse(ev.optString("date").let { if (it.length == 17) it.dropLast(1) + ":00Z" else it }) }.getOrNull() ?: return@mapNotNull null
    val lg = ev.optJSONObject("league")
    NationGame(ev.optString("id"), lg?.optString("slug").orEmpty(), lg?.optString("name").orEmpty(), date,
        h.optJSONObject("team")?.optString("displayName").orEmpty(), a.optJSONObject("team")?.optString("displayName").orEmpty(),
        c.optJSONObject("venue")?.optString("fullName").orEmpty(), c.optBoolean("timeValid", true))
}

/** The competition code for an ESPN league slug: one of ours when we know it, else "INT" (shown, but not scored). */
fun intlCode(slug: String) = (INTL + INTL_MORE).firstOrNull { it.slug == slug }?.code ?: "INT"

object Nations {
    /** The countries whose games we fetch: the ones you follow and the one on your tab. */
    fun teams(ctx: Context): List<String> =
        (Repo.follows(ctx).filter { it.startsWith("t:") }.map { it.substring(2) } + listOfNotNull(Settings.myTab(ctx).takeIf { it.startsWith("t:") }?.substring(2)))
            .filter(::isNation).distinct().sorted()

    private fun file(ctx: Context) = File(ctx.filesDir, "nations.json")

    /** Games of every followed country as events: the phone's copy if under [maxAgeMin] old and for the same countries, else ESPN. */
    suspend fun events(ctx: Context, now: Instant = Instant.now(), maxAgeMin: Long = 180): List<Event> = withContext(Dispatchers.IO) {
        val teams = teams(ctx)
        if (teams.isEmpty()) return@withContext emptyList()
        val f = file(ctx)
        val key = teams.joinToString("|")
        val saved = runCatching { JSONObject(f.readText()) }.getOrNull()
        val fresh = saved != null && saved.optString("key") == key && System.currentTimeMillis() - f.lastModified() < maxAgeMin * 60_000
        val doc = if (fresh) saved!! else runCatching { fetch(teams).also { f.writeText(it.put("key", key).toString()) } }.getOrElse { saved ?: return@withContext emptyList() }
        toEvents(doc, now)
    }

    private suspend fun fetch(teams: List<String>): JSONObject = coroutineScope {
        val out = JSONObject()
        teams.map { team ->
            async(Dispatchers.IO) {
                val id = NATIONS[team] ?: return@async null
                // played games and games to come are two lists; both are needed for a full picture
                val games = listOf("", "?fixture=true").flatMap { q ->
                    runCatching { parseNationGames(JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/$id/schedule$q"))) }
                        .getOrDefault(emptyList())
                }
                if (games.isEmpty()) null else team to games
            }
        }.awaitAll().filterNotNull().forEach { (team, games) ->
            out.put(team, JSONArray(games.distinctBy { it.id }.map { g ->
                JSONObject().put("id", g.id).put("slug", g.slug).put("league", g.league).put("date", g.date.toString()).put("home", g.home)
                    .put("away", g.away).put("venue", g.venue).put("tv", g.timeValid)
            }))
        }
        out
    }

    /** Each game becomes an event, from four months back; when two followed countries meet it is one event. */
    internal fun toEvents(doc: JSONObject, now: Instant): List<Event> {
        val from = now.minusSeconds(120L * 86400)
        val out = LinkedHashMap<String, Event>()
        doc.keys().asSequence().filter { it != "key" }.forEach { team ->
            doc.optJSONArray(team).objs().forEach { o ->
                val date = runCatching { Instant.parse(o.optString("date")) }.getOrNull() ?: return@forEach
                if (date.isBefore(from)) return@forEach
                val code = intlCode(o.optString("slug"))
                val home = o.optString("home"); val away = o.optString("away")
                val eid = "CUP|${o.optString("id")}"
                out[eid] = Event(eid, Sport.FOOTBALL, code, cup(code)?.takeIf { code != "INT" }?.name ?: o.optString("league").ifBlank { "International football" },
                    "$home v $away", "Kick-off", date, 120, o.optString("venue"), if (o.optBoolean("tv", true)) "confirmed" else "tbc", listOf(home, away),
                    null, null, null)
            }
        }
        return out.values.sortedBy { it.start }
    }
}
