package ar.nahuel.bitacora.routine

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class RoutineEntry(val date: LocalDate, val routineId: String) {
    val key: String get() = "$date$SEPARATOR$routineId"

    companion object {
        const val SEPARATOR = "|"
        val VALID_IDS = setOf("A", "B")

        fun parse(raw: String): RoutineEntry? {
            val id = raw.substringAfter(SEPARATOR, "")
            if (id !in VALID_IDS) return null
            val date = try {
                LocalDate.parse(raw.substringBefore(SEPARATOR))
            } catch (e: DateTimeParseException) {
                return null
            }
            return RoutineEntry(date, id)
        }
    }
}

class RoutineLog(context: Context) {

    private val prefs = context.getSharedPreferences("routine_log", Context.MODE_PRIVATE)

    @Synchronized
    fun startDate(today: LocalDate): LocalDate {
        prefs.getString(KEY_START, null)?.let { stored -> parseDate(stored)?.let { return it } }
        prefs.edit().putString(KEY_START, today.toString()).apply()
        return today
    }

    @Synchronized
    fun entries(): List<RoutineEntry> = read(KEY_ENTRIES).sortedBy { it.date }

    @Synchronized
    fun pendingDeletes(): List<RoutineEntry> = read(KEY_DELETED)

    @Synchronized
    fun toggle(date: LocalDate, routineId: String) {
        val entry = RoutineEntry(date, routineId)
        val entries = rawSet(KEY_ENTRIES)
        val deleted = rawSet(KEY_DELETED)
        if (entries.remove(entry.key)) {
            deleted += entry.key
        } else {
            entries += entry.key
            deleted -= entry.key
        }
        prefs.edit().putStringSet(KEY_ENTRIES, entries).putStringSet(KEY_DELETED, deleted).apply()
    }

    @Synchronized
    fun confirmDeleted(confirmed: Collection<RoutineEntry>) {
        val deleted = rawSet(KEY_DELETED)
        deleted -= confirmed.map { it.key }.toSet()
        prefs.edit().putStringSet(KEY_DELETED, deleted).apply()
    }

    @Synchronized
    fun mergeRemote(remote: Collection<RoutineEntry>) {
        val deleted = rawSet(KEY_DELETED)
        val entries = rawSet(KEY_ENTRIES)
        entries += remote.map { it.key }.filter { it !in deleted }
        val editor = prefs.edit().putStringSet(KEY_ENTRIES, entries)
        val earliest = entries.mapNotNull(RoutineEntry::parse).minOfOrNull { it.date }
        val start = prefs.getString(KEY_START, null)?.let(::parseDate)
        if (earliest != null && (start == null || earliest.isBefore(start))) editor.putString(KEY_START, earliest.toString())
        editor.apply()
    }

    private fun rawSet(key: String): MutableSet<String> = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()

    private fun read(key: String): List<RoutineEntry> = rawSet(key).mapNotNull(RoutineEntry::parse)

    private fun parseDate(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private companion object {
        const val KEY_START = "start"
        const val KEY_ENTRIES = "entries"
        const val KEY_DELETED = "deleted"
    }
}
