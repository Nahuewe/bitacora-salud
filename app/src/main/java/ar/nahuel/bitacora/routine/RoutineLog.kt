package ar.nahuel.bitacora.routine

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class RoutineEntry(val date: LocalDate, val routineId: String)

class RoutineLog(context: Context) {

    private val prefs = context.getSharedPreferences("routine_log", Context.MODE_PRIVATE)

    @Synchronized
    fun startDate(today: LocalDate): LocalDate {
        prefs.getString(KEY_START, null)?.let { stored ->
            parse(stored)?.let { return it }
        }
        prefs.edit().putString(KEY_START, today.toString()).apply()
        return today
    }

    @Synchronized
    fun entries(): List<RoutineEntry> =
        prefs.getStringSet(KEY_ENTRIES, emptySet()).orEmpty()
            .mapNotNull { raw ->
                val date = parse(raw.substringBefore(SEPARATOR)) ?: return@mapNotNull null
                RoutineEntry(date, raw.substringAfter(SEPARATOR))
            }
            .sortedBy { it.date }

    @Synchronized
    fun toggle(date: LocalDate, routineId: String) {
        val key = "$date$SEPARATOR$routineId"
        val current = prefs.getStringSet(KEY_ENTRIES, emptySet()).orEmpty().toMutableSet()
        if (!current.remove(key)) current += key
        prefs.edit().putStringSet(KEY_ENTRIES, current).apply()
    }

    private fun parse(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        const val KEY_START = "start"
        const val KEY_ENTRIES = "entries"
        const val SEPARATOR = "|"
    }
}
