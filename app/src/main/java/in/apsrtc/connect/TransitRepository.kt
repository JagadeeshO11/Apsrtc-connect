package `in`.apsrtc.connect

import android.content.Context
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)
data class BusRoute(val id: String, val shortName: String, val longName: String, val routeType: Int = 3)
data class LiveBus(val id: String, val label: String, val routeId: String, val tripId: String, val lat: Double, val lon: Double, val bearing: Double, val timestamp: Long = 0L, val speed: Double = 0.0, val routeName: String = "", val routeLongName: String = "", val startTime: String = "")
data class Arrival(val tripId: String, val routeId: String, val stopId: String, val arrivalEpoch: Long?, val delay: Int?)

class TransitRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
    private val prefs = context.getSharedPreferences("apsrtc_connect", Context.MODE_PRIVATE)
    private val base = "https://maps.reclaim.city"

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "APSRTC-Connect/1.1 (Android)").header("Accept", "application/json").build()
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string() ?: error("Empty response from ${request.url.encodedPath}")
                    val code = response.code
                    if (code !in listOf(408, 429, 500, 502, 503, 504)) error("API ${request.url.encodedPath}: HTTP $code")
                    lastError = java.io.IOException("API ${request.url.encodedPath}: HTTP $code")
                }
            } catch (e: Exception) {
                lastError = e
                if (e.message?.contains("HTTP 4") == true && !e.message.orEmpty().contains("408") && !e.message.orEmpty().contains("429")) throw e
            }
            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
        }
        throw lastError ?: java.io.IOException("API request failed")
    }

    suspend fun loadStaticData(): Pair<List<Stop>, List<BusRoute>> = withContext(Dispatchers.IO) {
        val cache = java.io.File(context.cacheDir, "apsrtc.zip")
        if (!cache.exists() || cache.length() < 100_000L || System.currentTimeMillis() - cache.lastModified() > 7L * 24 * 60 * 60 * 1000) {
            val request = Request.Builder().url("$base/gtfs/apsrtc.zip")
                .header("User-Agent", "APSRTC-Connect/1.0 (Android)").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GTFS download failed: ${response.code}")
                response.body?.byteStream()?.use { input -> cache.outputStream().use(input::copyTo) }
            }
        }
        val stops = mutableListOf<Stop>()
        val routes = mutableListOf<BusRoute>()
        ZipInputStream(cache.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "stops.txt" -> readCsv(zip) { row ->
                        val id = row["stop_id"].orEmpty()
                        val name = row["stop_name"].orEmpty()
                        if (id.isNotBlank() && name.isNotBlank()) {
                            stops += Stop(id, name, row["stop_lat"]?.toDoubleOrNull() ?: 0.0, row["stop_lon"]?.toDoubleOrNull() ?: 0.0)
                        }
                    }
                    "routes.txt" -> readCsv(zip) { row ->
                        val id = row["route_id"].orEmpty()
                        if (id.isNotBlank()) routes += BusRoute(id, row["route_short_name"].orEmpty(), row["route_long_name"].orEmpty(), row["route_type"]?.toIntOrNull() ?: 3)
                    }
                }
                zip.closeEntry()
            }
        }
        prefs.edit().putLong("static_loaded_at", System.currentTimeMillis()).apply()
        stops to routes
    }

    private fun readCsv(input: java.io.InputStream, consume: (Map<String, String>) -> Unit) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val header = reader.readLine()?.removePrefix("\uFEFF")?.let(::parseCsv) ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val values = parseCsv(line)
            consume(header.mapIndexedNotNull { i, key -> values.getOrNull(i)?.let { key to it } }.toMap())
        }
    }

    private fun parseCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { cell.append('"'); i++ }
                else quoted = !quoted
            } else if (c == ',' && !quoted) { out += cell.toString(); cell.setLength(0) }
            else cell.append(c)
            i++
        }
        out += cell.toString()
        return out
    }

    suspend fun findRoutes(origin: Stop, destination: Stop, routes: List<BusRoute>): List<BusRoute> = withContext(Dispatchers.IO) {
        val cache = java.io.File(context.cacheDir, "apsrtc.zip")
        val tripRoutes = HashMap<String, String>()
        ZipInputStream(cache.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "trips.txt") readCsv(zip) { row ->
                    val trip = row["trip_id"].orEmpty(); val route = row["route_id"].orEmpty()
                    if (trip.isNotBlank()) tripRoutes[trip] = route
                }
                zip.closeEntry()
            }
        }
        val originSeq = HashMap<String, Int>()
        val matches = LinkedHashSet<String>()
        ZipInputStream(cache.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "stop_times.txt") readCsv(zip) { row ->
                    val trip = row["trip_id"].orEmpty()
                    val stop = row["stop_id"].orEmpty()
                    val seq = row["stop_sequence"]?.toIntOrNull() ?: 0
                    if (stop == origin.id) originSeq[trip] = seq
                    if (stop == destination.id) {
                        val start = originSeq[trip]
                        if (start != null && seq > start) tripRoutes[trip]?.let(matches::add)
                    }
                }
                zip.closeEntry()
            }
        }
        matches.mapNotNull { id -> routes.firstOrNull { it.id == id } }.distinctBy { it.id }.take(30)
    }

    suspend fun status(): String = withContext(Dispatchers.IO) { get("$base/apsrtc/rt/status.json") }

    fun saveLiveBuses(items: List<LiveBus>) { prefs.edit().putString("last_live_buses", com.google.gson.Gson().toJson(items)).apply() }
    fun cachedLiveBuses(): List<LiveBus> = runCatching {
        val raw = prefs.getString("last_live_buses", null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<LiveBus>>() {}.type
        com.google.gson.Gson().fromJson<List<LiveBus>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())
    fun saveArrivals(items: List<Arrival>) { prefs.edit().putString("last_arrivals", com.google.gson.Gson().toJson(items)).apply() }
    fun cachedArrivals(): List<Arrival> = runCatching {
        val raw = prefs.getString("last_arrivals", null) ?: return emptyList()
        val type = object : com.google.gson.reflect.TypeToken<List<Arrival>>() {}.type
        com.google.gson.Gson().fromJson<List<Arrival>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun liveBuses(routes: List<BusRoute> = emptyList()): List<LiveBus> = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(get("$base/apsrtc/rt/vehicle_positions.json")).asJsonObject
        val entities = root.getAsJsonArray("entity") ?: return@withContext emptyList()
        entities.mapNotNull { item ->
            runCatching {
                val v = item.asJsonObject.getAsJsonObject("vehicle") ?: return@runCatching null
                val p = v.getAsJsonObject("position") ?: return@runCatching null
                val trip = v.getAsJsonObject("trip")
                val vehicle = v.getAsJsonObject("vehicle")
                LiveBus(
                    id = item.asJsonObject.get("id")?.asString.orEmpty(),
                    label = vehicle?.get("label")?.asString ?: vehicle?.get("id")?.asString ?: "APSRTC bus",
                    routeId = trip?.get("routeId")?.asString.orEmpty(),
                    tripId = trip?.get("tripId")?.asString.orEmpty(),
                    lat = p.get("latitude")?.asDouble ?: return@runCatching null,
                    lon = p.get("longitude")?.asDouble ?: return@runCatching null,
                    bearing = p.get("bearing")?.asDouble ?: 0.0,
                    timestamp = v.get("timestamp")?.asLong ?: 0L,
                    speed = p.get("speed")?.asDouble ?: 0.0,
                    routeName = routes.firstOrNull { it.id == trip?.get("routeId")?.asString.orEmpty() }?.shortName.orEmpty(),
                    routeLongName = routes.firstOrNull { it.id == trip?.get("routeId")?.asString.orEmpty() }?.longName.orEmpty(),
                    startTime = trip?.get("startTime")?.asString.orEmpty()
                )
            }.getOrNull()
        }
    }

    suspend fun arrivals(): List<Arrival> = withContext(Dispatchers.IO) {
        val root = JsonParser.parseString(get("$base/apsrtc/rt/trip_updates.json")).asJsonObject
        val entities = root.getAsJsonArray("entity") ?: return@withContext emptyList()
        buildList {
            entities.forEach { item ->
                runCatching {
                    val update = item.asJsonObject.getAsJsonObject("tripUpdate") ?: return@runCatching
                    val trip = update.getAsJsonObject("trip")
                    val tripId = trip?.get("tripId")?.asString.orEmpty()
                    val routeId = trip?.get("routeId")?.asString.orEmpty()
                    update.getAsJsonArray("stopTimeUpdate")?.forEach { stopItem ->
                        val stop = stopItem.asJsonObject
                        val event = stop.getAsJsonObject("arrival") ?: stop.getAsJsonObject("departure")
                        add(Arrival(tripId, routeId, stop.get("stopId")?.asString.orEmpty(),
                            event?.get("time")?.asLong, event?.get("delay")?.asInt))
                    }
                }
            }
        }
    }

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun toggleFavorite(routeId: String): Set<String> {
        val next = favorites().toMutableSet()
        if (!next.add(routeId)) next.remove(routeId)
        prefs.edit().putStringSet("favorites", next).apply()
        return next
    }
}
