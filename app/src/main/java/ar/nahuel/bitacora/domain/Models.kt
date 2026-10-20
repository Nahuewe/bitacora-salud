package ar.nahuel.bitacora.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ExerciseKind(val slug: String, val label: String) {
    WALKING("walking", "Caminata"),
    RUNNING("running", "Carrera"),
    STRENGTH("strength", "Fuerza"),
    CYCLING("cycling", "Bici"),
    SWIMMING("swimming", "Natación"),
    OTHER("other", "Otro"),
}

data class ExerciseSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val type: Int,
    val kind: ExerciseKind,
    val title: String?,
    val distanceMeters: Double?,
    val avgHeartRate: Long?,
    val maxHeartRate: Long?,
    val kilocalories: Double?,
    val sourceApp: String,
) {
    val durationMinutes: Long get() = Duration.between(start, end).toMinutes()
}

data class DayActivity(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double?,
    val activeKilocalories: Double?,
    val activeMinutes: Long = 0,
    val sources: Map<String, Long> = emptyMap(),
    val healthConnectSteps: Long? = null,
)

data class SleepSession(
    val id: String,
    val start: Instant,
    val end: Instant,
    val deepMinutes: Long,
    val remMinutes: Long,
    val lightMinutes: Long,
    val awakeMinutes: Long,
    val avgHeartRate: Long?,
) {
    val asleepMinutes: Long
        get() = (Duration.between(start, end).toMinutes() - awakeMinutes).coerceAtLeast(0)
}

data class WeightEntry(
    val id: String,
    val time: Instant,
    val kilograms: Double,
)

data class HealthSnapshot(
    val zone: ZoneId,
    val days: List<DayActivity>,
    val sessions: List<ExerciseSession>,
    val sleeps: List<SleepSession>,
    val weights: List<WeightEntry>,
) {
    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    fun activeMinutes(fromInclusive: LocalDate, toExclusive: LocalDate): Long =
        days.filter { it.date >= fromInclusive && it.date < toExclusive }.sumOf { it.activeMinutes }

    val mainSleeps: List<SleepSession> by lazy {
        sleeps
            .filter { Duration.between(it.start, it.end).toMinutes() >= MAIN_SLEEP_MINUTES }
            .groupBy { localDate(it.end) }
            .values
            .map { night -> night.maxBy { Duration.between(it.start, it.end) } }
            .sortedBy { it.end }
    }

    companion object {
        const val MAIN_SLEEP_MINUTES = 180L

        fun empty(zone: ZoneId) = HealthSnapshot(zone, emptyList(), emptyList(), emptyList(), emptyList())
    }
}
