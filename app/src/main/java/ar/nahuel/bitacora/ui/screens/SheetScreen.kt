package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.Dashboard
import ar.nahuel.bitacora.domain.DayRow
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.NightRow
import ar.nahuel.bitacora.domain.Recommendation
import ar.nahuel.bitacora.domain.Severity
import ar.nahuel.bitacora.domain.Thresholds
import ar.nahuel.bitacora.domain.WeekRow
import ar.nahuel.bitacora.ui.ContentState
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val thresholds = Thresholds()
private val clock = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun SheetScreen(content: ContentState, today: LocalDate, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item { Masthead(today) }
        when (content) {
            ContentState.Loading -> loadingRows()
            ContentState.Empty -> item { EmptySheet() }
            is ContentState.Error -> item { ErrorSheet(content.message, onRetry) }
            is ContentState.Loaded -> loadedSheet(content.dashboard, content.notes)
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun Masthead(today: LocalDate) {
    val ink = LocalInk.current
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("BITÁCORA", style = Type.masthead, color = ink.ink, modifier = Modifier.weight(1f))
            Text(Format.longDay(today) + " · " + Format.dayMonth(today).substringAfter(' '), style = Type.figureSmall, color = ink.inkSoft)
        }
        Spacer(Modifier.height(6.dp))
        Rule(thickness = 3.dp, strong = true)
        Spacer(Modifier.height(2.dp))
        Rule()
    }
}

private fun LazyListScope.loadedSheet(dashboard: Dashboard, notes: List<Recommendation>) {
    item { Summary(dashboard) }
    item { SectionHeader("Notas al margen", "${notes.size}") }
    if (notes.isEmpty()) {
        item { Text("Sin observaciones esta semana.", style = Type.body, color = LocalInk.current.inkSoft, modifier = Modifier.padding(vertical = 14.dp)) }
    } else {
        itemsIndexed(notes, key = { _, note -> "note-${note.id}" }) { index, note -> NoteRow(index + 1, note) }
    }

    item {
        SectionHeader("Pasos · 14 días", "piso ${Format.int(thresholds.stepTarget)}")
        Spacer(Modifier.height(6.dp))
    }
    items(dashboard.days, key = { "day-${it.date}" }) { StepRow(it, dashboard.stepScaleMax) }

    item {
        SectionHeader("Carga semanal", "minutos activos")
        Spacer(Modifier.height(6.dp))
    }
    items(dashboard.weeks, key = { "week-${it.weekStart}" }) { WeekLoadRow(it, dashboard.weekScaleMax) }

    item { SectionHeader("Sueño · últimas noches", "dormido · acostarse → despertar") }
    if (dashboard.nights.isEmpty()) {
        item { Text("Sin registros de sueño en Health Connect.", style = Type.body, color = LocalInk.current.inkSoft, modifier = Modifier.padding(vertical = 14.dp)) }
    } else {
        items(dashboard.nights, key = { "night-${it.wakeDate}-${it.wake}" }) { NightLine(it) }
    }
}

@Composable
private fun Summary(dashboard: Dashboard) {
    val ink = LocalInk.current
    val today = dashboard.today
    val week = dashboard.weeks.firstOrNull()
    Column {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Pasos hoy", style = Type.small, color = ink.inkSoft)
            Text(Format.int(today?.steps ?: 0L), style = Type.figureLarge, color = ink.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("Activo hoy", style = Type.small, color = ink.inkSoft)
            Text("${Format.int(today?.activeMinutes ?: 0L)} min", style = Type.figureLarge, color = ink.ink)
        }
    }
    Text(
        "Activo esta semana: ${Format.int(week?.minutes ?: 0L)} min. Se estima con los minutos en los que diste más de 60 pasos; Samsung Health no comparte su \"tiempo de actividad\" con Health Connect, así que puede diferir unos minutos.",
        style = Type.small,
        color = ink.inkSoft,
        modifier = Modifier.padding(top = 8.dp),
    )
    today?.let { day -> SourcesNote(day) }
    }
}

@Composable
private fun SourcesNote(day: ar.nahuel.bitacora.domain.DayActivity) {
    val ink = LocalInk.current
    val others = day.sources.filter { it.value != day.steps }
    val hc = day.healthConnectSteps
    if (others.isEmpty() && (hc == null || hc == day.steps)) return
    val detail = buildString {
        append("Fuentes de pasos hoy: ")
        append(day.sources.entries.sortedByDescending { it.value }.joinToString(" · ") { "${sourceName(it.key)} ${Format.int(it.value)}" })
        if (hc != null && hc != day.steps) append(". Health Connect, por su orden de prioridad, informa ${Format.int(hc)}")
        append(". Uso la fuente con más pasos. Para que coincida en todas las apps, en Health Connect → Prioridad de las apps → Pasos, poné Samsung Health primero.")
    }
    Text(detail, style = Type.small, color = ink.signal, modifier = Modifier.padding(top = 6.dp))
}

private fun sourceName(packageName: String): String = when {
    packageName.contains("shealth") -> "Samsung Health"
    packageName.contains("google.android.apps.fitness") -> "Google Fit"
    packageName.contains("healthdata") || packageName == "android" -> "Teléfono"
    else -> packageName.substringAfterLast('.')
}

@Composable
private fun NoteRow(index: Int, note: Recommendation) {
    val ink = LocalInk.current
    val tone = when (note.severity) {
        Severity.ALERT -> ink.signal
        Severity.ATTENTION -> ink.ink
        Severity.INFO -> ink.inkSoft
        Severity.GOOD -> ink.good
    }
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text(index.toString().padStart(2, '0'), style = Type.figure, color = ink.inkSoft, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.severity == Severity.ALERT || note.severity == Severity.ATTENTION) {
                        Box(Modifier.size(8.dp).padding(0.dp)) {
                            Canvas(Modifier.size(8.dp)) { drawRect(tone) }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(note.severity.label.uppercase(), style = Type.sectionLabel, color = tone)
                }
                Spacer(Modifier.height(4.dp))
                Text(note.title, style = Type.noteTitle, color = ink.ink)
                Spacer(Modifier.height(4.dp))
                Text(note.detail, style = Type.body, color = ink.inkSoft)
            }
        }
        Rule()
    }
}

@Composable
private fun StepRow(day: DayRow, scaleMax: Long) {
    val ink = LocalInk.current
    val overFloor = day.steps >= thresholds.stepTarget
    val description = buildString {
        append(Format.longDay(day.date)).append(": ")
        if (day.hasData) append(Format.int(day.steps)).append(" pasos, ").append(if (overFloor) "sobre el piso" else "bajo el piso")
        else append("sin datos")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Format.shortDay(day.date),
            style = Type.figureSmall,
            color = if (day.date.dayOfWeek.value >= 6) ink.ink else ink.inkSoft,
            fontWeight = if (day.date.dayOfWeek.value >= 6) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(56.dp),
        )
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val floorX = size.width * thresholds.stepTarget / scaleMax
            val barWidth = size.width * day.steps.coerceAtMost(scaleMax) / scaleMax
            val barHeight = 9.dp.toPx()
            drawRect(
                color = if (overFloor) ink.ink else ink.barMuted,
                topLeft = Offset(0f, (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
            )
            drawLine(
                color = ink.signal,
                start = Offset(floorX, 0f),
                end = Offset(floorX, size.height),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            )
        }
        Text(
            if (day.hasData) Format.int(day.steps) else "—",
            style = Type.figureSmall,
            color = if (overFloor) ink.ink else ink.inkSoft,
            modifier = Modifier.width(64.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun WeekLoadRow(week: WeekRow, scaleMax: Long) {
    val ink = LocalInk.current
    val label = if (week.isCurrent) "esta" else Format.dayMonth(week.weekStart)
    val description = "Semana del ${Format.dayMonth(week.weekStart)}: ${week.minutes} minutos en ${week.sessions} sesiones, ${week.intenseMinutes} intensos"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = Type.figureSmall,
            color = if (week.isCurrent) ink.ink else ink.inkSoft,
            fontWeight = if (week.isCurrent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(56.dp),
        )
        Canvas(Modifier.weight(1f).height(18.dp)) {
            val barHeight = 9.dp.toPx()
            val top = (size.height - barHeight) / 2
            val total = size.width * week.minutes.coerceAtMost(scaleMax) / scaleMax
            val intense = size.width * week.intenseMinutes.coerceAtMost(scaleMax) / scaleMax
            drawRect(color = ink.barMuted, topLeft = Offset(0f, top), size = Size(total, barHeight))
            drawRect(color = ink.ink, topLeft = Offset(0f, top), size = Size(intense, barHeight))
        }
        Text(
            "${week.minutes}",
            style = Type.figureSmall,
            color = ink.ink,
            modifier = Modifier.width(64.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun NightLine(night: NightRow) {
    val ink = LocalInk.current
    val short = night.asleepMinutes < thresholds.shortSleepMinutes
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Format.shortDay(night.wakeDate), style = Type.figureSmall, color = ink.inkSoft, modifier = Modifier.width(56.dp))
            Text(
                Format.hours(night.asleepMinutes),
                style = Type.figure,
                color = if (short) ink.signal else ink.ink,
                modifier = Modifier.weight(1f),
            )
            Text("${night.bedtime.format(clock)} → ${night.wake.format(clock)}", style = Type.figureSmall, color = ink.inkSoft)
        }
        Rule()
    }
}

private fun LazyListScope.loadingRows() {
    item { SectionHeader("Leyendo Health Connect") }
    items(8) { index ->
        val ink = LocalInk.current
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp).height(8.dp)) { Canvas(Modifier.fillMaxWidth().height(8.dp)) { drawRect(ink.rule) } }
            Spacer(Modifier.width(16.dp))
            Canvas(Modifier.weight(1f).height(9.dp)) {
                drawRect(ink.rule, size = Size(size.width * (0.35f + (index % 4) * 0.15f), size.height))
            }
        }
    }
}

@Composable
private fun EmptySheet() {
    val ink = LocalInk.current
    Column(Modifier.padding(top = 32.dp)) {
        Text("La hoja está en blanco.", style = Type.headline, color = ink.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            "Health Connect todavía no tiene datos de los últimos 60 días. En Samsung Health andá a Ajustes → Health Connect y activá la sincronización de ejercicio, pasos, sueño, frecuencia cardíaca y peso.",
            style = Type.body,
            color = ink.inkSoft,
        )
    }
}

@Composable
private fun ErrorSheet(message: String, onRetry: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.padding(top = 32.dp)) {
        Text("No se pudo leer", style = Type.headline, color = ink.signal)
        Spacer(Modifier.height(10.dp))
        Text(message, style = Type.body, color = ink.inkSoft)
        Spacer(Modifier.height(18.dp))
        LedgerButton("Reintentar", onRetry)
    }
}
