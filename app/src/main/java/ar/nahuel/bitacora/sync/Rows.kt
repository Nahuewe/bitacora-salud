package ar.nahuel.bitacora.sync

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.SleepSession
import ar.nahuel.bitacora.domain.WeightEntry
import java.time.Duration
import kotlin.math.round
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExerciseRow(
    @SerialName("hc_id") val hcId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("exercise_type") val exerciseType: Int,
    @SerialName("exercise_kind") val exerciseKind: String,
    @SerialName("title") val title: String?,
    @SerialName("duration_s") val durationSeconds: Long,
    @SerialName("distance_m") val distanceMeters: Double?,
    @SerialName("avg_hr") val avgHeartRate: Long?,
    @SerialName("max_hr") val maxHeartRate: Long?,
    @SerialName("kcal") val kilocalories: Double?,
    @SerialName("source_app") val sourceApp: String,
)

@Serializable
data class DailyRow(
    @SerialName("day") val day: String,
    @SerialName("steps") val steps: Long,
    @SerialName("distance_m") val distanceMeters: Double?,
    @SerialName("active_kcal") val activeKilocalories: Double?,
    @SerialName("active_min") val activeMinutes: Long,
)

@Serializable
data class SleepRow(
    @SerialName("hc_id") val hcId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("asleep_min") val asleepMinutes: Long,
    @SerialName("deep_min") val deepMinutes: Long,
    @SerialName("rem_min") val remMinutes: Long,
    @SerialName("light_min") val lightMinutes: Long,
    @SerialName("awake_min") val awakeMinutes: Long,
    @SerialName("avg_hr") val avgHeartRate: Long?,
)

@Serializable
data class WeightRow(
    @SerialName("hc_id") val hcId: String,
    @SerialName("measured_at") val measuredAt: String,
    @SerialName("weight_kg") val weightKg: Double,
)

@Serializable
data class SyncRunRow(
    @SerialName("trigger") val trigger: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    @SerialName("status") val status: String,
    @SerialName("counts") val counts: Map<String, Int>,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("app_version") val appVersion: String,
)

private fun heartRate(value: Long?): Long? = value?.takeIf { it in 20L..250L }

private fun tenth(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }?.let { round(it * 10) / 10 }

fun ExerciseSession.toRow() = ExerciseRow(
    hcId = id,
    startedAt = start.toString(),
    endedAt = end.toString(),
    exerciseType = type,
    exerciseKind = kind.slug,
    title = title?.take(200),
    durationSeconds = Duration.between(start, end).seconds.coerceAtLeast(0),
    distanceMeters = tenth(distanceMeters),
    avgHeartRate = heartRate(avgHeartRate),
    maxHeartRate = heartRate(maxHeartRate),
    kilocalories = tenth(kilocalories),
    sourceApp = sourceApp.take(200),
)

fun DayActivity.toRow() = DailyRow(
    day = date.toString(),
    steps = steps.coerceIn(0, 200_000),
    distanceMeters = tenth(distanceMeters),
    activeKilocalories = tenth(activeKilocalories),
    activeMinutes = activeMinutes.coerceIn(0, 1440),
)

fun SleepSession.toRow() = SleepRow(
    hcId = id,
    startedAt = start.toString(),
    endedAt = end.toString(),
    asleepMinutes = asleepMinutes,
    deepMinutes = deepMinutes,
    remMinutes = remMinutes,
    lightMinutes = lightMinutes,
    awakeMinutes = awakeMinutes,
    avgHeartRate = heartRate(avgHeartRate),
)

fun WeightEntry.toRow(): WeightRow? =
    if (kilograms in 20.0..400.0) WeightRow(id, time.toString(), round(kilograms * 100) / 100) else null

@Serializable
data class StrengthRow(
    @SerialName("day") val day: String,
    @SerialName("routine_id") val routineId: String,
)
