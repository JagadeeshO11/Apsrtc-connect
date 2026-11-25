package `in`.apsrtc.connect

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.location.LocationServices
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { APSRTCApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun APSRTCApp() {
    val context = LocalContext.current
    val repo = remember { TransitRepository(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var appVisible by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            appVisible = event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var telugu by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var resultsPage by remember { mutableStateOf(false) }
    var serviceDetailPage by remember { mutableStateOf(false) }
    var selectedJourney by remember { mutableStateOf<JourneyOption?>(null) }
    var showRouteFilters by remember { mutableStateOf(false) }
    var selectedGtfsRouteType by remember { mutableStateOf<String?>(null) }
    var selectedTimeWindow by remember { mutableStateOf<String?>(null) }
    var stops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var routes by remember { mutableStateOf<List<BusRoute>>(emptyList()) }
    var buses by remember { mutableStateOf(repo.cachedLiveBuses()) }
    var status by remember { mutableStateOf("Loading cached or live APSRTC feed…") }
    var error by remember { mutableStateOf("") }
    var staticError by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf<Stop?>(null) }
    var destination by remember { mutableStateOf<Stop?>(null) }
    var originText by remember { mutableStateOf("") }
    var destinationText by remember { mutableStateOf("") }
    var routeResults by remember { mutableStateOf<List<JourneyOption>>(emptyList()) }
    var searchingRoutes by remember { mutableStateOf(false) }
    var routeSearchError by remember { mutableStateOf("") }
    var nearbyStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) fusedLocation.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) nearbyStops = stops.sortedBy { stop ->
                val results = FloatArray(1)
                android.location.Location.distanceBetween(loc.latitude, loc.longitude, stop.lat, stop.lon, results)
                results[0]
            }.take(10)
        }
    }
    var arrivals by remember { mutableStateOf(repo.cachedArrivals()) }
    var lastUpdated by remember { mutableStateOf(context.getSharedPreferences("apsrtc_connect", android.content.Context.MODE_PRIVATE).getLong("last_live_success", 0L)) }
    var favorites by remember { mutableStateOf(repo.favorites()) }
    var selectedBus by remember { mutableStateOf<LiveBus?>(null) }
    var selectedTripStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var updateInfo by remember { mutableStateOf<AppUpdate?>(null) }
    var downloadedUpdate by remember { mutableStateOf<java.io.File?>(null) }
    var updateDialogVisible by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("Checking GitHub for updates…") }
    var downloadingUpdate by remember { mutableStateOf(false) }

    suspend fun checkForAppUpdate(showUpToDate: Boolean) {
        val updatePrefs = context.getSharedPreferences("apsrtc_connect", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!showUpToDate && now - updatePrefs.getLong("last_update_check", 0L) < 12 * 60 * 60 * 1000L) return
        updateStatus = "Checking GitHub for updates…"
        try {
            updateInfo = UpdateManager.checkLatest()
            downloadedUpdate = updateInfo?.let { UpdateManager.cachedApk(context, it) }
            updateStatus = when {
                updateInfo == null -> "Already using the latest version. No APK downloaded."
                downloadedUpdate != null -> "This update is already on your phone. No data will be downloaded again."
                else -> "A new version is available: " + updateInfo!!.version
            }
            if (updateInfo != null || showUpToDate) updateDialogVisible = true
        } catch (e: Exception) {
            updateStatus = e.message ?: "Could not check for updates."
            if (showUpToDate) updateDialogVisible = true
        } finally {
            updatePrefs.edit().putLong("last_update_check", now).apply()
        }
    }

    fun refreshLive() {
        scope.launch {
            if (refreshing) return@launch
            refreshing = true
            try {
            val failures = mutableListOf<String>()
            var positionsOk = false
            runCatching { repo.liveBuses(routes) }
                .onSuccess { fresh ->
                    buses = fresh; repo.saveLiveBuses(fresh); positionsOk = true
                    val newest = fresh.maxOfOrNull { it.timestamp } ?: 0L
                    if (newest > 0L) { lastUpdated = newest; context.getSharedPreferences("apsrtc_connect", android.content.Context.MODE_PRIVATE).edit().putLong("last_live_success", newest).apply() }
                }
                .onFailure { failures += "Vehicle feed: ${it.message ?: "network error"}"; if (buses.isEmpty()) buses = repo.cachedLiveBuses() }
            runCatching { repo.arrivals() }
                .onSuccess { fresh -> arrivals = fresh; repo.saveArrivals(fresh) }
                .onFailure { failures += "Arrival feed: ${it.message ?: "network error"}"; if (arrivals.isEmpty()) arrivals = repo.cachedArrivals() }
            status = if (lastUpdated > 0L) {
                "Vehicle feed timestamp ${SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(lastUpdated * 1000))} • ${buses.size} vehicles"
            } else if (positionsOk) "Vehicle positions loaded" else "Showing saved data"
            if (status.startsWith("Connecting")) status = if (positionsOk) "Live vehicle positions loaded" else "Showing saved data"
            error = failures.joinToString("  •  ")
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Suggestions always come from the offline GTFS stop catalogue; location is optional.
        runCatching { val data = repo.loadStaticData(); stops = data.first; routes = data.second; staticError = "" }
            .onFailure { staticError = it.message ?: "Could not load APSRTC stop data" }
        loading = false
        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            runCatching {
                fusedLocation.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null && stops.isNotEmpty()) {
                        nearbyStops = stops.asSequence().filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }
                            .sortedBy { stop ->
                                val result = FloatArray(1)
                                android.location.Location.distanceBetween(loc.latitude, loc.longitude, stop.lat, stop.lon, result)
                                result[0]
                            }.take(10).toList()
                    }
                }
            }
        }
        else {
            // Ask once on first launch. Stop search/autocomplete does not depend on accepting this.
            locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        delay(1800)
        checkForAppUpdate(false)
    }

    LaunchedEffect(appVisible) {
        if (appVisible) {
            refreshLive()
            while (true) {
                delay(65_000)
                if (appVisible) refreshLive()
            }
        }
    }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF123B43),
        secondary = androidx.compose.ui.graphics.Color(0xFFE09F3E),
        tertiary = androidx.compose.ui.graphics.Color(0xFF007F86),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFFD8ECE9),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFEBC7),
        background = androidx.compose.ui.graphics.Color(0xFFF4F7F5)
    )) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (serviceDetailPage) "Selected service" else if (resultsPage) "Matching buses" else "APSRTC Connect", fontWeight = FontWeight.Bold)
                            Text(if (resultsPage) "${origin?.name.orEmpty()} → ${destination?.name.orEmpty()}" else if (telugu) "ప్రత్యక్ష బస్సులు • స్మార్ట్ ప్రయాణం" else "Live network • Smarter journeys", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    navigationIcon = { if (serviceDetailPage) IconButton(onClick = { serviceDetailPage = false }) { Icon(Icons.Default.ArrowBack, "Back to matching buses") } else if (resultsPage) IconButton(onClick = { resultsPage = false }) { Icon(Icons.Default.ArrowBack, "Back to search") } },
                    actions = {
                        if (resultsPage && !serviceDetailPage) IconButton(onClick = { showRouteFilters = true }) { Icon(Icons.Default.FilterList, "Filter bus services") }
                        TextButton(onClick = { telugu = !telugu }) { Text(if (telugu) "EN" else "తె") }
                        IconButton(onClick = { scope.launch { checkForAppUpdate(true) } }) { Icon(Icons.Default.SystemUpdate, "Check app updates") }
                        IconButton(onClick = { refreshLive() }, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Refresh live buses") }
                    }
                )
            },
            bottomBar = {
                if (!resultsPage) NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Default.Search, null) }, label = { Text(if (telugu) "శోధన" else "Search") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Default.Map, null) }, label = { Text(if (telugu) "మ్యాప్" else "Map") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Default.DirectionsBus, null) }, label = { Text(if (telugu) "లైవ్" else "Live") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Default.Favorite, null) }, label = { Text(if (telugu) "ఇష్టమైనవి" else "Saved") })
                }
            }
        ) { padding ->
            if (serviceDetailPage && selectedJourney != null) {
                ServiceDetailScreen(Modifier.padding(padding), selectedJourney!!)
            } else if (resultsPage) {
                JourneyResultsScreen(Modifier.padding(padding), origin, destination, routeResults, selectedGtfsRouteType, selectedTimeWindow,
                    { selectedGtfsRouteType = it }, { selectedTimeWindow = it }, favorites,
                    { id -> favorites = repo.toggleFavorite(id) },
                    { journey -> selectedJourney = journey; serviceDetailPage = true })
            } else when (tab) {
                0 -> SearchScreen(
                    Modifier.padding(padding), telugu, loading, staticError, stops, originText, destinationText,
                    { originText = it; origin = null; routeResults = emptyList(); routeSearchError = "" }, { destinationText = it; destination = null; routeResults = emptyList(); routeSearchError = "" },
                    { origin = it; originText = it.name }, { destination = it; destinationText = it.name },
                    origin, destination, routeResults, searchingRoutes, routeSearchError,
                    { resultsPage = true; selectedGtfsRouteType = null; selectedTimeWindow = null; scope.launch {
                        val fromStop = origin
                        val toStop = destination
                        if (fromStop == null || toStop == null) { routeSearchError = "Choose both stops from the suggestions."; return@launch }
                        if (fromStop.id == toStop.id) { routeSearchError = "From and To stops must be different."; routeResults = emptyList(); return@launch }
                        searchingRoutes = true; routeSearchError = ""; routeResults = emptyList()
                        try { routeResults = repo.findJourneys(fromStop, toStop, routes, stops)
                            if (routeResults.isEmpty()) routeSearchError = "No scheduled direct buses found for these stops in the current GTFS timetable."
                        } catch (e: Exception) { routeSearchError = e.message ?: "Route search failed. Check your internet connection and retry." }
                        finally { searchingRoutes = false }
                    } },
                    favorites, { id -> favorites = repo.toggleFavorite(id) },
                    nearbyStops, { locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    error,
                    { scope.launch {
                        loading = true
                        staticError = ""
                        try { val data = repo.loadStaticData(); stops = data.first; routes = data.second }
                        catch (e: Exception) { staticError = e.message ?: "Could not load APSRTC stop data" }
                        finally { loading = false }
                    } }
                )
                1 -> MapScreen(Modifier.padding(padding), buses, routes, origin, destination, selectedBus, selectedTripStops, lastUpdated) { bus ->
                    selectedBus = bus
                    selectedTripStops = emptyList()
                    if (bus != null) scope.launch {
                        selectedTripStops = runCatching { repo.tripStops(bus.tripId, stops) }.getOrDefault(emptyList())
                    }
                }
                2 -> LiveScreen(Modifier.padding(padding), status, buses, arrivals, stops, telugu, lastUpdated, error)
                else -> FavoritesScreen(Modifier.padding(padding), favorites, routes, telugu) { id -> favorites = repo.toggleFavorite(id) }
            }
        }
        if (showRouteFilters && resultsPage && !serviceDetailPage) {
            // Keep APSRTC service categories visible even when a particular route name is missing from this GTFS result.
            val availableTypes = listOf(
                "Garuda Plus", "Garuda", "Indra", "Amaravathi", "Vennela",
                "Super Luxury", "Ultra Palle Velugu", "Palle Velugu",
                "Ultra Deluxe", "Deluxe", "Metro Express", "Express",
                "Semi Luxury", "City Ordinary", "AC Service", "Other"
            )
            AlertDialog(
                onDismissRequest = { showRouteFilters = false },
                title = { Text(if (telugu) "బస్సు ఫిల్టర్లు" else "Filter services") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filter by APSRTC service names found in the route feed.", style = MaterialTheme.typography.bodySmall)
                        FilterChip(selected = selectedGtfsRouteType == null, onClick = { selectedGtfsRouteType = null }, label = { Text("All services (${routeResults.size})") })
                        availableTypes.forEach { type ->
                            FilterChip(selected = selectedGtfsRouteType == type, onClick = { selectedGtfsRouteType = type }, label = { Text("$type • ${routeResults.count { apsrtcServiceClass(it.route) == type }}") })
                        }
                        Divider()
                        Text("Departure time", fontWeight = FontWeight.SemiBold)
                        listOf("All times", "00:00–06:00", "06:00–12:00", "12:00–18:00", "18:00–24:00").forEach { window ->
                            val value = if (window == "All times") null else window
                            FilterChip(selected = selectedTimeWindow == value, onClick = { selectedTimeWindow = value }, label = { Text(window) })
                        }
                        Text("Categories are detected only from APSRTC keywords present in route names. Unmatched routes appear under Other.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { showRouteFilters = false }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { selectedGtfsRouteType = null; selectedTimeWindow = null }) { Text("Clear") } }
            )
        }
        if (updateDialogVisible) {
            AlertDialog(
                onDismissRequest = { if (!downloadingUpdate) updateDialogVisible = false },
                title = { Text("APSRTC Connect update") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(updateStatus)
                        updateInfo?.let {
                            Text("Latest version: " + it.version, fontWeight = FontWeight.SemiBold)
                            if (it.sizeBytes > 0L) Text("APK size: %.1f MB".format(it.sizeBytes / 1048576.0), style = MaterialTheme.typography.bodySmall)
                            Text("Only the APK update is downloaded. Your stops, routes and saved data stay on the phone.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (downloadingUpdate) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (downloadedUpdate != null) Text("APK downloaded. Tap Install to continue.")
                    }
                },
                confirmButton = {
                    TextButton(enabled = !downloadingUpdate && (downloadedUpdate != null || updateInfo != null), onClick = {
                        val ready = downloadedUpdate
                        if (ready != null) { UpdateManager.install(context, ready) }
                        else {
                            val url = updateInfo?.downloadUrl ?: return@TextButton
                            scope.launch {
                                downloadingUpdate = true
                                updateStatus = "Downloading update…"
                                try {
                                    val update = updateInfo ?: error("Update information expired. Check again.")
                                    val cached = UpdateManager.cachedApk(context, update)
                                    downloadedUpdate = cached ?: run {
                                        val id = UpdateManager.download(context, update)
                                        UpdateManager.waitForDownload(context, id, update)
                                    }
                                    updateStatus = "Download complete."
                                } catch (e: Exception) { updateStatus = e.message ?: "Download failed." }
                                finally { downloadingUpdate = false }
                            }
                        }
                    }) { Text(if (downloadedUpdate != null) "Install" else "Download APK") }
                },
                dismissButton = { TextButton(onClick = { updateDialogVisible = false }) { Text("Later") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier, telugu: Boolean, loading: Boolean, initialError: String, stops: List<Stop>,
    from: String, to: String, onFrom: (String) -> Unit, onTo: (String) -> Unit,
    selectFrom: (Stop) -> Unit, selectTo: (Stop) -> Unit, origin: Stop?, destination: Stop?,
    results: List<JourneyOption>, searching: Boolean, searchError: String, search: () -> Unit,
    favorites: Set<String>, toggleFavorite: (String) -> Unit,
    nearbyStops: List<Stop>, findNearby: () -> Unit, error: String, retryStops: () -> Unit
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(if (telugu) "మీ ప్రయాణాన్ని ప్లాన్ చేయండి" else "Plan your journey", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        Text(if (telugu) "APSRTC స్టాప్‌ల మధ్య షెడ్యూల్ బస్సులు" else "Search scheduled APSRTC services between stops", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        StopAutocomplete(if (telugu) "ఎక్కడి నుంచి?" else "From", from, stops, onFrom, selectFrom)
        Spacer(Modifier.height(10.dp))
        StopAutocomplete(if (telugu) "ఎక్కడికి?" else "To", to, stops, onTo, selectTo)
        if (origin != null && destination != null) {
            Card(Modifier.fillMaxWidth().padding(top = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ROUTE PREVIEW", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("${origin.name}  →  ${destination.name}", fontWeight = FontWeight.SemiBold)
                    Text(if (searching) "Checking scheduled services…" else "${results.size} matching scheduled trips", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(if (stops.isEmpty() && loading) "Loading APSRTC stops and routes…" else "Type at least 2 letters and choose a stop from the suggestions.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = findNearby, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.MyLocation, null); Spacer(Modifier.width(6.dp)); Text(if (telugu) "దగ్గరలోని స్టాప్‌లు" else "Nearby stops")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = search, enabled = origin != null && destination != null && !searching && !loading, modifier = Modifier.weight(1f)) {
                if (searching) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(6.dp)) }
                Text(if (telugu) "బస్సులు వెతకండి" else "Find buses")
            }
        }
        if (nearbyStops.isNotEmpty()) {
            Text("Nearby stops", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(nearbyStops.take(5).joinToString("  •  ") { it.name }, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        if (initialError.isNotBlank()) {
            Text(if (telugu) "స్టాప్‌ల జాబితా లోడ్ కాలేదు" else "Stop list could not load: $initialError", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retryStops) { Text(if (telugu) "మళ్లీ ప్రయత్నించండి" else "Retry stop data") }
        }
        if (error.isNotBlank()) Text(if (telugu) "లైవ్ అప్‌డేట్స్ తాత్కాలికంగా అందుబాటులో లేవు" else "Live updates temporarily unavailable: $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (searchError.isNotBlank()) {
            Text(searchError, color = if (results.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Found ${results.size} scheduled trips. Tap Find buses to open the full results page.", style = MaterialTheme.typography.bodySmall)
        } else if (origin != null && destination != null && !searching && searchError.isBlank()) {
            Text("Choose Find buses to load matching departures.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun JourneyResultsScreen(
    modifier: Modifier, origin: Stop?, destination: Stop?, results: List<JourneyOption>,
    selectedRouteType: String?, selectedTimeWindow: String?, onRouteTypeChange: (String?) -> Unit, onTimeWindowChange: (String?) -> Unit, favorites: Set<String>, toggleFavorite: (String) -> Unit,
    onSelectService: (JourneyOption) -> Unit
) {
    var departureSort by remember { mutableStateOf("Earliest first") }
    val filtered = results.filter { journey ->
        val typeMatch = selectedRouteType == null || apsrtcServiceClass(journey.route) == selectedRouteType
        val hour = (((journey.departureMinutes % 1440) + 1440) % 1440) / 60
        val timeMatch = when (selectedTimeWindow) {
            "00:00–06:00" -> hour in 0..5
            "06:00–12:00" -> hour in 6..11
            "12:00–18:00" -> hour in 12..17
            "18:00–24:00" -> hour in 18..23
            else -> true
        }
        typeMatch && timeMatch
    }.let { matches ->
        when (departureSort) {
            "Latest first" -> matches.sortedByDescending { it.departureMinutes }
            "Shortest trip" -> matches.sortedBy { it.durationMinutes }
            else -> matches.sortedBy { it.departureMinutes }
        }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text("ONLY TRIPS THAT STOP AT BOTH SELECTED STOPS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("${origin?.name.orEmpty()} → ${destination?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${filtered.size} of ${results.size} scheduled trips • GTFS timetable", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort:", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                listOf("Earliest first", "Latest first", "Shortest trip").forEach { option ->
                    FilterChip(selected = departureSort == option, onClick = { departureSort = option },
                        label = { Text(option) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }
        if (results.isEmpty()) item { Text("No scheduled trip visits the origin first and destination later in the same trip.") }
        if (filtered.isEmpty() && results.isNotEmpty()) item { Text("No trips match the selected route type and departure-time filters. Clear filters to see all trips.") }
        items(filtered) { journey ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(journey.route.shortName.ifBlank { journey.route.id }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(journey.route.longName.ifBlank { "APSRTC scheduled service" }, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { toggleFavorite(journey.route.id) }) {
                            Icon(if (favorites.contains(journey.route.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Toggle favourite")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(journey.departure, fontWeight = FontWeight.Bold); Text("From selected stop", style = MaterialTheme.typography.labelSmall) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ArrowForward, null); Text("${journey.durationMinutes} min", style = MaterialTheme.typography.labelSmall) }
                        Column(horizontalAlignment = Alignment.End) { Text(journey.arrival, fontWeight = FontWeight.Bold); Text("To selected stop", style = MaterialTheme.typography.labelSmall) }
                    }
                    Text("Stops on this trip", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("●  ${origin?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        journey.intermediateStops.forEach { stop -> Text("│  $stop", style = MaterialTheme.typography.bodySmall) }
                        Text("●  ${destination?.name.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("GTFS route_type: ${journey.route.routeType} • APSRTC service class not specified in feed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (journey.routeStops.size >= 2) "Route map preview • ${journey.routeStops.size} GTFS stops" else "Route map unavailable: missing stop coordinates", style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { onSelectService(journey) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Map, null)
                        Spacer(Modifier.width(8.dp))
                        Text("View service & route map")
                    }
                }
            }
        }
    }
}

@Composable
fun ServiceDetailScreen(modifier: Modifier, journey: JourneyOption) {
    val stops = journey.routeStops
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(journey.route.shortName.ifBlank { journey.route.id }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(journey.route.longName.ifBlank { "APSRTC scheduled service" }, style = MaterialTheme.typography.bodyMedium)
            Text("\${journey.departure} → \${journey.arrival} • \${journey.durationMinutes} min", style = MaterialTheme.typography.titleMedium)
            Text("Map shows only this selected trip segment, from the chosen origin to destination.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (stops.size >= 2 && stops.all { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx -> WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                            request?.isForMainFrame == true
                    }
                    val html = serviceRouteMapHtml(journey)
                    tag = html.hashCode()
                    loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
                } },
                update = { view ->
                    val html = serviceRouteMapHtml(journey)
                    if (view.tag != html.hashCode()) {
                        view.tag = html.hashCode()
                        view.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
                    }
                }
            )
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This GTFS trip does not contain enough valid coordinates to draw a route map.")
            }
        }
        LazyColumn(Modifier.heightIn(max = 260.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
            items(stops) { stop ->
                ListItem(
                    headlineContent = { Text(stop.name) },
                    supportingContent = { Text("GTFS stop ID: \${stop.id}") },
                    leadingContent = { Icon(Icons.Default.Place, null) },
                    trailingContent = { Text(if (stop == stops.first()) "FROM" else if (stop == stops.last()) "TO" else "STOP") }
                )
                HorizontalDivider()
            }
        }
        Text("Scheduled GTFS route preview • Not live bus GPS • © OpenStreetMap", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun serviceRouteMapHtml(journey: JourneyOption): String {
    val points = com.google.gson.Gson().toJson(journey.routeStops.mapIndexed { index, stop ->
        mapOf("lat" to stop.lat, "lon" to stop.lon, "name" to stop.name, "id" to stop.id,
            "kind" to if (index == 0) "From" else if (index == journey.routeStops.lastIndex) "To" else "Stop")
    })
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>html,body,#map{height:100%;margin:0}.start{background:#087f5b;border:2px solid white;border-radius:50%;width:16px;height:16px}.mid{background:#315c8a;border:2px solid white;border-radius:50%;width:11px;height:11px}.end{background:#c2410c;border:2px solid white;border-radius:50%;width:16px;height:16px}</style></head><body><div id="map"></div><script>
    const pts=$points; const map=L.map('map'); L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
    const coords=pts.map(p=>[p.lat,p.lon]); L.polyline(coords,{color:'#176b87',weight:5,opacity:.85}).addTo(map);
    pts.forEach((p,i)=>{const cls=i===0?'start':i===pts.length-1?'end':'mid';const icon=L.divIcon({className:'',html:'<div class="'+cls+'"></div>',iconSize:[20,20],iconAnchor:[10,10]});L.marker([p.lat,p.lon],{icon}).addTo(map).bindPopup('<b>'+String(p.kind)+': '+String(p.name).replace(/[&<>"]/g,'')+'</b><br>GTFS stop: '+String(p.id));});
    if(coords.length) map.fitBounds(coords,{padding:[28,28],maxZoom:13});
    </script></body></html>"""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopAutocomplete(label: String, value: String, stops: List<Stop>, onValue: (String) -> Unit, onSelect: (Stop) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<Stop>>(emptyList()) }
    // Build the searchable catalogue once. Filtering is debounced and runs off the UI thread,
    // so typing stays responsive even with the full APSRTC stop catalogue.
    val searchableStops = remember(stops) {
        stops.map { stop -> stop to "${stop.name.lowercase(Locale.ROOT)} ${stop.id.lowercase(Locale.ROOT)}" }
    }
    LaunchedEffect(value, searchableStops) {
        val query = value.trim().lowercase(Locale.ROOT)
        if (query.length < 2) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(120)
        val found = withContext(Dispatchers.Default) {
            searchableStops.asSequence()
                .filter { (_, searchable) -> searchable.contains(query) }
                .take(12)
                .map { it.first }
                .toList()
        }
        matches = found
    }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(value, { typed -> onValue(typed); expanded = true }, Modifier.fillMaxWidth().menuAnchor(), label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { stop ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stop.name)
                            Text("stop_id: ${stop.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { onSelect(stop); expanded = false }
                )
            }
        }
    }
}

@Composable
fun LiveScreen(modifier: Modifier, status: String, buses: List<LiveBus>, arrivals: List<Arrival>, stops: List<Stop>, telugu: Boolean, lastUpdated: Long, apiError: String) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(if (telugu) "ప్రత్యక్ష బస్సులు" else "Live APSRTC buses", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp)); Text("LIVE NETWORK", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f)); Text("${buses.size} buses", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp)); Text(status, style = MaterialTheme.typography.bodySmall)
                Text(if (lastUpdated > 0L) "Last successful GPS update: ${SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(lastUpdated * 1000))}" else "Last successful GPS update: Not received yet", style = MaterialTheme.typography.labelMedium)
                if (lastUpdated > 0L) {
                    val age = ((System.currentTimeMillis() / 1000L) - lastUpdated).coerceAtLeast(0L)
                    Text(if (age <= 90) "GPS data is fresh" else "GPS data may be stale • ${age / 60} min old", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (apiError.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Some feeds unavailable: $apiError. Other feeds continue independently; saved data is retained.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (buses.isEmpty() && arrivals.isEmpty()) {
            Text(if (telugu) "ప్రస్తుతం లైవ్ సమాచారం అందుబాటులో లేదు" else "No live vehicle positions or arrival updates are currently available.")
        }
        LazyColumn(Modifier.weight(1f)) {
            if (buses.isNotEmpty()) {
                item { Text(if (telugu) "బస్సుల స్థానాలు" else "Vehicle positions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(buses.take(300)) { bus ->
                    ListItem(
                        headlineContent = { Text(bus.label) },
                        supportingContent = { Text(if (bus.routeId.isBlank()) "Route not matched" else "Route: ${bus.routeId}") },
                        leadingContent = { Icon(Icons.Default.DirectionsBus, null) },
                        trailingContent = { Text("GPS") }
                    )
                    HorizontalDivider()
                }
            }
            if (arrivals.isNotEmpty()) {
                item { Text("Arrival predictions", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(arrivals.take(100)) { arrival ->
                    val stopName = stops.firstOrNull { it.id == arrival.stopId }?.name ?: arrival.stopId
                    val time = arrival.arrivalEpoch?.let { SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(it * 1000)) } ?: "Time unavailable"
                    ListItem(
                        headlineContent = { Text(stopName) },
                        supportingContent = { Text("Route ${arrival.routeId.ifBlank { "unmatched" }} • stop_id: ${arrival.stopId} • ${time}") },
                        trailingContent = { arrival.delay?.let { Text(if (it >= 0) "+${it / 60}m" else "${it / 60}m") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun apsrtcServiceClass(route: BusRoute): String {
    val name = "${route.shortName} ${route.longName} ${route.id}".lowercase(Locale.ROOT)
    return when {
        Regex("\\bgaruda\\s*plus\\b").containsMatchIn(name) -> "Garuda Plus"
        Regex("\\bgaruda\\b").containsMatchIn(name) -> "Garuda"
        Regex("\\bindra\\b").containsMatchIn(name) -> "Indra"
        Regex("\\bamaravathi\\b|\\bamaravati\\b").containsMatchIn(name) -> "Amaravathi"
        Regex("\\bvennela\\b").containsMatchIn(name) -> "Vennela"
        Regex("\\bsuper\\s*luxury\\b").containsMatchIn(name) -> "Super Luxury"
        Regex("\\bultra\\s*palle\\s*velugu\\b|\\bultra\\s*pallevelugu\\b").containsMatchIn(name) -> "Ultra Palle Velugu"
        Regex("\\bpalle\\s*velugu\\b|\\bpallevelugu\\b|\\bpv\\b").containsMatchIn(name) -> "Palle Velugu"
        Regex("\\bultra\\s*deluxe\\b").containsMatchIn(name) -> "Ultra Deluxe"
        Regex("\\bdeluxe\\b").containsMatchIn(name) -> "Deluxe"
        Regex("\\bmetro\\s*express\\b").containsMatchIn(name) -> "Metro Express"
        Regex("\\bexpress\\b").containsMatchIn(name) -> "Express"
        Regex("\\bsemi\\s*luxury\\b").containsMatchIn(name) -> "Semi Luxury"
        Regex("\\b(city\\s*ordinary|ordinary)\\b").containsMatchIn(name) -> "City Ordinary"
        Regex("\\bac\\b").containsMatchIn(name) -> "AC Service"
        else -> "Other"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier, buses: List<LiveBus>, routes: List<BusRoute>,
    origin: Stop?, destination: Stop?, selected: LiveBus?, selectedTripStops: List<Stop>,
    lastUpdated: Long, onSelect: (LiveBus?) -> Unit
) {
    var busType by remember { mutableStateOf("All services") }
    var sortBy by remember { mutableStateOf("Latest update") }
    val routeById = remember(routes) { routes.associateBy { it.id } }
    val availableTypes = buses.mapNotNull { bus -> routeById[bus.routeId]?.let { apsrtcServiceClass(it) } }.distinct().sorted()
    val filtered = buses.filter { bus ->
        val serviceClass = routeById[bus.routeId]?.let { apsrtcServiceClass(it) } ?: "Other"
        busType == "All services" || serviceClass == busType
    }.let { list ->
        when (sortBy) {
            "Fastest" -> list.sortedByDescending { it.speed }
            "Route" -> list.sortedBy { it.routeId }
            else -> list.sortedByDescending { it.timestamp }
        }
    }
    val visibleBuses = if (selected != null) listOf(filtered.firstOrNull { it.id == selected.id } ?: selected) else filtered
    Column(modifier.fillMaxSize()) {
        Text("Live APSRTC buses • interactive map", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (selected != null) "Selected trip only • ${selected.tripId.ifBlank { "Trip ID unavailable" }}" else "All available live vehicle positions • select a bus to isolate its trip", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Text(if (lastUpdated > 0L) "GPS last updated ${SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(lastUpdated * 1000))}" else "Waiting for first GPS timestamp", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = busType == "All services", onClick = { busType = "All services" }, label = { Text("All services • ${buses.size}") }, modifier = Modifier.padding(end = 6.dp))
            availableTypes.forEach { type ->
                val busCount = buses.count { bus -> routeById[bus.routeId]?.let { apsrtcServiceClass(it) } == type }
                val routeCount = routes.count { apsrtcServiceClass(it) == type }
                FilterChip(selected = busType == type, onClick = { busType = type }, label = { Text("$type • $busCount buses / $routeCount routes") }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            listOf("Latest update", "Fastest", "Route").forEach { option ->
                FilterChip(selected = sortBy == option, onClick = { sortBy = option }, label = { Text(option) }, modifier = Modifier.padding(end = 6.dp))
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun selectBus(index: Int) {
                            this@apply.post { onSelect(visibleBuses.getOrNull(index)) }
                        }
                    }, "AndroidBus")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                            request?.isForMainFrame == true
                    }
                    val html = mapHtml(visibleBuses, origin, destination, selected, selectedTripStops)
                    tag = html.hashCode()
                    loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                val html = mapHtml(visibleBuses, origin, destination, selected, selectedTripStops)
                if (view.tag != html.hashCode()) {
                    view.addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun selectBus(index: Int) {
                            view.post { onSelect(visibleBuses.getOrNull(index)) }
                        }
                    }, "AndroidBus")
                    view.tag = html.hashCode()
                    view.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
                }
            }
        )
        if (selected != null) {
            val route = routeById[selected.routeId]
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                ListItem(
                    headlineContent = { Text(selected.label) },
                    supportingContent = { Text("Route: ${route?.shortName ?: selected.routeId.ifBlank { "Unavailable" }} • Trip ID: ${selected.tripId.ifBlank { "Unavailable" }} • ${selectedTripStops.size} GTFS stops") },
                    trailingContent = { IconButton(onClick = { onSelect(null) }) { Icon(Icons.Default.Close, "Show all buses") } }
                )
            }
            if (selectedTripStops.isEmpty()) {
                Text("No matching GTFS stop sequence was found for this live trip ID.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Stops on selected trip", Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.heightIn(max = 145.dp).fillMaxWidth()) {
                    items(selectedTripStops) { stop ->
                        ListItem(
                            headlineContent = { Text(stop.name) },
                            supportingContent = { Text("stop_id: ${stop.id}") },
                            leadingContent = { Icon(Icons.Default.Place, null) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        } else if (visibleBuses.isNotEmpty()) {
            Text("Tap a bus marker or row to show only that bus and its trip stops", Modifier.padding(start = 16.dp, top = 6.dp), style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
                items(visibleBuses.take(80)) { bus ->
                    val route = routeById[bus.routeId]
                    ListItem(
                        modifier = Modifier.clickable { onSelect(bus) },
                        headlineContent = { Text(bus.label) },
                        supportingContent = { Text("${route?.shortName ?: bus.routeId.ifBlank { "Route not matched" }} • Trip: ${bus.tripId.ifBlank { "unknown" }} • ${if (bus.speed > 0) "${"%.0f".format(bus.speed * 3.6)} km/h" else "Speed unavailable"}") },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            Text("No live vehicle positions in this filter. Try All or clear the selected bus.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text("${visibleBuses.size} shown / ${buses.size} live positions • Auto-refresh every 65 seconds • © OpenStreetMap", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun mapHtml(buses: List<LiveBus>, origin: Stop?, destination: Stop?, selected: LiveBus?, tripStops: List<Stop>): String {
    val gson = com.google.gson.Gson()
    val points = buses.map { bus -> mapOf(
        "lat" to bus.lat, "lon" to bus.lon, "label" to bus.label,
        "route" to bus.routeId, "tripId" to bus.tripId, "speed" to bus.speed, "time" to bus.timestamp
    ) }
    val markers = gson.toJson(points)
    val journey = gson.toJson(listOfNotNull(
        origin?.takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }?.let { listOf(it.lat, it.lon, it.name, it.id, "From") },
        destination?.takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }?.let { listOf(it.lat, it.lon, it.name, it.id, "To") }
    ))
    val stopsJson = gson.toJson(tripStops.filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat != 0.0 || it.lon != 0.0) }.mapIndexed { index, stop ->
        mapOf("lat" to stop.lat, "lon" to stop.lon, "name" to stop.name, "id" to stop.id, "sequence" to index + 1)
    })
    val selectedTrip = gson.toJson(selected?.tripId.orEmpty())
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
      html,body,#map{height:100%;width:100%;margin:0;background:#eef3f2}
      .bus-dot{background:#b3261e;border:2px solid white;border-radius:50%;width:14px;height:14px;box-shadow:0 1px 6px #555}
      .bus-dot.selected{background:#087f5b;width:19px;height:19px;border:3px solid white}
      .endpoint{background:#315c8a;border:2px solid white;border-radius:50%;width:16px;height:16px}
      .trip-stop{background:#e09f3e;border:2px solid white;border-radius:50%;width:12px;height:12px}
      .map-note{position:absolute;z-index:500;bottom:12px;left:12px;background:#fff;padding:7px 10px;border-radius:9px;font:12px sans-serif;box-shadow:0 1px 5px #777}
    </style></head><body><div id="map"></div><div class="map-note" id="note">Loading map…</div><script>
    const pts=$markers, journey=$journey, stops=$stopsJson, selectedTrip=$selectedTrip;
    const map=L.map('map',{zoomControl:true}).setView([16.5,80.6],7);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
    const bounds=[];
    const safe=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    let shown=0;
    pts.forEach((p,i)=>{
      if(!Number.isFinite(p.lat)||!Number.isFinite(p.lon)||p.lat < -90||p.lat > 90||p.lon < -180||p.lon > 180||(p.lat===0&&p.lon===0)) return;
      const icon=L.divIcon({className:'',html:'<div class="bus-dot selected"></div>',iconSize:[24,24],iconAnchor:[12,12]});
      const m=L.marker([p.lat,p.lon],{icon}).addTo(map);
      m.bindPopup('<b>'+safe(p.label)+'</b><br>Route ID: '+safe(p.route||'Not matched')+'<br>Trip ID: '+safe(p.tripId||'Unavailable')+'<br>Speed: '+(p.speed>0?(p.speed*3.6).toFixed(0)+' km/h':'Unavailable')+'<br>Updated: '+(p.time?new Date(p.time*1000).toLocaleTimeString():'Unknown'));
      m.on('click',()=>{if(window.AndroidBus&&window.AndroidBus.selectBus)window.AndroidBus.selectBus(i);});
      bounds.push([p.lat,p.lon]); shown++;
    });
    if(stops.length){
      const coords=stops.map(p=>[p.lat,p.lon]);
      L.polyline(coords,{color:'#176b87',weight:5,opacity:.85}).addTo(map);
      stops.forEach((p,i)=>{
        const icon=L.divIcon({className:'',html:'<div class="trip-stop"></div>',iconSize:[16,16],iconAnchor:[8,8]});
        L.marker([p.lat,p.lon],{icon}).addTo(map).bindPopup('<b>Stop '+p.sequence+': '+safe(p.name)+'</b><br>stop_id: '+safe(p.id));
        bounds.push([p.lat,p.lon]);
      });
    }
    journey.forEach(p=>{
      if(!Number.isFinite(p[0])||!Number.isFinite(p[1])) return;
      const icon=L.divIcon({className:'',html:'<div class="endpoint"></div>',iconSize:[20,20],iconAnchor:[10,10]});
      L.marker([p[0],p[1]],{icon}).addTo(map).bindPopup('<b>'+safe(p[4])+': '+safe(p[2])+'</b><br>stop_id: '+safe(p[3]));
      bounds.push([p[0],p[1]]);
    });
    document.getElementById('note').textContent=shown+' selected bus marker(s) • '+stops.length+' trip stops • '+(selectedTrip?'trip '+selectedTrip:'select a bus');
    if(bounds.length) map.fitBounds(bounds,{padding:[28,28],maxZoom:13});
    setTimeout(()=>map.invalidateSize(),250);
    </script></body></html>"""
}

@Composable
fun FavoritesScreen(modifier: Modifier, favorites: Set<String>, routes: List<BusRoute>, telugu: Boolean, toggle: (String) -> Unit) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(if (telugu) "ఇష్టమైన రూట్లు" else "Favourite routes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (favorites.isEmpty()) Text(if (telugu) "ఇంకా రూట్లు సేవ్ చేయలేదు" else "No favourites saved yet. Save routes here when you add them.")
        LazyColumn {
            items(favorites.toList()) { id ->
                val route = routes.firstOrNull { it.id == id }
                ListItem(headlineContent = { Text(route?.let { it.shortName.ifBlank { it.longName } } ?: id) },
                    supportingContent = { Text(route?.longName.orEmpty()) },
                    trailingContent = { IconButton(onClick = { toggle(id) }) { Icon(Icons.Default.Favorite, "Remove favourite") } })
            }
        }
    }
}
