package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.Thresholds
import ar.nahuel.bitacora.ui.ContentState
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

private val intensity = Thresholds().intensityHeartRate
private val clock = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun LogScreen(content: ContentState, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val zone = ZoneId.systemDefault()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item(key = "masthead") {
            Column(Modifier.padding(top = 12.dp)) {
                Text("REGISTRO", style = Type.masthead, color = ink.ink)
                Spacer(Modifier.height(6.dp))
                Rule(thickness = 3.dp, strong = true)
                Spacer(Modifier.height(10.dp))
                ColumnHeader()
            }
        }
        val loaded = content as? ContentState.Loaded
        if (loaded == null || loaded.dashboard.sessionWeeks.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (content is ContentState.Loading) "Leyendo sesiones…" else "No hay sesiones de ejercicio en los últimos seis meses.",
                    style = Type.body,
                    color = ink.inkSoft,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            loaded.dashboard.sessionWeeks.forEach { week ->
                item(key = "week-${week.weekStart}") {
                    SectionHeader(
                        "Semana ${week.weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${Format.dayMonth(week.weekStart)}",
                        "${Format.int(week.minutes)} min",
                    )
                }
                items(week.sessions, key = { "session-${it.id}" }) { session ->
                    SessionLine(
                        session = session,
                        zone = zone,
                        expanded = expanded == session.id,
                        onToggle = { expanded = if (expanded == session.id) null else session.id },
                    )
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ColumnHeader() {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("día", style = Type.small, color = ink.inkSoft, modifier = Modifier.width(56.dp))
        Text("tipo · tocá para ver más", style = Type.small, color = ink.inkSoft, modifier = Modifier.weight(1f))
        Text("min", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("km", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        Text("ppm", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun SessionLine(session: ExerciseSession, zone: ZoneId, expanded: Boolean, onToggle: () -> Unit) {
    val ink = LocalInk.current
    val start = session.start.atZone(zone)
    val date = start.toLocalDate()
    val km = session.distanceMeters?.takeIf { it > 0 }?.let { Format.decimal(it / 1000) } ?: "—"
    val hr = session.avgHeartRate?.toString() ?: "—"
    val intense = (session.avgHeartRate ?: 0L) >= intensity
    val description = "${Format.longDay(date)}, ${session.kind.label}, ${session.durationMinutes} minutos" +
        (session.distanceMeters?.let { ", $km kilómetros" } ?: "") +
        (session.avgHeartRate?.let { ", pulso promedio $it" } ?: "")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Ocultar detalle" else "Ver detalle", onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = if (expanded) "detalle abierto" else "detalle cerrado"
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Format.shortDay(date), style = Type.figureSmall, color = ink.inkSoft, modifier = Modifier.width(56.dp))
            Text(
                session.title?.takeIf { it.isNotBlank() } ?: session.kind.label,
                style = Type.body,
                color = ink.ink,
                modifier = Modifier.weight(1f),
            )
            Text("${session.durationMinutes}", style = Type.figure, color = ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Text(km, style = Type.figure, color = ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            Text(hr, style = Type.figure, color = if (intense) ink.signal else ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        }
        if (expanded) SessionDetail(session, start.toLocalTime().format(clock), session.end.atZone(zone).toLocalTime().format(clock))
        Rule()
    }
}

@Composable
private fun SessionDetail(session: ExerciseSession, from: String, to: String) {
    val ink = LocalInk.current
    val km = (session.distanceMeters ?: 0.0) / 1000.0
    val minutes = session.durationMinutes.toDouble()
    val rows = buildList {
        add("Horario" to "$from → $to")
        add("Duración" to Format.hours(session.durationMinutes))
        if (km > 0.05 && minutes > 0) {
            add("Distancia" to "${Format.decimal(km)} km")
            add("Velocidad" to "${Format.decimal(km / (minutes / 60.0))} km/h")
            val pace = minutes / km
            add("Ritmo" to "${pace.toInt()}:${((pace - pace.toInt()) * 60).toInt().toString().padStart(2, '0')} min/km")
        }
        session.avgHeartRate?.let { add("Pulso promedio" to "$it ppm") }
        session.maxHeartRate?.let { add("Pulso máximo" to "$it ppm") }
        session.avgHeartRate?.let {
            add("Zona" to if (it >= intensity) "intensa (≥ $intensity ppm)" else "aeróbica suave")
        }
        session.kilocalories?.takeIf { it > 0 }?.let { add("Calorías" to "${Format.int(it.toLong())} kcal") }
        add("Origen" to session.sourceApp.substringAfterLast('.'))
    }
    Column(Modifier.padding(start = 56.dp, bottom = 12.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(label, style = Type.small, color = ink.inkSoft, modifier = Modifier.weight(1f))
                Text(value, style = Type.figureSmall, color = ink.ink)
            }
        }
    }
}
