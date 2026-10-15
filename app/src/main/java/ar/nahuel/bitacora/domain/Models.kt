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

    companion object {
        fun empty(zone: ZoneId) = HealthSnapshot(zone, emptyList(), emptyList(), emptyList(), emptyList())
    }
}
