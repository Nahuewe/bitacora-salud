package ar.nahuel.bitacora.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.RouteTrack
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.LocalDate
import java.time.format.TextStyle
import kotlin.math.roundToLong

@Composable
internal fun DayRibbon(days: List<WalkDay>, selected: LocalDate?, total: Int, onSelect: (LocalDate?) -> Unit) {
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

internal fun daySummary(day: WalkDay?, tracks: List<RouteTrack>): String {
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
