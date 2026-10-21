package ar.nahuel.bitacora.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import ar.nahuel.bitacora.BuildConfig
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.MapMetric
import ar.nahuel.bitacora.domain.RouteTrack
import ar.nahuel.bitacora.domain.Segment
import ar.nahuel.bitacora.domain.Zone
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

enum class MapMode { LINES, ZONES }

val RAMP = listOf(Color(0xFF2F6690), Color(0xFF81A4B4), Color(0xFFD8B04A), Color(0xFFD7742A), Color(0xFFB13A0A))

private val dayFormat = DateTimeFormatter.ofPattern("dd/MM")

@Composable
fun RouteMap(
    key: String,
    tracks: List<RouteTrack>,
    zones: List<Zone>,
    mode: MapMode,
    metric: MapMetric,
    breaks: List<Double>,
    dark: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { context ->
            Configuration.getInstance().apply {
                userAgentValue = BuildConfig.APPLICATION_ID
                osmdroidBasePath = File(context.cacheDir, "osmdroid")
                osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            }
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 4.0
                maxZoomLevel = 19.0
                isTilesScaledToDpi = true
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(tileFilter(dark))
            render(map, key, tracks, zones, mode, metric, breaks, onSelect)
        },
        onRelease = { it.onDetach() },
    )
}

private fun tileFilter(dark: Boolean): ColorMatrixColorFilter {
    val gray = ColorMatrix().apply { setSaturation(0f) }
    val tone = if (dark) {
        ColorMatrix(floatArrayOf(-0.7f, 0f, 0f, 0f, 200f, 0f, -0.7f, 0f, 0f, 200f, 0f, 0f, -0.7f, 0f, 200f, 0f, 0f, 0f, 1f, 0f))
    } else {
        ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 30f, 0f, 0.85f, 0f, 0f, 30f, 0f, 0f, 0.85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))
    }
    gray.postConcat(tone)
    return ColorMatrixColorFilter(gray)
}

private fun value(segment: Segment, metric: MapMetric): Double? = when (metric) {
    MapMetric.SPEED -> segment.speedKmh
    MapMetric.HEART_RATE -> segment.heartRate?.toDouble()
}

private fun render(
    map: MapView,
    selection: String,
    tracks: List<RouteTrack>,
    zones: List<Zone>,
    mode: MapMode,
    metric: MapMetric,
    breaks: List<Double>,
    onSelect: (String) -> Unit,
) {
    val key = listOf(selection, tracks.size, tracks.firstOrNull()?.route?.id, tracks.lastOrNull()?.route?.id).joinToString()
    val previous = map.tag as? String
    map.overlays.clear()
    val density = map.context.resources.displayMetrics.density

    if (mode == MapMode.LINES) {
        tracks.forEach { track ->
            val zone = ZoneId.systemDefault()
            val label = track.route.start.atZone(zone).format(dayFormat)
            groups(track.segments, metric, breaks).forEach { group ->
                val line = Polyline(map).apply {
                    setPoints(listOf(GeoPoint(group.first().from.lat, group.first().from.lon)) + group.map { GeoPoint(it.to.lat, it.to.lon) })
                    val bucket = value(group.first(), metric)?.let { Geo.bucket(it, breaks) }
                    outlinePaint.color = (bucket?.let { RAMP[it] } ?: Color(0xFF9A948A)).toArgb()
                    outlinePaint.strokeWidth = 5f * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    infoWindow = null
                    val speed = group.map { it.speedKmh }.average()
                    val hr = group.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()
                    val text = "$label · ${Format.decimal(speed)} km/h" + (hr?.let { " · ${it.roundToLong()} ppm" } ?: " · sin pulso")
                    setOnClickListener { _, _, _ -> onSelect(text); true }
                }
                map.overlays.add(line)
            }
        }
    } else {
        zones.forEach { zone ->
            val v = when (metric) {
                MapMetric.SPEED -> zone.speedKmh
                MapMetric.HEART_RATE -> zone.heartRate
            }
            val polygon = Polygon(map).apply {
                points = listOf(
                    GeoPoint(zone.south, zone.west),
                    GeoPoint(zone.south, zone.east),
                    GeoPoint(zone.north, zone.east),
                    GeoPoint(zone.north, zone.west),
                )
                val color = v?.let { RAMP[Geo.bucket(it, breaks)] } ?: Color(0xFF9A948A)
                fillPaint.color = color.copy(alpha = 0.72f).toArgb()
                outlinePaint.color = color.toArgb()
                outlinePaint.strokeWidth = 1f
                infoWindow = null
                val text = "Zona: ${Format.decimal(zone.speedKmh)} km/h de media" +
                    (zone.heartRate?.let { " · ${it.roundToLong()} ppm" } ?: "") +
                    " · ${zone.passes} ${if (zone.passes == 1) "caminata" else "caminatas"}"
                setOnClickListener { _, _, _ -> onSelect(text); true }
            }
            map.overlays.add(polygon)
        }
    }

    if (previous != key) {
        map.tag = key
        val points = tracks.flatMap { t -> t.route.points.map { GeoPoint(it.lat, it.lon) } }
        if (points.size >= 2) {
            val box = BoundingBox.fromGeoPointsSafe(points).increaseByScale(1.2f)
            if (map.width > 0 && map.height > 0) {
                map.post { map.zoomToBoundingBox(box, false) }
            } else {
                map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false) }
            }
        }
    }
    map.invalidate()
}

private fun groups(segments: List<Segment>, metric: MapMetric, breaks: List<Double>): List<List<Segment>> {
    val out = mutableListOf<MutableList<Segment>>()
    var lastBucket: Int? = -1
    segments.forEach { segment ->
        val bucket = value(segment, metric)?.let { Geo.bucket(it, breaks) }
        val last = out.lastOrNull()?.lastOrNull()
        val contiguous = last != null && last.to == segment.from
        if (contiguous && bucket == lastBucket) {
            out.last() += segment
        } else {
            out += mutableListOf(segment)
        }
        lastBucket = bucket
    }
    return out
}
