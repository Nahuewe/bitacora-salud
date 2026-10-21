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
import ar.nahuel.bitacora.ui.MapData
import ar.nahuel.bitacora.ui.MapState
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
private const val ALL = "all"

private data class WalkDay(val date: LocalDate, val tracks: List<RouteTrack>) {
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

@Composable
private fun DayRibbon(days: List<WalkDay>, selected: LocalDate?, total: Int, onSelect: (LocalDate?) -> Unit) {
    val ink = LocalInk.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item(key = ALL) {
            DayCell("Todos", "$total recorridos", "120 días", selected == null, Modifier) { onSelect(null) }
        }
        items(days, key = { it.date.toString() }) { day ->
            val month = day.date.month.getDisplayName(TextStyle.SHORT, Format.locale).trimEnd('.')
            val walks = if (day.tracks.size == 1) "1 caminata" else "${day.tracks.size} caminatas"
            DayCell(Format.shortDay(day.date), "${Format.decimal(day.km)} km", "$month · $walks", day.date == selected, Modifier) {
                onSelect(day.date)
            }
        }
    }
}

@Composable
private fun DayCell(title: String, figure: String, caption: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = LocalInk.current
    Column(
        modifier
            .padding(end = 6.dp)
            .border(1.dp, if (active) ink.ink else ink.rule)
            .background(if (active) ink.ink else ink.paper)
            .selectable(selected = active, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(title, style = Type.small, color = if (active) ink.paper else ink.ink)
        Text(figure, style = Type.figureSmall, color = if (active) ink.paper else ink.ink)
        Text(caption, style = Type.small, color = if (active) ink.paper else ink.inkSoft)
    }
}

private fun daySummary(day: WalkDay?, tracks: List<RouteTrack>): String {
    val km = tracks.sumOf { it.route.distanceMeters } / 1000
    val segments = tracks.flatMap { it.segments }
    val speed = segments.map { it.speedKmh }.takeIf { it.isNotEmpty() }?.average()
    val heart = segments.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()
    val head = if (day == null) {
        "${tracks.size} recorridos"
    } else {
        Format.longDay(day.date).replaceFirstChar { it.uppercase() } + " " + day.date.month.getDisplayName(TextStyle.FULL, Format.locale) +
            " · " + if (tracks.size == 1) "1 caminata" else "${tracks.size} caminatas"
    }
    return head + " · ${Format.decimal(km)} km" +
        (speed?.let { " · ${Format.decimal(it)} km/h de media" } ?: "") +
        (heart?.let { " · ${it.roundToLong()} ppm de media" } ?: "")
}

@Composable
private fun Legend(metric: MapMetric, breaks: List<Double>) {
    val ink = LocalInk.current
    val unit = if (metric == MapMetric.SPEED) "km/h" else "ppm"
    fun fmt(v: Double) = if (metric == MapMetric.SPEED) Format.decimal(v) else v.roundToLong().toString()
    val labels = if (breaks.size == 4) {
        listOf("< ${fmt(breaks[0])}", "${fmt(breaks[0])}–${fmt(breaks[1])}", "${fmt(breaks[1])}–${fmt(breaks[2])}", "${fmt(breaks[2])}–${fmt(breaks[3])}", "> ${fmt(breaks[3])}")
    } else {
        List(5) { "" }
    }
    val (low, high) = if (metric == MapMetric.SPEED) "más lento" to "más rápido" else "más tranquilo" to "más agitado"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Escala de $unit: ${labels.joinToString(", ")}. Azul $low, naranja $high."
            },
    ) {
        Row(Modifier.fillMaxWidth()) {
            RAMP.forEachIndexed { i, color ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.fillMaxWidth().height(10.dp)) { drawRect(color) }
                    Text(labels[i], style = Type.figureSmall, color = ink.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("← $low", style = Type.small, color = ink.inkSoft, modifier = Modifier.weight(1f))
            Text("$high · $unit →", style = Type.small, color = ink.inkSoft)
        }
    }
}

@Composable
private fun EmptyRoutes(data: MapData, message: String?, onImport: () -> Unit, onRequestRoutes: () -> Unit, onConsent: (String) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Todavía no hay recorridos.", style = Type.headline, color = ink.ink)
        message?.let { Text(it, style = Type.body, color = ink.signal, modifier = Modifier.padding(top = 10.dp)) }
        Text(diagnosis(data), style = Type.body, color = ink.ink, modifier = Modifier.padding(top = 12.dp))
        if (data.consentIds.isNotEmpty()) {
            Text(
                "Health Connect pide autorizar cada recorrido. Para no hacerlo uno por uno, tocá Permitir recorridos y elegí \"Permitir siempre\".",
                style = Type.small,
                color = ink.inkSoft,
                modifier = Modifier.padding(top = 8.dp),
            )
            LedgerButton("Autorizar la última caminata", { onConsent(data.consentIds.first()) }, filled = true, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            "Samsung Health muestra el mapa al terminar la caminata, pero no siempre le pasa el GPS a Health Connect. Hay dos caminos:",
            style = Type.body,
            color = ink.inkSoft,
            modifier = Modifier.padding(top = 16.dp),
        )
        Step(1, if (data.routePermission) "El permiso de recorridos ya está dado. Si igual no aparecen, Samsung no los está compartiendo: usá el paso 2." else "Permitir recorridos en Health Connect. Si Samsung los comparte, aparecen solos.")
        if (!data.routePermission) LedgerButton("Permitir recorridos", onRequestRoutes, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
        Step(2, "Importar desde la exportación de Samsung Health: en Samsung Health tocá Ajustes → Descargar datos personales. Después, acá, elegí la carpeta samsunghealth_… que queda en Descargas → Samsung Health.")
        LedgerButton("Importar exportación", onImport, filled = true, modifier = Modifier.padding(start = 32.dp, top = 6.dp))
        if (data.consentRequired > 0) {
            Text("${data.consentRequired} caminatas tienen recorrido pero falta tu permiso.", style = Type.small, color = ink.inkSoft, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            "Los recorridos se guardan solo en este teléfono: no se sincronizan con Supabase porque muestran dónde vivís y trabajás.",
            style = Type.small,
            color = ink.inkSoft,
            modifier = Modifier.padding(top = 20.dp),
        )
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
