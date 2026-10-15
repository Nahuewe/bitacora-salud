package ar.nahuel.bitacora.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import java.time.temporal.IsoFields

private val intensity = Thresholds().intensityHeartRate

@Composable
fun LogScreen(content: ContentState, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val zone = ZoneId.systemDefault()
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item {
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
            item {
                Text(
                    if (content is ContentState.Loading) "Leyendo sesiones…" else "No hay sesiones de ejercicio en los últimos 60 días.",
                    style = Type.body,
                    color = ink.inkSoft,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            loaded.dashboard.sessionWeeks.forEach { week ->
                item(key = "w-${week.weekStart}") {
                    SectionHeader(
                        "Semana ${week.weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · ${Format.dayMonth(week.weekStart)}",
                        "${Format.int(week.minutes)} min",
                    )
                }
                items(week.sessions, key = { it.id }) { SessionLine(it, zone) }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ColumnHeader() {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text("día", style = Type.small, color = ink.inkSoft, modifier = Modifier.width(56.dp))
        Text("tipo", style = Type.small, color = ink.inkSoft, modifier = Modifier.weight(1f))
        Text("min", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
        Text("km", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        Text("ppm", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun SessionLine(session: ExerciseSession, zone: ZoneId) {
    val ink = LocalInk.current
    val date = session.start.atZone(zone).toLocalDate()
    val km = session.distanceMeters?.takeIf { it > 0 }?.let { Format.decimal(it / 1000) } ?: "—"
    val hr = session.avgHeartRate?.toString() ?: "—"
    val intense = (session.avgHeartRate ?: 0L) >= intensity
    val description = "${Format.longDay(date)}, ${session.kind.label}, ${session.durationMinutes} minutos" +
        (session.distanceMeters?.let { ", $km kilómetros" } ?: "") +
        (session.avgHeartRate?.let { ", pulso promedio $it" } ?: "")
    Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Format.shortDay(date), style = Type.figureSmall, color = ink.inkSoft, modifier = Modifier.width(56.dp))
            Text(session.title?.takeIf { it.isNotBlank() } ?: session.kind.label, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
            Text("${session.durationMinutes}", style = Type.figure, color = ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
            Text(km, style = Type.figure, color = ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
            Text(hr, style = Type.figure, color = if (intense) ink.signal else ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
        }
        Rule()
    }
}
