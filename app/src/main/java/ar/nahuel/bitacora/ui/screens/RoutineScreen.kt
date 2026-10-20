package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.DayKind
import ar.nahuel.bitacora.domain.Exercise
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.PlanDay
import ar.nahuel.bitacora.domain.Routine
import ar.nahuel.bitacora.domain.StrengthProgram
import ar.nahuel.bitacora.routine.RoutineEntry
import ar.nahuel.bitacora.ui.RoutineUi
import ar.nahuel.bitacora.ui.components.LedgerButton
import ar.nahuel.bitacora.ui.components.PoseFigure
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

@Composable
fun RoutineScreen(routine: RoutineUi, today: LocalDate, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val phase = StrengthProgram.phases[StrengthProgram.phaseIndex(routine.week)]
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEntries = routine.entries.filter { it.date >= monday && it.date <= today }

    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item(key = "masthead") {
            Column(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("RUTINA", style = Type.masthead, color = ink.ink, modifier = Modifier.weight(1f))
                    Text("semana ${routine.week} · ${phase.name.lowercase()}", style = Type.figureSmall, color = ink.inkSoft)
                }
                Spacer(Modifier.height(6.dp))
                Rule(thickness = 3.dp, strong = true)
                Text(
                    weekSummary(routine.week, weekEntries) + " " + phase.rule,
                    style = Type.body,
                    color = ink.ink,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        item(key = "plan-head") { SectionHeader("La semana", phase.weeks.lowercase()) }
        items(StrengthProgram.weekPlan(routine.week), key = { "plan-${it.day}" }) { day ->
            PlanRow(day, monday.plusDays(day.day.value - 1L), today, weekEntries)
        }

        item(key = "warmup") {
            SectionHeader("Antes de cada sesión", "5–7 min")
            Column(Modifier.padding(top = 10.dp)) {
                StrengthProgram.warmUp.forEachIndexed { i, step -> NumberedLine(i + 1, step) }
            }
        }

        StrengthProgram.routines.forEach { r ->
            routineSection(r, today, routine.entries, open, onOpen = { id -> open = if (open == id) null else id }, onToggle = onToggle)
        }

        item(key = "rules") {
            SectionHeader("Cómo progresar")
            StrengthProgram.phases.forEach { p ->
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = Type.noteTitle, color = if (p == phase) ink.ink else ink.inkSoft, modifier = Modifier.weight(1f))
                        Text(p.weeks, style = Type.figureSmall, color = ink.inkSoft)
                    }
                    Text(p.rule, style = Type.body, color = ink.inkSoft, modifier = Modifier.padding(top = 4.dp))
                }
                Rule()
            }
            Text(StrengthProgram.EFFORT_RULE, style = Type.body, color = ink.ink, modifier = Modifier.padding(top = 14.dp))
        }
        item(key = "end") { Spacer(Modifier.height(32.dp)) }
    }
}

private fun LazyListScope.routineSection(
    routine: Routine,
    today: LocalDate,
    entries: List<RoutineEntry>,
    open: String?,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    item(key = "routine-${routine.id}") {
        val ink = LocalInk.current
        val doneToday = entries.any { it.date == today && it.routineId == routine.id }
        SectionHeader(routine.name, "${routine.minutes} min · ${routine.exercises.size} ejercicios")
        if (routine.purpose.isNotEmpty()) {
            Text(routine.purpose, style = Type.body, color = ink.ink, modifier = Modifier.padding(top = 10.dp))
        }
        if (routine.format.isNotEmpty()) {
            Text(routine.format, style = Type.small, color = ink.inkSoft, modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (doneToday) "Registrada hoy." else "Tocá cada ejercicio para ver la técnica.",
                style = Type.small,
                color = if (doneToday) ink.good else ink.inkSoft,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            LedgerButton(if (doneToday) "Deshacer" else "Hecha hoy", { onToggle(routine.id) }, filled = !doneToday)
        }
    }
    routine.exercises.forEachIndexed { index, exercise ->
        val key = "${routine.id}-${exercise.id}"
        item(key = key) {
            ExerciseEntry(index + 1, exercise, expanded = open == key, onToggle = { onOpen(key) })
        }
    }
}

@Composable
private fun PlanRow(day: PlanDay, date: LocalDate, today: LocalDate, weekEntries: List<RoutineEntry>) {
    val ink = LocalInk.current
    val isToday = date == today
    val doneIds = weekEntries.filter { it.date == date }.map { it.routineId }.toSet()
    val planned = day.routineIds
    val doneCount = planned.count { it in doneIds }
    val status = when {
        planned.isNotEmpty() && doneCount == planned.size -> "hecho"
        doneCount > 0 -> "$doneCount de ${planned.size}"
        planned.any { it in StrengthProgram.strengthIds } && date.isBefore(today) -> "pendiente"
        isToday -> "hoy"
        else -> ""
    }
    val name = day.day.getDisplayName(TextStyle.SHORT, Format.locale).trimEnd('.')
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${day.day.getDisplayName(TextStyle.FULL, Format.locale)}: ${day.title}. ${day.detail} $status" }) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(36.dp).background(if (isToday) ink.signal else ink.paper))
            Spacer(Modifier.width(8.dp))
            Text(name, style = Type.figureSmall, color = if (isToday) ink.ink else ink.inkSoft, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(day.title, style = Type.body, color = ink.ink, fontWeight = if (day.kind == DayKind.STRENGTH) FontWeight.SemiBold else FontWeight.Normal)
                Text(day.detail, style = Type.small, color = ink.inkSoft)
                if (planned.isNotEmpty()) {
                    Text(
                        planned.mapNotNull { StrengthProgram.routine(it)?.name }.joinToString(" · "),
                        style = Type.figureSmall,
                        color = ink.inkSoft,
                    )
                }
            }
            Text(
                status,
                style = Type.figureSmall,
                color = when (status) {
                    "hecho" -> ink.good
                    "pendiente" -> ink.signal
                    else -> ink.inkSoft
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Rule()
    }
}

@Composable
private fun NumberedLine(number: Int, text: String) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(number.toString().padStart(2, '0'), style = Type.figureSmall, color = ink.inkSoft, modifier = Modifier.width(32.dp))
        Text(text, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExerciseEntry(number: Int, exercise: Exercise, expanded: Boolean, onToggle: () -> Unit) {
    val ink = LocalInk.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Ocultar técnica" else "Ver técnica", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "abierto" else "cerrado" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(number.toString().padStart(2, '0'), style = Type.figure, color = ink.inkSoft, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(exercise.name, style = Type.noteTitle, color = ink.ink)
                Text("descanso ${exercise.rest}", style = Type.small, color = ink.inkSoft)
            }
            Text(exercise.prescription, style = Type.figure, color = ink.ink)
            Text(if (expanded) "  −" else "  +", style = Type.figure, color = ink.inkSoft)
        }
        if (expanded) ExerciseDetail(exercise)
        Rule()
    }
}

@Composable
private fun ExerciseDetail(exercise: Exercise) {
    val ink = LocalInk.current
    Column(Modifier.padding(start = 32.dp, bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            exercise.frames.forEach { frame ->
                PoseFigure(
                    frame = frame,
                    description = "${exercise.name}, posición ${frame.label.lowercase()}" + if (frame.wrong) ", forma incorrecta" else "",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Línea punteada naranja: la referencia de postura que tenés que sostener.",
            style = Type.small,
            color = ink.inkSoft,
            modifier = Modifier.padding(top = 8.dp),
        )
        DetailBlock("Material", listOf(exercise.equipment))
        DetailBlock("Claves", exercise.cues)
        DetailBlock("Evitá", exercise.avoid, warn = true)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("MÁS FÁCIL", style = Type.sectionLabel, color = ink.inkSoft)
                Text(exercise.easier, style = Type.small, color = ink.ink, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("MÁS DIFÍCIL", style = Type.sectionLabel, color = ink.inkSoft)
                Text(exercise.harder, style = Type.small, color = ink.ink, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun DetailBlock(title: String, lines: List<String>, warn: Boolean = false) {
    val ink = LocalInk.current
    Column(Modifier.padding(top = 14.dp)) {
        Text(title.uppercase(), style = Type.sectionLabel, color = if (warn) ink.signal else ink.inkSoft)
        lines.forEach { line ->
            Row(Modifier.padding(top = 4.dp)) {
                Text(if (warn) "×" else "—", style = Type.body, color = if (warn) ink.signal else ink.inkSoft, modifier = Modifier.width(18.dp))
                Text(line, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun weekSummary(week: Int, entries: List<RoutineEntry>): String {
    val plan = StrengthProgram.weekPlan(week)
    fun planned(id: String) = plan.count { id in it.routineIds }
    fun done(ids: Set<String>) = entries.count { it.routineId in ids }
    val strengthPlanned = plan.count { day -> day.routineIds.any { it in StrengthProgram.strengthIds } }
    return "Esta semana: fuerza ${done(StrengthProgram.strengthIds)} de $strengthPlanned · core ${done(setOf("C"))} de ${planned("C")} · movilidad ${done(setOf("M"))} de ${planned("M")}."
}
