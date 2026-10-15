package ar.nahuel.bitacora.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.SleepSession
import ar.nahuel.bitacora.domain.WeightEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

enum class HealthConnectAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

object HealthAccess {
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val core: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
    )
}

class HealthConnectSource(private val context: Context) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun availability(): HealthConnectAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.NEEDS_UPDATE
        else -> HealthConnectAvailability.UNAVAILABLE
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    fun requestablePermissions(): Set<String> {
        val extra = mutableSetOf<String>()
        if (featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) extra += HealthAccess.HISTORY
        if (featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) extra += HealthAccess.BACKGROUND
        return HealthAccess.core + extra
    }

    fun backgroundReadSupported(): Boolean =
        featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    private fun featureAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun snapshot(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): HealthSnapshot {
        val start = from.atStartOfDay(zone).toInstant()
        val end = toInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        return HealthSnapshot(
            zone = zone,
            days = dailyActivity(from, toInclusive),
            sessions = exerciseSessions(start, end),
            sleeps = sleepSessions(start, end),
            weights = weights(start.minus(Duration.ofDays(365)), end),
        )
    }

    suspend fun dailyActivity(from: LocalDate, toInclusive: LocalDate): List<DayActivity> {
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(
                    from.atStartOfDay(),
                    toInclusive.plusDays(1).atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return groups
            .filter { it.result[StepsRecord.COUNT_TOTAL] != null }
            .map {
                DayActivity(
                    date = it.startTime.toLocalDate(),
                    steps = it.result[StepsRecord.COUNT_TOTAL] ?: 0L,
                    distanceMeters = it.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                    activeKilocalories = it.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                )
            }
    }

    suspend fun exerciseSessions(start: Instant, end: Instant): List<ExerciseSession> =
        readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end)).map { record ->
            val totals = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MAX,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                ),
            )
            ExerciseSession(
                id = record.metadata.id,
                start = record.startTime,
                end = record.endTime,
                type = record.exerciseType,
                kind = kindOf(record.exerciseType),
                title = record.title,
                distanceMeters = totals[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                avgHeartRate = totals[HeartRateRecord.BPM_AVG],
                maxHeartRate = totals[HeartRateRecord.BPM_MAX],
                kilocalories = totals[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                sourceApp = record.metadata.dataOrigin.packageName,
            )
        }

    suspend fun sleepSessions(start: Instant, end: Instant): List<SleepSession> =
        readAll(SleepSessionRecord::class, TimeRangeFilter.between(start, end)).map { record ->
            fun minutesOf(stage: Int): Long = record.stages
                .filter { it.stage == stage }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

            val heartRate = client.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                ),
            )[HeartRateRecord.BPM_AVG]

            SleepSession(
                id = record.metadata.id,
                start = record.startTime,
                end = record.endTime,
                deepMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_DEEP),
                remMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_REM),
                lightMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_LIGHT),
                awakeMinutes = minutesOf(SleepSessionRecord.STAGE_TYPE_AWAKE),
                avgHeartRate = heartRate,
            )
        }

    suspend fun weights(start: Instant, end: Instant): List<WeightEntry> =
        readAll(WeightRecord::class, TimeRangeFilter.between(start, end)).map {
            WeightEntry(id = it.metadata.id, time = it.time, kilograms = it.weight.inKilograms)
        }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = range,
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun kindOf(type: Int): ExerciseKind = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        -> ExerciseKind.WALKING

        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        -> ExerciseKind.RUNNING

        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
        -> ExerciseKind.STRENGTH

        ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        -> ExerciseKind.CYCLING

        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        -> ExerciseKind.SWIMMING

        else -> ExerciseKind.OTHER
    }

    private companion object {
        const val PAGE_SIZE = 1000
    }
}
