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
