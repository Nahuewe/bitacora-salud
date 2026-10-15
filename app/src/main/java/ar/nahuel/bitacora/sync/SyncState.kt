package ar.nahuel.bitacora.sync

import android.content.Context
import java.time.Instant

data class SyncStatus(
    val lastSuccess: Instant?,
    val lastAttempt: Instant?,
    val lastErrorCode: String?,
    val initialSyncDone: Boolean,
)

class SyncState(context: Context) {

    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)

    fun read(): SyncStatus = SyncStatus(
        lastSuccess = prefs.getLong(LAST_SUCCESS, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastAttempt = prefs.getLong(LAST_ATTEMPT, 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli),
        lastErrorCode = prefs.getString(LAST_ERROR, null),
        initialSyncDone = prefs.getBoolean(INITIAL_DONE, false),
    )

    fun markSuccess(at: Instant) {
        prefs.edit()
            .putLong(LAST_SUCCESS, at.toEpochMilli())
            .putLong(LAST_ATTEMPT, at.toEpochMilli())
            .remove(LAST_ERROR)
            .putBoolean(INITIAL_DONE, true)
            .apply()
    }

    fun markFailure(at: Instant, code: String) {
        prefs.edit()
            .putLong(LAST_ATTEMPT, at.toEpochMilli())
            .putString(LAST_ERROR, code)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val LAST_SUCCESS = "last_success"
        const val LAST_ATTEMPT = "last_attempt"
        const val LAST_ERROR = "last_error"
        const val INITIAL_DONE = "initial_done"
    }
}
