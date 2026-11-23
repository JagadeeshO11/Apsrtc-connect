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
    var selectedGtfsRouteType by remember { mutableStateOf<Int?>(null) }
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
            updateStatus = if (updateInfo == null) "Already using the latest version." else "A new version is available: " + updateInfo!!.version
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
        runCatching { val data = repo.loadStaticData(); stops = data.first; routes = data.second; staticError = "" }
            .onFailure { staticError = it.message ?: "Could not load APSRTC stop data" }
        loading = false
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
                JourneyResultsScreen(Modifier.padding(padding), origin, destination, routeResults, selectedGtfsRouteType, favorites,
                    { id -> favorites = repo.toggleFavorite(id) },
                    { journey -> selectedJourney = journey; serviceDetailPage = true })
            } else when (tab) {
                0 -> SearchScreen(
                    Modifier.padding(padding), telugu, loading, staticError, stops, originText, destinationText,
                    { originText = it; origin = null; routeResults = emptyList(); routeSearchError = "" }, { destinationText = it; destination = null; routeResults = emptyList(); routeSearchError = "" },
                    { origin = it; originText = it.name }, { destination = it; destinationText = it.name },
                    origin, destination, routeResults, searchingRoutes, routeSearchError,
                    { resultsPage = true; selectedGtfsRouteType = null; scope.launch {
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
                1 -> MapScreen(Modifier.padding(padding), buses, routes, origin, destination, selectedBus, lastUpdated) { selectedBus = it }
                2 -> LiveScreen(Modifier.padding(padding), status, buses, arrivals, stops, telugu, lastUpdated, error)
                else -> FavoritesScreen(Modifier.padding(padding), favorites, routes, telugu) { id -> favorites = repo.toggleFavorite(id) }
            }
        }
        if (showRouteFilters && resultsPage && !serviceDetailPage) {
            val availableTypes = routeResults.map { it.route.routeType }.distinct().sorted()
            AlertDialog(
                onDismissRequest = { showRouteFilters = false },
                title = { Text(if (telugu) "బస్సు ఫిల్టర్లు" else "Filter services") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Filter uses GTFS route_type from the timetable, not guessed route-name keywords.", style = MaterialTheme.typography.bodySmall)
                        FilterChip(selected = selectedGtfsRouteType == null, onClick = { selectedGtfsRouteType = null }, label = { Text("All services (${routeResults.size})") })
                        availableTypes.forEach { type ->
                            val label = when (type) { 0 -> "Tram (GTFS 0)"; 1 -> "Subway / Metro (GTFS 1)"; 2 -> "Rail (GTFS 2)"; 3 -> "Bus (GTFS 3)"; 4 -> "Ferry (GTFS 4)"; 5 -> "Cable tram (GTFS 5)"; 6 -> "Aerial lift (GTFS 6)"; 7 -> "Funicular (GTFS 7)"; else -> "GTFS type $type" }
                            FilterChip(selected = selectedGtfsRouteType == type, onClick = { selectedGtfsRouteType = type }, label = { Text("$label • ${routeResults.count { it.route.routeType == type }}") })
                        }
                        Text("APSRTC service classes (Palle Velugu, Express, Super Luxury, etc.) are not provided as a dedicated field in this feed. They are not guessed from names.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = { TextButton(onClick = { showRouteFilters = false }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { selectedGtfsRouteType = null }) { Text("Clear") } }
            )
        }
        if (updateDialogVisible) {
            AlertDialog(
                onDismissRequest = { if (!downloadingUpdate) updateDialogVisible = false },
                title = { Text("APSRTC Connect update") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(updateStatus)
                        updateInfo?.let { Text("Latest version: " + it.version, fontWeight = FontWeight.SemiBold) }
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
                                    val id = UpdateManager.download(context, url)
                                    downloadedUpdate = UpdateManager.waitForDownload(context, id)
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
    selectedRouteType: Int?, favorites: Set<String>, toggleFavorite: (String) -> Unit,
    onSelectService: (JourneyOption) -> Unit
) {
    val filtered = results.filter { selectedRouteType == null || it.route.routeType == selectedRouteType }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text("ONLY TRIPS THAT STOP AT BOTH SELECTED STOPS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("${origin?.name.orEmpty()} → ${destination?.name.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${filtered.size} of ${results.size} scheduled trips • GTFS timetable", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (results.isEmpty()) item { Text("No scheduled trip visits the origin first and destination later in the same trip.") }
        if (filtered.isEmpty() && results.isNotEmpty()) item { Text("No trips match this GTFS route-type filter.") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopAutocomplete(label: String, value: String, stops: List<Stop>, onValue: (String) -> Unit, onSelect: (Stop) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matches = remember(value, stops) { if (value.trim().length < 2) emptyList() else stops.filter { it.name.contains(value.trim(), true) || it.id.contains(value.trim(), true) }.take(12) }
    ExposedDropdownMenuBox(expanded = expanded && matches.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(value, { typed -> onValue(typed); expanded = true }, Modifier.fillMaxWidth().menuAnchor(), label = { Text(label) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) })
        ExposedDropdownMenu(expanded = expanded && matches.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matches.forEach { stop -> DropdownMenuItem(text = { Text(stop.name) }, onClick = { onSelect(stop); expanded = false }) }
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
                        supportingContent = { Text("Route ${arrival.routeId.ifBlank { "unmatched" }} • ${time}") },
                        trailingContent = { arrival.delay?.let { Text(if (it >= 0) "+${it / 60}m" else "${it / 60}m") } }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    modifier: Modifier, buses: List<LiveBus>, routes: List<BusRoute>,
    origin: Stop?, destination: Stop?, selected: LiveBus?, lastUpdated: Long, onSelect: (LiveBus?) -> Unit
) {
    var busType by remember { mutableStateOf("All") }
    var sortBy by remember { mutableStateOf("Latest update") }
    val routeById = remember(routes) { routes.associateBy { it.id } }
    fun category(bus: LiveBus): String {
        val name = (routeById[bus.routeId]?.shortName.orEmpty() + " " + routeById[bus.routeId]?.longName.orEmpty() + " " + bus.routeLongName).lowercase()
        return when {
            listOf("pallevelugu", "palle velugu", "ordinary", "city", "metro").any { it in name } -> "Ordinary"
            listOf("super luxury", "ultra deluxe", "amaravati", "dolphin", "star").any { it in name } -> "Premium"
            listOf("express", "deluxe", "garuda", "indra", "vennela").any { it in name } -> "Express"
            else -> "Other"
        }
    }
    val filtered = buses.filter { busType == "All" || category(it) == busType }
        .let { list -> when (sortBy) {
            "Fastest" -> list.sortedByDescending { it.speed }
            "Route" -> list.sortedBy { it.routeId }
            else -> list.sortedByDescending { it.timestamp }
        } }
    Column(modifier.fillMaxSize()) {
        Text("Live buses • one map", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (origin != null && destination != null) "Journey: ${origin.name} → ${destination.name}" else "All available live vehicle positions", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        Text(if (lastUpdated > 0L) "GPS last updated ${SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()).format(Date(lastUpdated * 1000))}" else "Waiting for first GPS timestamp", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Ordinary", "Express", "Premium", "Other").forEach { type ->
                FilterChip(selected = busType == type, onClick = { busType = type }, label = { Text(type) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            listOf("Latest update", "Fastest", "Route").forEach { option ->
                FilterChip(selected = sortBy == option, onClick = { sortBy = option }, label = { Text(option) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled = true
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.openstreetmap.org", mapHtml(filtered, origin, destination), "text/html", "UTF-8", null)
            } },
            update = { it.loadDataWithBaseURL("https://www.openstreetmap.org", mapHtml(filtered, origin, destination), "text/html", "UTF-8", null) }
        )
        if (selected != null) {
            val route = routeById[selected.routeId]
            ListItem(
                headlineContent = { Text(selected.label) },
                supportingContent = { Text("${route?.shortName ?: selected.routeId.ifBlank { "Route unavailable" }} • ${category(selected)} • ${if (selected.timestamp > 0) SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(selected.timestamp * 1000)) else "Time unavailable"}") },
                trailingContent = { Text(if (selected.speed > 0) "${"%.0f".format(selected.speed * 3.6)} km/h" else "GPS") }
            )
        }
        Text("${filtered.size} / ${buses.size} live positions • Auto-refresh every 30 seconds • © OpenStreetMap", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun mapHtml(buses: List<LiveBus>, origin: Stop?, destination: Stop?): String {
    val gson = com.google.gson.Gson()
    val points = buses.map { bus -> mapOf(
        "lat" to bus.lat, "lon" to bus.lon, "label" to bus.label,
        "route" to bus.routeId, "speed" to bus.speed, "time" to bus.timestamp
    ) }
    val markers = gson.toJson(points)
    val journey = gson.toJson(listOfNotNull(origin?.let { listOf(it.lat, it.lon, it.name, "From") }, destination?.let { listOf(it.lat, it.lon, it.name, "To") }))
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>html,body,#map{height:100%;margin:0}.bus-dot{background:#b3261e;border:2px solid white;border-radius:50%;width:13px;height:13px;box-shadow:0 1px 5px #555}</style></head><body><div id="map"></div><script>
    const pts=$markers; const journey=$journey; const map=L.map('map').setView([16.5,80.6],7);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'© OpenStreetMap'}).addTo(map);
    const bounds=[];
    pts.forEach(p=>{const m=L.marker([p.lat,p.lon],{icon:L.divIcon({className:'',html:'<div class="bus-dot"></div>',iconSize:[17,17],iconAnchor:[8,8]})}).addTo(map);
      m.bindPopup('<b>'+String(p.label).replace(/[&<>"]/g,'')+'</b><br>Route: '+String(p.route||'Not matched')+'<br>Speed: '+(p.speed>0?(p.speed*3.6).toFixed(0)+' km/h':'Unavailable')+'<br>Updated: '+(p.time?new Date(p.time*1000).toLocaleTimeString():'Unknown')); bounds.push([p.lat,p.lon]);});
    journey.forEach((p,i)=>{L.marker([p[0],p[1]]).addTo(map).bindPopup('<b>'+p[3]+': '+p[2]+'</b>');bounds.push([p[0],p[1]]);});
    if(journey.length===2){L.polyline([[journey[0][0],journey[0][1]],[journey[1][0],journey[1][1]]],{color:'#315c8a',dashArray:'6 8',weight:4}).addTo(map);}
    if(bounds.length){map.fitBounds(bounds,{padding:[24,24],maxZoom:12});}
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
