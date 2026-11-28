package `in`.apsrtc.connect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val APSRTC_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun MapLibreBusMap(
    modifier: Modifier = Modifier,
    buses: List<LiveBus>,
    tripStops: List<Stop>,
    origin: Stop?,
    destination: Stop?,
    selected: LiveBus?,
    onSelect: (LiveBus?) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var initialCameraSet by remember { mutableStateOf(false) }
    var lastCameraSelection by remember { mutableStateOf<String?>(null) }
    val latestBuses by rememberUpdatedState(buses)
    val latestOnSelect by rememberUpdatedState(onSelect)

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapLibre.getInstance(ctx.applicationContext)
                MapView(ctx).apply {
                    onCreate(null)
                    mapView = this
                    getMapAsync { readyMap ->
                        readyMap.uiSettings.setAttributionEnabled(true)
                        readyMap.uiSettings.setLogoEnabled(true)
                        readyMap.setTileCacheEnabled(true)
                        readyMap.setPrefetchesTiles(true)
                        readyMap.setStyle(Style.Builder().fromUri(APSRTC_MAP_STYLE)) {
                            map = readyMap
                            mapReady = true
                        }
                    }
                }
            },
            update = { }
        )
    
        if (!mapReady) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 3.dp
            ) {
                Text("Loading OpenStreetMap…", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            shape = MaterialTheme.shapes.small,
            shadowElevation = 2.dp
        ) {
            Text(
                "© OpenStreetMap contributors • OpenFreeMap",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val view = mapView
        if (view == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // MapView may be created after the Activity has entered STARTED/RESUMED.
            // Synchronize its lifecycle immediately instead of waiting for another event.
            val currentState = lifecycleOwner.lifecycle.currentState
            if (currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
            if (currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                runCatching { view.onPause() }
                runCatching { view.onStop() }
                runCatching { view.onDestroy() }
                mapView = null
                map = null
                mapReady = false
                initialCameraSet = false
            }
        }
    }

    LaunchedEffect(mapReady, buses, tripStops, origin, destination, selected?.id) {
        val readyMap = map ?: return@LaunchedEffect
        if (!mapReady) return@LaunchedEffect

        val validBuses = buses.filter { isValidMapPoint(it.lat, it.lon) }
        val validStops = tripStops.filter { isValidMapPoint(it.lat, it.lon) }
        val validEnds = listOfNotNull(origin, destination).filter { isValidMapPoint(it.lat, it.lon) }

        readyMap.clear()
        validBuses.forEach { bus ->
            readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(bus.lat, bus.lon))
                    .title(bus.label)
                    .snippet("bus:${bus.id}")
            )
        }
        validStops.forEachIndexed { index, stop ->
            readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title("${index + 1}. ${stop.name}")
                    .snippet("stop:${stop.id}")
            )
        }
        validEnds.forEach { stop ->
            readyMap.addMarker(
                MarkerOptions()
                    .position(LatLng(stop.lat, stop.lon))
                    .title(stop.name)
                    .snippet("endpoint:${stop.id}")
            )
        }
        if (validStops.size > 1) {
            readyMap.addPolyline(
                PolylineOptions()
                    .addAll(validStops.map { LatLng(it.lat, it.lon) })
                    .color(android.graphics.Color.rgb(8, 127, 155))
                    .width(4.5f)
            )
        }
        readyMap.setOnMarkerClickListener { marker ->
            val busId = marker.snippet?.takeIf { it.startsWith("bus:") }?.removePrefix("bus:")
            val bus = busId?.let { id -> latestBuses.firstOrNull { it.id == id } }
            if (bus != null) {
                latestOnSelect(bus)
                true
            } else {
                false
            }
        }

        val selectedPoint = selected?.takeIf { isValidMapPoint(it.lat, it.lon) }
        if (selectedPoint != null) {
            if (lastCameraSelection != selectedPoint.id) {
                readyMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(selectedPoint.lat, selectedPoint.lon), 13.0),
                    350
                )
                lastCameraSelection = selectedPoint.id
            }
        } else {
            lastCameraSelection = null
            if (!initialCameraSet) {
            val cameraPoints = (validBuses.map { LatLng(it.lat, it.lon) } +
                validStops.map { LatLng(it.lat, it.lon) } +
                validEnds.map { LatLng(it.lat, it.lon) }).distinct()
            when {
                cameraPoints.size > 1 -> {
                    val bounds = LatLngBounds.Builder().apply {
                        cameraPoints.forEach { include(it) }
                    }.build()
                    readyMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56), 350)
                    initialCameraSet = true
                }
                cameraPoints.size == 1 -> {
                    readyMap.animateCamera(CameraUpdateFactory.newLatLngZoom(cameraPoints.first(), 10.0), 350)
                    initialCameraSet = true
                }
                else -> {
                    readyMap.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(LatLng(16.5, 80.6))
                        .zoom(6.2)
                        .build()
                }
            }
            }
        }
    }
}

private fun isValidMapPoint(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -85.0..85.0 && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)
