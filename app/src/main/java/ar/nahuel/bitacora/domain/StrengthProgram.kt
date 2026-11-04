package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class P(val x: Float, val y: Float)

data class Pose(
    val head: P,
    val shoulder: P,
    val elbow: P,
    val hand: P,
    val hip: P,
    val knee: P,
    val ankle: P,
    val elbow2: P? = null,
    val hand2: P? = null,
    val knee2: P? = null,
    val ankle2: P? = null,
    val feet: Boolean = true,
    val footTo: P? = null,
)

sealed interface Prop {
    data class Block(val left: Float, val top: Float, val right: Float, val bottom: Float) : Prop
    data class Band(val from: P, val to: P) : Prop
}

data class Guide(val from: P, val to: P)

data class Frame(
    val label: String,
    val pose: Pose,
    val props: List<Prop> = emptyList(),
    val guides: List<Guide> = emptyList(),
    val dumbbell: Boolean = false,
    val wrong: Boolean = false,
)

data class Exercise(
    val id: String,
    val name: String,
    val prescription: String,
    val rest: String,
    val equipment: String,
    val cues: List<String>,
    val avoid: List<String>,
    val easier: String,
    val harder: String,
    val frames: List<Frame>,
)

data class Routine(
    val id: String,
    val name: String,
    val minutes: Int,
    val exercises: List<Exercise>,
    val format: String = "",
    val purpose: String = "",
    val strength: Boolean = false,
)

enum class DayKind { STRENGTH, EASY_WALK, INTERVALS, LONG_WALK, REST }

data class PlanDay(val day: DayOfWeek, val kind: DayKind, val title: String, val detail: String, val routineIds: List<String> = emptyList())

data class Phase(val name: String, val weeks: String, val rule: String, val untilWeek: Int?)

class StrengthProgram(
    val warmUp: List<String>,
    val effortRule: String,
    val phases: List<Phase>,
    val routines: List<Routine>,
    private val week: List<PlanDay>,
    private val weekChanges: List<Pair<Int, PlanDay>>,
) {
    val strengthIds: Set<String> = routines.filter { it.strength }.map { it.id }.toSet()

    fun routine(id: String): Routine? = routines.firstOrNull { it.id == id }

    fun phaseIndex(week: Int): Int =
        phases.indexOfFirst { phase -> phase.untilWeek == null || week <= phase.untilWeek }.coerceAtLeast(0)

    fun weekPlan(weekNumber: Int): List<PlanDay> = week.map { base ->
        weekChanges.filter { (fromWeek, day) -> day.day == base.day && weekNumber >= fromWeek }.maxByOrNull { it.first }?.second ?: base
    }

    companion object {
        fun weekNumber(start: LocalDate, today: LocalDate): Int =
            (ChronoUnit.DAYS.between(start, today).coerceAtLeast(0) / 7 + 1).toInt()
    }
}
