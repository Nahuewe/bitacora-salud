package ar.nahuel.bitacora.sync

import ar.nahuel.bitacora.BuildConfig
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectAvailability
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.remote.ApiError
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.remote.AuthSession
import ar.nahuel.bitacora.remote.SupabaseApi
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class SyncTrigger(val wire: String) { MANUAL("manual"), PERIODIC("periodic") }

sealed interface SyncOutcome {
    data class Success(val counts: Map<String, Int>) : SyncOutcome
    data object NotConfigured : SyncOutcome
    data object NotSignedIn : SyncOutcome
    data object HealthConnectUnavailable : SyncOutcome
    data object MissingPermissions : SyncOutcome
    data object AlreadyRunning : SyncOutcome
    data class Failed(val code: String, val retryable: Boolean) : SyncOutcome
}

class SyncService(
    private val health: HealthConnectSource,
    private val auth: AuthRepository,
    private val api: SupabaseApi,
    private val state: SyncState,
    private val json: Json,
) {
    private val lock = Mutex()

    suspend fun run(trigger: SyncTrigger): SyncOutcome {
        if (!lock.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            return execute(trigger)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun execute(trigger: SyncTrigger): SyncOutcome {
        if (!api.isConfigured) return SyncOutcome.NotConfigured
        if (health.availability() != HealthConnectAvailability.AVAILABLE) return SyncOutcome.HealthConnectUnavailable

        val granted = try {
            health.grantedPermissions()
        } catch (e: SecurityException) {
            return SyncOutcome.MissingPermissions
        }
        if (!granted.containsAll(HealthAccess.core)) return SyncOutcome.MissingPermissions
        if (trigger == SyncTrigger.PERIODIC && HealthAccess.BACKGROUND !in granted) return SyncOutcome.MissingPermissions

        val session = try {
            auth.validSession()
        } catch (e: ApiError) {
            return fail(e.code, e.retryable)
        } ?: return SyncOutcome.NotSignedIn

        val startedAt = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val status = state.read()
        val historyDays = if (HealthAccess.HISTORY in granted) INITIAL_HISTORY_DAYS else RECENT_DAYS
        val windowStart = if (status.initialSyncDone && status.lastSuccess != null) {
            status.lastSuccess.minus(Duration.ofDays(OVERLAP_DAYS))
        } else {
            today.minusDays(historyDays).atStartOfDay(zone).toInstant()
        }
        val windowStartDate = windowStart.atZone(zone).toLocalDate()
        val recomputeFrom = today.minusDays(DAILY_RECOMPUTE_DAYS)
        val dailyFrom = if (windowStartDate.isBefore(recomputeFrom)) windowStartDate else recomputeFrom
        val windowEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

        return try {
            val days = health.dailyActivity(dailyFrom, today).map { it.toRow() }
            val heart = health.heartRateSamples(windowStart, windowEnd)
            val sessions = health.exerciseSessions(windowStart, windowEnd, heart).map { it.toRow() }
            val sleeps = health.sleepSessions(windowStart, windowEnd, heart).map { it.toRow() }
            val weights = health.weights(windowStart, windowEnd).mapNotNull { it.toRow() }

            push("daily_activity", "user_id,day", days, DailyRow.serializer(), session)
            push("exercise_sessions", "user_id,hc_id", sessions, ExerciseRow.serializer(), session)
            push("sleep_sessions", "user_id,hc_id", sleeps, SleepRow.serializer(), session)
            push("weight_entries", "user_id,hc_id", weights, WeightRow.serializer(), session)

            val counts = mapOf(
                "daily_activity" to days.size,
                "exercise_sessions" to sessions.size,
                "sleep_sessions" to sleeps.size,
                "weight_entries" to weights.size,
            )
            val finishedAt = Instant.now()
            record(trigger, startedAt, finishedAt, "success", counts, null, session)
            state.markSuccess(finishedAt)
            SyncOutcome.Success(counts)
        } catch (e: SecurityException) {
            fail("health_permission", false)
            SyncOutcome.MissingPermissions
        } catch (e: ApiError) {
            if (e !is ApiError.Network) {
                record(trigger, startedAt, Instant.now(), "failed", emptyMap(), e.code, session)
            }
            fail(e.code, e.retryable)
        } catch (e: IllegalStateException) {
            fail("health_read", true)
        } catch (e: android.os.RemoteException) {
            fail("health_remote", true)
        }
    }

    private suspend fun <T> push(
        table: String,
        conflict: String,
        rows: List<T>,
        serializer: KSerializer<T>,
        session: AuthSession,
    ) {
        rows.chunked(CHUNK_SIZE).forEach { chunk ->
            api.upsert(table, conflict, json.encodeToString(ListSerializer(serializer), chunk), session)
        }
    }

    private suspend fun record(
        trigger: SyncTrigger,
        startedAt: Instant,
        finishedAt: Instant,
        status: String,
        counts: Map<String, Int>,
        errorCode: String?,
        session: AuthSession,
    ) {
        val row = SyncRunRow(
            trigger = trigger.wire,
            startedAt = startedAt.toString(),
            finishedAt = finishedAt.toString(),
            status = status,
            counts = counts,
            errorCode = errorCode,
            appVersion = BuildConfig.VERSION_NAME,
        )
        runCatching { api.insert("sync_runs", json.encodeToString(SyncRunRow.serializer(), row), session) }
    }

    private fun fail(code: String, retryable: Boolean): SyncOutcome {
        state.markFailure(Instant.now(), code)
        return SyncOutcome.Failed(code, retryable)
    }

    private companion object {
        const val INITIAL_HISTORY_DAYS = 365L
        const val RECENT_DAYS = 29L
        const val OVERLAP_DAYS = 3L
        const val DAILY_RECOMPUTE_DAYS = 10L
        const val CHUNK_SIZE = 500
    }
}
