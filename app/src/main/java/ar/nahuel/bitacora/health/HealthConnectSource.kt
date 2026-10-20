package ar.nahuel.bitacora.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
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
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.SleepSession
import ar.nahuel.bitacora.domain.WeightEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

enum class HealthConnectAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

object HealthAccess {
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val ROUTES = "android.permission.health.READ_EXERCISE_ROUTES"

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

data class HeartStats(val average: Long, val max: Long)

class HeartSamples(private val times: LongArray, private val bpm: LongArray) {

    fun stats(from: Instant, to: Instant): HeartStats? {
        val start = from.toEpochMilli()
        val end = to.toEpochMilli()
        var index = lowerBound(start)
        var sum = 0L
        var count = 0
        var max = 0L
        while (index < times.size && times[index] < end) {
            val value = bpm[index]
            sum += value
            count++
            if (value > max) max = value
            index++
        }
        return if (count == 0) null else HeartStats(Math.round(sum.toDouble() / count), max)
    }

    fun at(timeMs: Long, toleranceMs: Long = 90_000L): Long? {
        if (times.isEmpty()) return null
        val index = lowerBound(timeMs)
        val candidates = listOf(index - 1, index).filter { it in times.indices }
        val best = candidates.minByOrNull { kotlin.math.abs(times[it] - timeMs) } ?: return null
        return if (kotlin.math.abs(times[best] - timeMs) <= toleranceMs) bpm[best] else null
    }

    private fun lowerBound(value: Long): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }
}

data class RouteRead(val routes: List<Route>, val consentRequired: Int)

private data class SessionTotals(val distanceMeters: Double?, val kilocalories: Double?)

class HealthConnectSource(private val context: Context) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val totalsCache = ConcurrentHashMap<String, Pair<Instant, SessionTotals>>()

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
        extra += HealthAccess.ROUTES
        return HealthAccess.core + extra
    }

    fun backgroundReadSupported(): Boolean =
        featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    private fun featureAvailable(feature: Int): Boolean =
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    suspend fun snapshot(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): HealthSnapshot {
        val start = from.atStartOfDay(zone).toInstant()
        val end = toInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        val heart = heartRateSamples(start, end)
        return HealthSnapshot(
            zone = zone,
            days = dailyActivity(from, toInclusive),
            sessions = exerciseSessions(start, end, heart),
            sleeps = sleepSessions(start, end, heart),
            weights = weights(start.minus(Duration.ofDays(365)), end),
        )
    }

    suspend fun heartRateSamples(start: Instant, end: Instant): HeartSamples {
        val samples = readAll(HeartRateRecord::class, TimeRangeFilter.between(start, end))
            .flatMap { record -> record.samples }
            .sortedBy { it.time }
        return HeartSamples(
            times = LongArray(samples.size) { samples[it].time.toEpochMilli() },
            bpm = LongArray(samples.size) { samples[it].beatsPerMinute },
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
        val zone = ZoneId.systemDefault()
        val bySource = stepsBySource(from, toInclusive, zone)
        val aggregated = groups.associateBy { it.startTime.toLocalDate() }
        val dates = (aggregated.keys + bySource.keys).filter { !it.isBefore(from) && !it.isAfter(toInclusive) }.sortedBy { it.toEpochDay() }
        return dates.mapNotNull { date ->
            val group = aggregated[date]
            val sources = bySource[date].orEmpty()
            val best = sources.maxByOrNull { it.value.steps }
            val aggregateSteps = group?.result?.get(StepsRecord.COUNT_TOTAL)
            val steps = maxOf(best?.value?.steps ?: 0L, aggregateSteps ?: 0L)
            if (best == null && aggregateSteps == null) return@mapNotNull null
            DayActivity(
                date = date,
                steps = steps,
                distanceMeters = group?.result?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
                activeKilocalories = group?.result?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
                activeMinutes = best?.value?.activeMinutes ?: 0L,
                sources = sources.mapValues { it.value.steps },
                healthConnectSteps = aggregateSteps,
            )
        }
    }

    private class SourceDay(var steps: Long = 0L, var activeMinutes: Long = 0L)

    private suspend fun stepsBySource(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Map<LocalDate, Map<String, SourceDay>> {
        val start = from.atStartOfDay(zone).toInstant()
        val end = toInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        val records = try {
            readAll(StepsRecord::class, TimeRangeFilter.between(start, end))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }
        val days = HashMap<LocalDate, HashMap<String, SourceDay>>()
        val minuteSteps = HashMap<String, HashMap<Long, Double>>()
        records.forEach { record ->
            val origin = record.metadata.dataOrigin.packageName
            val date = record.startTime.atZone(zone).toLocalDate()
            days.getOrPut(date) { HashMap() }.getOrPut(origin) { SourceDay() }.steps += record.count
            val firstMinute = record.startTime.toEpochMilli() / MINUTE_MS
            val lastMinute = maxOf(firstMinute + 1, (record.endTime.toEpochMilli() + MINUTE_MS - 1) / MINUTE_MS)
            val perMinute = record.count.toDouble() / (lastMinute - firstMinute)
            val buckets = minuteSteps.getOrPut(origin) { HashMap() }
            for (minute in firstMinute until lastMinute) buckets[minute] = (buckets[minute] ?: 0.0) + perMinute
        }
        minuteSteps.forEach { (origin, buckets) ->
            buckets.forEach { (minute, steps) ->
                if (steps >= ACTIVE_STEPS_PER_MINUTE) {
                    val date = Instant.ofEpochMilli(minute * MINUTE_MS).atZone(zone).toLocalDate()
                    days[date]?.get(origin)?.let { it.activeMinutes++ }
                }
            }
        }
        return days
    }

    suspend fun exerciseSessions(start: Instant, end: Instant, heart: HeartSamples? = null): List<ExerciseSession> {
        val samples = heart ?: heartRateSamples(start, end)
        return readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end)).map { record ->
            val totals = sessionTotals(record)
            val hr = samples.stats(record.startTime, record.endTime)
            ExerciseSession(
                id = record.metadata.id,
                start = record.startTime,
                end = record.endTime,
                type = record.exerciseType,
                kind = kindOf(record.exerciseType),
                title = record.title,
                distanceMeters = totals.distanceMeters,
                avgHeartRate = hr?.average,
                maxHeartRate = hr?.max,
                kilocalories = totals.kilocalories,
                sourceApp = record.metadata.dataOrigin.packageName,
            )
        }
    }

    private suspend fun sessionTotals(record: ExerciseSessionRecord): SessionTotals {
        val key = record.metadata.id
        val version = record.metadata.lastModifiedTime
        totalsCache[key]?.takeIf { it.first == version }?.let { return it.second }
        val totals = try {
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(record.startTime, record.endTime),
                ),
            )
            SessionTotals(
                distanceMeters = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                kilocalories = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return SessionTotals(null, null)
        }
        totalsCache[key] = version to totals
        return totals
    }

    suspend fun sleepSessions(start: Instant, end: Instant, heart: HeartSamples? = null): List<SleepSession> {
        val samples = heart ?: heartRateSamples(start, end)
        return readAll(SleepSessionRecord::class, TimeRangeFilter.between(start, end)).map { record ->
            fun minutesOf(stage: Int): Long = record.stages
                .filter { it.stage == stage }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

            val heartRate = samples.stats(record.startTime, record.endTime)?.average

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
    }

    suspend fun routes(sessions: List<ExerciseSession>): RouteRead {
        val routes = mutableListOf<Route>()
        var consentRequired = 0
        for (session in sessions) {
            val record = try {
                client.readRecord(ExerciseSessionRecord::class, session.id).record
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            when (val result = record.exerciseRouteResult) {
                is ExerciseRouteResult.Data -> {
                    val points = result.exerciseRoute.route
                        .filter { Geo.isValid(it.latitude, it.longitude) }
                        .map { RoutePoint(it.time.toEpochMilli(), it.latitude, it.longitude) }
                    if (points.size >= 5) {
                        routes += Route(session.id, session.start, session.end, Geo.thin(points), RouteSource.HEALTH_CONNECT)
                    }
                }
                is ExerciseRouteResult.ConsentRequired -> consentRequired++
                else -> Unit
            }
        }
        return RouteRead(routes, consentRequired)
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
        const val MINUTE_MS = 60_000L
        const val ACTIVE_STEPS_PER_MINUTE = 60.0
    }
}
