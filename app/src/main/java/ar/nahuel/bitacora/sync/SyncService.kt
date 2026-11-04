package ar.nahuel.bitacora.sync

import ar.nahuel.bitacora.BuildConfig
import ar.nahuel.bitacora.data.ActivityRepository
import ar.nahuel.bitacora.health.HealthAccess
import ar.nahuel.bitacora.health.HealthConnectAvailability
import ar.nahuel.bitacora.health.HealthConnectSource
import ar.nahuel.bitacora.remote.ApiError
import ar.nahuel.bitacora.remote.AuthRepository
import ar.nahuel.bitacora.remote.AuthSession
import ar.nahuel.bitacora.remote.PostgrestFilter
import ar.nahuel.bitacora.remote.SupabaseApi
import ar.nahuel.bitacora.routine.RoutineEntry
import ar.nahuel.bitacora.routine.RoutineLog
import ar.nahuel.bitacora.samsung.SamsungStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
    private val routines: RoutineLog,
    private val activity: ActivityRepository,
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
        val window = SyncPlan.window(status.lastSuccess, status.initialSyncDone, HealthAccess.HISTORY in granted, today, zone)

        return try {
            val combined = activity.read(window.dailyFrom, today, zone)
            val snapshot = combined.snapshot
            val days = snapshot.days.filter { !it.date.isBefore(window.dailyFrom) }.map { it.toRow() }
            val sessions = snapshot.sessions.filter { !it.start.isBefore(window.start) }.map { it.toRow() }
            val sleeps = snapshot.sleeps.filter { !it.start.isBefore(window.start) }.map { it.toRow() }
            val weights = snapshot.weights.filter { !it.time.isBefore(window.start) }.mapNotNull { it.toRow() }

            push("daily_activity", "user_id,day", days, DailyRow.serializer(), session)
            push("exercise_sessions", "user_id,hc_id", sessions, ExerciseRow.serializer(), session)
            push("sleep_sessions", "user_id,hc_id", sleeps, SleepRow.serializer(), session)
            push("weight_entries", "user_id,hc_id", weights, WeightRow.serializer(), session)

            val sessionsComplete = combined.status != SamsungStatus.Ready || combined.samsungComplete
            val removed = buildMap {
                if (sessionsComplete) put("exercise_sessions", reconcile("exercise_sessions", "started_at", window, sessions.map { it.hcId }, session))
                put("sleep_sessions", reconcile("sleep_sessions", "started_at", window, sleeps.map { it.hcId }, session))
                put("weight_entries", reconcile("weight_entries", "measured_at", window, weights.map { it.hcId }, session))
            }
            val strength = syncStrength(session)

            val counts = mapOf(
                "daily_activity" to days.size,
                "exercise_sessions" to sessions.size,
                "sleep_sessions" to sleeps.size,
                "weight_entries" to weights.size,
                "strength_logs" to strength,
                "removed" to removed.values.sum(),
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

    private suspend fun reconcile(table: String, timeColumn: String, window: SyncWindow, localIds: List<String>, session: AuthSession): Int {
        val raw = api.selectWhere(
            table,
            "hc_id",
            listOf(
                timeColumn to "gte.${window.start}",
                timeColumn to "lt.${window.end}",
                "deleted_at" to "is.null",
            ),
            session,
        )
        val remote = try {
            json.decodeFromString(ListSerializer(LiveIdRow.serializer()), raw).map { it.hcId }
        } catch (e: SerializationException) {
            return 0
        }
        val stale = SyncPlan.staleIds(remote, localIds) ?: return 0
        val patch = json.encodeToString(DeletedAtPatch.serializer(), DeletedAtPatch(Instant.now().toString()))
        stale.chunked(DELETE_CHUNK).forEach { chunk ->
            api.updateWhere(table, listOf("hc_id" to PostgrestFilter.inList(chunk), "deleted_at" to "is.null"), patch, session)
        }
        return stale.size
    }

    private suspend fun syncStrength(session: AuthSession): Int = try {
        pushAndPullStrength(session)
    } catch (e: ApiError.Rejected) {
        STRENGTH_TABLE_MISSING
    } catch (e: SerializationException) {
        STRENGTH_TABLE_MISSING
    }

    private suspend fun pushAndPullStrength(session: AuthSession): Int {
        val local = routines.entries()
        push("strength_logs", "user_id,day,routine_id", local.map { StrengthRow(it.date.toString(), it.routineId) }, StrengthRow.serializer(), session)
        val deleted = routines.pendingDeletes()
        deleted.forEach { api.delete("strength_logs", mapOf("day" to it.date.toString(), "routine_id" to it.routineId), session) }
        routines.confirmDeleted(deleted)
        val remote = json.decodeFromString(ListSerializer(StrengthRow.serializer()), api.select("strength_logs", "day,routine_id", session))
        routines.mergeRemote(remote.mapNotNull { RoutineEntry.parse("${it.day}${RoutineEntry.SEPARATOR}${it.routineId}") })
        return local.size
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

    companion object {
        const val STRENGTH_TABLE_MISSING = -1
        private const val CHUNK_SIZE = 500
        private const val DELETE_CHUNK = 100
    }
}
