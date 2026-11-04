package ar.nahuel.bitacora.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.MapMetric
import ar.nahuel.bitacora.routes.MapData
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import ar.nahuel.bitacora.domain.RouteTrack
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.roundToLong

private const val LATEST = "latest"
internal const val ALL = "all"

internal data class WalkDay(val date: LocalDate, val tracks: List<RouteTrack>) {
    val km: Double get() = tracks.sumOf { it.route.distanceMeters } / 1000
}

@Composable
fun MapScreen(
    state: MapState,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onRequestRoutes: () -> Unit,
    onClearImported: () -> Unit,
    onRetry: () -> Unit,
    onConsent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    var mode by rememberSaveable { mutableStateOf(MapMode.LINES) }
    var metric by rememberSaveable { mutableStateOf(MapMetric.SPEED) }
    var daySelection by rememberSaveable { mutableStateOf(LATEST) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize().background(ink.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Volver", onClick = onBack)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("← Volver", style = Type.body, color = ink.ink) }
            Spacer(Modifier.weight(1f))
            Text("RECORRIDOS", style = Type.masthead, color = ink.ink)
            Spacer(Modifier.width(8.dp))
        }
        Rule(thickness = 3.dp, strong = true)

        when (state) {
            MapState.Idle, is MapState.Loading -> Message(if (state is MapState.Loading) state.message else "Preparando el mapa…")
            is MapState.Error -> Column(Modifier.padding(20.dp)) {
                Text(state.message, style = Type.body, color = ink.signal)
                Spacer(Modifier.height(16.dp))
                LedgerButton("Reintentar", onRetry)
            }
            is MapState.Ready -> {
                val data = state.data
                if (data.tracks.isEmpty()) {
                    EmptyRoutes(data, state.message, onImport, onRequestRoutes, onConsent)
                } else {
                    val zone = ZoneId.systemDefault()
                    val days = remember(data) {
                        data.tracks.groupBy { it.route.start.atZone(zone).toLocalDate() }
                            .map { (date, list) -> WalkDay(date, list.sortedBy { it.route.start }) }
                            .sortedByDescending { it.date.toEpochDay() }
                    }
                    val selectedDay = when (daySelection) {
                        ALL -> null
                        LATEST -> days.firstOrNull()
                        else -> days.firstOrNull { it.date.toString() == daySelection } ?: days.firstOrNull()
                    }
                    val tracks = selectedDay?.tracks ?: data.tracks
                    val zones = remember(tracks, mode) { if (mode == MapMode.ZONES) Geo.zones(tracks) else emptyList() }
                    val breaks = if (metric == MapMetric.SPEED) data.speedBreaks else data.heartBreaks

                    Controls(mode, { mode = it; selected = null }, metric, { metric = it; selected = null })
                    DayRibbon(days, selectedDay?.date, data.tracks.size) { date ->
                        daySelection = date?.toString() ?: ALL
                        selected = null
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Mapa con ${tracks.size} recorridos" }) {
                        RouteMap(
                            key = selectedDay?.date?.toString() ?: ALL,
                            tracks = tracks,
                            zones = zones,
                            mode = mode,
                            metric = metric,
                            breaks = breaks,
                            dark = isSystemInDarkTheme(),
                            onSelect = { selected = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Legend(metric, breaks)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            selected ?: daySummary(selectedDay, tracks),
                            style = Type.body,
                            color = if (selected != null) ink.ink else ink.inkSoft,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        state.message?.let { Text(it, style = Type.small, color = ink.inkSoft, modifier = Modifier.padding(top = 4.dp)) }
                        Text(
                            "Tocá una línea o una zona para ver su velocidad y su pulso." +
                                (if (data.consentRequired > 0) " · ${data.consentRequired} sin permiso" else ""),
                            style = Type.figureSmall,
                            color = ink.inkSoft,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(diagnosis(data), style = Type.small, color = ink.inkSoft, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp)) {
                            if (data.consentIds.isNotEmpty()) {
                                LedgerButton("Autorizar", { onConsent(data.consentIds.first()) })
                                Spacer(Modifier.width(8.dp))
                            }
                            LedgerButton("Importar", onImport)
                            Spacer(Modifier.width(8.dp))
                            if (data.imported > 0) LedgerButton("Borrar importados", onClearImported)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    val ink = LocalInk.current
    Text(text, style = Type.body, color = ink.inkSoft, modifier = Modifier.padding(20.dp))
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Row(modifier.border(1.dp, ink.ink).selectableGroup()) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .background(if (active) ink.ink else ink.paper)
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Type.small, color = if (active) ink.paper else ink.ink)
            }
        }
    }
}

@Composable
private fun Controls(
    mode: MapMode,
    onMode: (MapMode) -> Unit,
    metric: MapMetric,
    onMetric: (MapMetric) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Segmented(listOf(MapMetric.SPEED to "Velocidad", MapMetric.HEART_RATE to "Pulso"), metric, onMetric)
        Spacer(Modifier.width(10.dp))
        Segmented(listOf(MapMode.LINES to "Líneas", MapMode.ZONES to "Zonas"), mode, onMode)
    }
}

private fun diagnosis(data: MapData): String = buildString {
    if (data.fromSamsung > 0) {
        append("${data.fromSamsung} recorridos vienen del reloj, por Samsung Health, con el pulso de cada tramo.")
        if (data.fromHealthConnect > 0) append(" ${data.fromHealthConnect} más desde Health Connect.")
    } else {
        append("Revisé ${data.checked} caminatas de los últimos 120 días en Health Connect: ")
        append("${data.fromHealthConnect} con recorrido")
        if (data.consentRequired > 0) append(", ${data.consentRequired} esperan tu autorización")
        append(", ${data.withoutRoute} sin GPS compartido.")
        if (!data.routePermission) append(" Falta el permiso de recorridos.")
    }
    if (data.imported > 0) append(" Además hay ${data.imported} importadas de la exportación.")
}

@Composable
private fun Step(number: Int, text: String) {
    val ink = LocalInk.current
    Row(Modifier.padding(top = 14.dp)) {
        Text(number.toString().padStart(2, '0'), style = Type.figure, color = ink.inkSoft, modifier = Modifier.width(32.dp))
        Text(text, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
    }
}
