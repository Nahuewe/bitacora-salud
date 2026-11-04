package ar.nahuel.bitacora.data

import ar.nahuel.bitacora.domain.DayActivity
import ar.nahuel.bitacora.domain.ExerciseSession
import ar.nahuel.bitacora.domain.HealthSnapshot
import ar.nahuel.bitacora.samsung.SamsungActivity
import ar.nahuel.bitacora.samsung.SamsungParts
import ar.nahuel.bitacora.samsung.SamsungStatus
import java.time.Duration

data class Combined(
    val snapshot: HealthSnapshot,
    val samsung: SamsungActivity?,
    val status: SamsungStatus?,
    val note: String?,
) {
    val samsungComplete: Boolean get() = samsung?.complete == true
    val samsungConnected: Boolean get() = status == SamsungStatus.Ready
}

object SourceMerge {

    private val MATCH_WINDOW: Duration = Duration.ofMinutes(3)

    fun combine(hc: HealthSnapshot, status: SamsungStatus?, samsung: SamsungActivity?): Combined {
        val usable = samsung?.takeIf { status == SamsungStatus.Ready && it.usable }
        val merged = if (usable == null) hc else merge(hc, usable)
        return Combined(merged, usable, status, note(status, samsung))
    }

    fun note(status: SamsungStatus?, samsung: SamsungActivity?): String? {
        if (status != SamsungStatus.Ready || samsung == null || samsung.complete) return null
        val detail = samsung.fault?.let { " ($it)" }.orEmpty()
        return if (samsung.usable) {
            "Samsung Health no entregó: ${samsung.failed.joinToString(", ")}$detail. Eso lo completo con Health Connect."
        } else {
            "Samsung Health no pudo entregar datos$detail. Uso Health Connect."
        }
    }

    fun merge(hc: HealthSnapshot, samsung: SamsungActivity): HealthSnapshot {
        val failed = samsung.failed.toSet()
        val hcDays = hc.days.associateBy { it.date }
        val samsungDays = samsung.days.associateBy { it.date }
        val dates = (hcDays.keys + samsungDays.keys).sortedBy { it.toEpochDay() }
        val days = dates.mapNotNull { date -> mergeDay(hcDays[date], samsungDays[date], failed) }
        val sessions = if (SamsungParts.WALKS in failed) hc.sessions else mergeSessions(samsung.sessions, hc.sessions)
        return hc.copy(days = days, sessions = sessions)
    }

    private fun mergeDay(h: DayActivity?, s: DayActivity?, failed: Set<String>): DayActivity? {
        if (s == null) return h
        val stepsOk = SamsungParts.STEPS !in failed
        val activeOk = SamsungParts.ACTIVE_TIME !in failed
        if (h == null) return s.takeIf { stepsOk || activeOk }
        return h.copy(
            steps = if (stepsOk) s.steps else h.steps,
            activeMinutes = if (activeOk) s.activeMinutes else h.activeMinutes,
            distanceMeters = s.distanceMeters ?: h.distanceMeters,
            activeKilocalories = s.activeKilocalories ?: h.activeKilocalories,
            sources = if (stepsOk) h.sources + s.sources else h.sources,
            healthConnectSteps = h.healthConnectSteps ?: h.steps,
        )
    }

    fun mergeSessions(primary: List<ExerciseSession>, fallback: List<ExerciseSession>): List<ExerciseSession> {
        val used = mutableSetOf<String>()
        val aligned = primary.distinctBy { it.id }.map { session ->
            val match = fallback.firstOrNull {
                it.id !in used && Duration.between(it.start, session.start).abs() <= MATCH_WINDOW
            }
            if (match != null) {
                used += match.id
                session.copy(id = match.id)
            } else {
                session
            }
        }
        val extra = fallback.filter { it.id !in used && aligned.none { s -> overlaps(s, it) } }
        return (aligned + extra).distinctBy { it.id }.sortedBy { it.start }
    }

    private fun overlaps(a: ExerciseSession, b: ExerciseSession): Boolean =
        a.start.isBefore(b.end) && b.start.isBefore(a.end)
}
