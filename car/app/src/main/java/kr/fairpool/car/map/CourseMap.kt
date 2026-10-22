package kr.fairpool.car.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kr.fairpool.car.data.Place
import kr.fairpool.car.trip.Passenger
import kr.fairpool.car.trip.Stop
import kr.fairpool.car.trip.distanceKm
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconOffset
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private const val GRAY = 0xFFB0B8C1.toInt()

/** One marker: who and what (pickup circle, drop-off pin, start/end tag). */
private data class Mark(val place: Place, val kind: String, val label: String, val done: Boolean)

/**
 * The trip course drawn by FairPool itself (the stock navi cannot be embedded): the road line through
 * the start and every stop, a green circle where each person gets in and a blue pin where they get out.
 */
@Composable
fun CourseMap(origin: Place?, plan: List<Stop>, passengers: List<Passenger>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                restyle(s)
                map = m
                style = s
            }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val marks = buildList {
        if (origin != null) {
            add(Mark(origin, "start", "출발", false))
            passengers.filter { it.pickup == null || origin.distanceKm(it.pickup) <= 0.3 }
                .forEach { add(Mark(origin, "pickup", it.name.take(1), plan.any { st -> st.done })) }
        }
        for (st in plan) when (st.kind) {
            "pickup" -> add(Mark(st.place, "pickup", st.name.take(1), st.done))
            "dropoff" -> add(Mark(st.place, "dropoff", st.name.take(1), st.done))
            else -> add(Mark(st.place, "end", "도착", st.done))
        }
    }
    val points = listOfNotNull(origin) + plan.map { it.place }

    LaunchedEffect(style, points) {
        val s = style ?: return@LaunchedEffect
        val line = runCatching { roadLine(points) }.getOrNull()
            ?: points.map { Point.fromLngLat(it.lng, it.lat) }
        ensureActive()
        if (line.size >= 2) setSource(s, "course", FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(line))))
        if (s.getLayer("course-line") == null) addCourseLayers(s)
    }
    LaunchedEffect(style, marks) {
        val s = style ?: return@LaunchedEffect
        setSource(s, "stops", FeatureCollection.fromFeatures(markerFeatures(s, marks, context.resources.displayMetrics.density * 1.35f)))
        if (s.getLayer("stops-layer") == null) {
            s.addLayer(
                SymbolLayer("stops-layer", "stops").withProperties(
                    iconImage(get("icon")), iconAnchor(get("anchor")), iconOffset(get("offset")),
                    iconAllowOverlap(true), iconIgnorePlacement(true)
                )
            )
        }
    }
    LaunchedEffect(map, points) {
        val m = map ?: return@LaunchedEffect
        if (points.size < 2) return@LaunchedEffect
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lng)) } }.build()
        val d = context.resources.displayMetrics.density
        val side = (120 * d).toInt()
        val fitted = m.getCameraForLatLngBounds(bounds, intArrayOf(side, (90 * d).toInt(), side, (80 * d).toInt())) ?: return@LaunchedEffect
        m.cameraPosition = CameraPosition.Builder(fitted).tilt(20.0).build()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun setSource(style: Style, id: String, data: FeatureCollection) {
    val src = style.getSourceAs<GeoJsonSource>(id)
    if (src != null) src.setGeoJson(data) else style.addSource(GeoJsonSource(id, data))
}

private fun addCourseLayers(style: Style) {
    val below = style.layers.firstOrNull { it is SymbolLayer }?.id
    val casing = LineLayer("course-casing", "course").withProperties(
        lineColor("#ffffff"), lineWidth(interpolate(linear(), zoom(), stop(11, 7f), stop(16, 20f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
    )
    val line = LineLayer("course-line", "course").withProperties(
        lineColor("#3478f6"), lineWidth(interpolate(linear(), zoom(), stop(11, 4f), stop(16, 12f))),
        lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
    )
    if (below != null) {
        style.addLayerBelow(casing, below)
        style.addLayerBelow(line, below)
    } else {
        style.addLayer(casing)
        style.addLayer(line)
    }
}

/** Markers that share a spot (everyone who gets in at the start) are spread sideways. */
private fun markerFeatures(style: Style, marks: List<Mark>, density: Float): List<Feature> {
    val groups = marks.groupBy { "%.4f,%.4f".format(Locale.US, it.place.lat, it.place.lng) }
    return marks.mapIndexed { n, mk ->
        val group = groups.getValue("%.4f,%.4f".format(Locale.US, mk.place.lat, mk.place.lng))
        val i = group.indexOfFirst { it === marks[n] }
        val dx = (i - (group.size - 1) / 2f) * 64f
        val icon = "${mk.kind}-${mk.label}-${mk.done}"
        if (style.getImage(icon) == null) {
            val bmp = when (mk.kind) {
                "pickup" -> MarkerIcons.circle(mk.label, if (mk.done) GRAY else MarkerIcons.GREEN, density)
                "dropoff" -> MarkerIcons.pin(mk.label, if (mk.done) GRAY else MarkerIcons.BLUE, density)
                else -> MarkerIcons.tag(mk.label, MarkerIcons.DARK, density)
            }
            style.addImage(icon, bmp)
        }
        Feature.fromGeometry(Point.fromLngLat(mk.place.lng, mk.place.lat)).apply {
            addStringProperty("icon", icon)
            addStringProperty("anchor", if (mk.kind == "dropoff") "bottom" else "center")
            addProperty("offset", JsonArray().apply { add(dx); add(0f) })
        }
    }
}

/** Road geometry through the points in order, from the public OSRM demo router. */
private suspend fun roadLine(points: List<Place>): List<Point>? = withContext(Dispatchers.IO) {
    if (points.size < 2) return@withContext null
    val coords = points.joinToString(";") { "%.6f,%.6f".format(Locale.US, it.lng, it.lat) }
    val conn = URL("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson")
        .openConnection() as HttpURLConnection
    conn.connectTimeout = 6000
    conn.readTimeout = 10000
    conn.setRequestProperty("User-Agent", "FairPool-car-demo")
    try {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            .getJSONObject("geometry").getJSONArray("coordinates")
        (0 until arr.length()).map { i -> arr.getJSONArray(i).let { Point.fromLngLat(it.getDouble(0), it.getDouble(1)) } }
    } finally {
        conn.disconnect()
    }
}

/** Pale FairPool palette over the positron style, Korean labels, no POI clutter. */
private fun restyle(style: Style) {
    val roadPattern = Regex("road|highway|street|minor|major|trunk|motorway|primary|secondary|tertiary")
    for (layer in style.layers) {
        when (layer) {
            is SymbolLayer ->
                if (layer.id.contains("poi") || layer.id.contains("housenum")) layer.setProperties(visibility(Property.NONE))
                else layer.setProperties(textField(coalesce(get("name:ko"), get("name"))))
            is LineLayer ->
                if (roadPattern.containsMatchIn(layer.id) && !layer.id.contains("casing")) layer.setProperties(lineColor("#ffffff"))
            is FillLayer -> when {
                layer.id.contains("water") -> layer.setProperties(fillColor("#cfe3f7"))
                layer.id.contains("park") -> layer.setProperties(fillColor("#dcefdc"))
                layer.id.contains("landuse") || layer.id.contains("residential") -> layer.setProperties(fillColor("#eceef3"))
            }
            is BackgroundLayer -> layer.setProperties(backgroundColor("#f1f2f6"))
        }
    }
}
