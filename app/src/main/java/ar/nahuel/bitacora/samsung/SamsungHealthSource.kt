package ar.nahuel.bitacora.samsung

import android.app.Activity
import android.content.Context
import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseKind
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.Geo
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.domain.HeartPoint
import ar.nahuel.bitacora.domain.Route
import ar.nahuel.bitacora.domain.RoutePoint
import ar.nahuel.bitacora.domain.RouteSource
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

sealed interface SamsungStatus {
    data object Ready : SamsungStatus
    data object NeedsPermission : SamsungStatus
    data class NeedsResolution(val message: String) : SamsungStatus
    data class Unavailable(val message: String) : SamsungStatus
}

data class SamsungActivity(
    val days: List<DayActivity>,
    val sessions: List<ExerciseSession>,
    val routes: List<Route>,
)

class SamsungHealthSource(private val context: Context) {

    private var store: HealthDataStore? = null

    private val permissions: Set<Permission> = setOf(
        Permission.of(DataTypes.STEPS, AccessType.READ),
        Permission.of(DataTypes.ACTIVITY_SUMMARY, AccessType.READ),
        Permission.of(DataTypes.EXERCISE, AccessType.READ),
        Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ),
    )

    private fun store(): HealthDataStore = store ?: HealthDataService.getStore(context.applicationContext).also { store = it }

    suspend fun status(): SamsungStatus = try {
        val granted = store().getGrantedPermissions(permissions)
        if (granted.containsAll(permissions)) SamsungStatus.Ready else SamsungStatus.NeedsPermission
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResolvablePlatformException) {
        SamsungStatus.NeedsResolution(describe(e))
    } catch (e: HealthDataException) {
        SamsungStatus.Unavailable(describe(e))
    } catch (e: Exception) {
        SamsungStatus.Unavailable("No se pudo conectar con Samsung Health (${e.javaClass.simpleName}).")
    }

    suspend fun connect(activity: Activity): SamsungStatus = try {
        val granted = store().requestPermissions(permissions, activity)
        if (granted.containsAll(permissions)) SamsungStatus.Ready else SamsungStatus.NeedsPermission
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResolvablePlatformException) {
        if (e.hasResolution) e.resolve(activity)
        SamsungStatus.NeedsResolution(describe(e))
    } catch (e: HealthDataException) {
        SamsungStatus.Unavailable(describe(e))
    } catch (e: Exception) {
        SamsungStatus.Unavailable("No se pudo conectar con Samsung Health (${e.javaClass.simpleName}).")
    }

    suspend fun load(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): SamsungActivity {
        val filter = LocalTimeFilter.of(from.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay())
        val steps = daily(DataType.StepsType.TOTAL, filter)
        val activeTime = daily(DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME, filter)
        val distance = daily(DataType.ActivitySummaryType.TOTAL_DISTANCE, filter)
        val calories = daily(DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED, filter)

        val dates = (steps.keys + activeTime.keys).filter { !it.isBefore(from) && !it.isAfter(toInclusive) }.sortedBy { it.toEpochDay() }
        val days = dates.map { date ->
            DayActivity(
                date = date,
                steps = steps[date] ?: 0L,
                distanceMeters = distance[date]?.toDouble(),
                activeKilocalories = calories[date]?.toDouble(),
                activeMinutes = activeTime[date]?.toMinutes() ?: 0L,
                sources = mapOf(SOURCE_NAME to (steps[date] ?: 0L)),
            )
        }

        val sessions = mutableListOf<ExerciseSession>()
        val routes = mutableListOf<Route>()
        exercisePoints(filter).forEach { point ->
            val predefined = point.getValue(DataType.ExerciseType.EXERCISE_TYPE)
            val title = point.getValue(DataType.ExerciseType.CUSTOM_TITLE)
            point.getValue(DataType.ExerciseType.SESSIONS).orEmpty().forEachIndexed { index, session ->
                val id = "sh:${point.uid}:$index"
                val type = session.exerciseType ?: predefined
                val kind = kindOf(type?.name)
                sessions += ExerciseSession(
                    id = id,
                    start = session.startTime,
                    end = session.endTime,
                    type = healthConnectType(kind, type?.name),
                    kind = kind,
                    title = session.customTitle ?: title,
                    distanceMeters = session.distance?.toDouble(),
                    avgHeartRate = session.meanHeartRate?.let { Math.round(it).toLong() },
                    maxHeartRate = session.maxHeartRate?.let { Math.round(it).toLong() },
                    kilocalories = session.calories.toDouble(),
                    sourceApp = point.dataSource?.appId ?: SOURCE_PACKAGE,
                )
                val points = session.route.orEmpty()
                    .filter { Geo.isValid(it.latitude.toDouble(), it.longitude.toDouble()) }
                    .map { RoutePoint(it.timestamp.toEpochMilli(), it.latitude.toDouble(), it.longitude.toDouble()) }
                    .sortedBy { it.timeMs }
                if (points.size >= MIN_ROUTE_POINTS) {
                    val heart = session.log.orEmpty()
                        .mapNotNull { log -> log.heartRate?.takeIf { it > 0f }?.let { HeartPoint(log.timestamp.toEpochMilli(), Math.round(it).toLong()) } }
                        .sortedBy { it.timeMs }
                    routes += Route(id, session.startTime, session.endTime, Geo.thin(points), RouteSource.SAMSUNG_SDK, heart)
                }
            }
        }
        return SamsungActivity(days, sessions.sortedBy { it.start }, routes)
    }

    private suspend fun <T> daily(
        operation: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>,
        filter: LocalTimeFilter,
    ): Map<LocalDate, T> {
        val out = HashMap<LocalDate, T>()
        var token: String? = null
        do {
            val builder = operation.requestBuilder
                .setLocalTimeFilterWithGroup(filter, LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1))
                .setPageSize(PAGE_SIZE)
            token?.let { builder.setPageToken(it) }
            val response = store().aggregateData(builder.build())
            response.dataList.forEach { item -> item.value?.let { out[item.startLocalDateTime.toLocalDate()] = it } }
            token = response.pageToken?.takeIf { it.isNotBlank() }
        } while (token != null)
        return out
    }

    private suspend fun exercisePoints(filter: LocalTimeFilter): List<HealthDataPoint> {
        val out = mutableListOf<HealthDataPoint>()
        var token: String? = null
        do {
            val builder = DataTypes.EXERCISE.readDataRequestBuilder
                .setLocalTimeFilter(filter)
                .setPageSize(EXERCISE_PAGE_SIZE)
            token?.let { builder.setPageToken(it) }
            val response = store().readData(builder.build())
            out += response.dataList
            token = response.pageToken?.takeIf { it.isNotBlank() }
        } while (token != null)
        return out
    }

    private fun describe(e: HealthDataException): String = when (e.errorCode) {
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_PLATFORM_NOT_INSTALLED -> "Samsung Health no está instalado."
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_OLD_VERSION_PLATFORM -> "Hay que actualizar Samsung Health."
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_PLATFORM_DISABLED -> "Samsung Health está desactivado."
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_PLATFORM_NOT_INITIALIZED -> "Abrí Samsung Health una vez y completá la configuración inicial."
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_INVALID_PLATFORM_SIGNATURE,
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_ACCESS_CONTROL,
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_INVALID_CALLER,
        -> "Samsung Health rechazó la app: revisá que el modo desarrollador del Data SDK esté activado."
        com.samsung.android.sdk.health.data.error.ErrorCode.ERR_NO_USER_PERMISSION -> "Faltan permisos en Samsung Health."
        else -> "Samsung Health respondió con un error (${e.errorCode ?: "?"})."
    }

    private fun kindOf(name: String?): ExerciseKind = when (name) {
        "WALKING", "HIKING", "BACKPACKING" -> ExerciseKind.WALKING
        "RUNNING", "TRACK_RUNNING", "TREADMILL" -> ExerciseKind.RUNNING
        "BIKING", "STATIONARY_BIKING", "MOUNTAIN_BIKING" -> ExerciseKind.CYCLING
        "POOL_SWIMMING", "OPEN_WATER_SWIMMING" -> ExerciseKind.SWIMMING
        "WEIGHT_MACHINE", "BENCH_PRESS", "SQUATS", "LUNGES", "LEG_PRESSES", "LEG_EXTENSIONS", "LEG_CURLS",
        "BACK_EXTENSIONS", "LAT_PULLDOWNS", "DEADLIFTS", "SHOULDER_PRESSES", "FRONT_RAISES", "LATERAL_RAISES",
        "CRUNCH", "LEG_RAISES", "PLANK", "ARM_CURLS", "ARM_EXTENSIONS", "PUSH_UPS", "PULL_UPS", "SIT_UPS",
        "CIRCUIT_TRAINING",
        -> ExerciseKind.STRENGTH
        else -> ExerciseKind.OTHER
    }

    private fun healthConnectType(kind: ExerciseKind, name: String?): Int = when (kind) {
        ExerciseKind.WALKING -> if (name == "HIKING") 37 else 79
        ExerciseKind.RUNNING -> if (name == "TREADMILL") 57 else 56
        ExerciseKind.CYCLING -> if (name == "STATIONARY_BIKING") 9 else 8
        ExerciseKind.SWIMMING -> if (name == "OPEN_WATER_SWIMMING") 73 else 74
        ExerciseKind.STRENGTH -> 70
        ExerciseKind.OTHER -> 0
    }

    companion object {
        const val SOURCE_NAME = "com.sec.android.app.shealth.sdk"
        private const val SOURCE_PACKAGE = "com.sec.android.app.shealth"
        private const val PAGE_SIZE = 1000
        private const val EXERCISE_PAGE_SIZE = 200
        private const val MIN_ROUTE_POINTS = 5
    }
}

fun mergeSessions(primary: List<ExerciseSession>, fallback: List<ExerciseSession>): List<ExerciseSession> {
    val used = mutableSetOf<String>()
    val aligned = primary.map { session ->
        val match = fallback.firstOrNull {
            it.id !in used && Duration.between(it.start, session.start).abs() <= Duration.ofMinutes(3)
        }
        if (match != null) {
            used += match.id
            session.copy(id = match.id)
        } else {
            session
        }
    }
    val extra = fallback.filter { it.id !in used && aligned.none { s -> overlaps(s, it) } }
    return (aligned + extra).sortedBy { it.start }
}

private fun overlaps(a: ExerciseSession, b: ExerciseSession): Boolean =
    a.start.isBefore(b.end) && b.start.isBefore(a.end)


fun mergeSnapshot(hc: HealthSnapshot, samsung: SamsungActivity): HealthSnapshot {
    val hcDays = hc.days.associateBy { it.date }
    val samsungDays = samsung.days.associateBy { it.date }
    val dates = (hcDays.keys + samsungDays.keys).sortedBy { it.toEpochDay() }
    val days = dates.mapNotNull { date ->
        val s = samsungDays[date]
        val h = hcDays[date]
        when {
            s == null -> h
            h == null -> s
            else -> s.copy(
                distanceMeters = s.distanceMeters ?: h.distanceMeters,
                activeKilocalories = s.activeKilocalories ?: h.activeKilocalories,
                sources = h.sources + s.sources,
                healthConnectSteps = h.healthConnectSteps ?: h.steps,
            )
        }
    }
    return hc.copy(days = days, sessions = mergeSessions(samsung.sessions, hc.sessions))
}
