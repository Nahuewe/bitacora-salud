package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class DayRow(val date: LocalDate, val steps: Long, val hasData: Boolean)

data class WeekRow(
    val weekStart: LocalDate,
    val minutes: Long,
    val sessions: Int,
    val intenseMinutes: Long,
    val isCurrent: Boolean,
)

data class NightRow(
    val wakeDate: LocalDate,
    val asleepMinutes: Long,
    val bedtime: LocalTime,
    val wake: LocalTime,
)

data class SessionWeek(
    val weekStart: LocalDate,
    val minutes: Long,
    val sessions: List<ExerciseSession>,
)

data class Dashboard(
    val today: DayActivity?,
    val days: List<DayRow>,
    val weeks: List<WeekRow>,
    val nights: List<NightRow>,
    val sessionWeeks: List<SessionWeek>,
    val stepScaleMax: Long,
    val weekScaleMax: Long,
)

object DashboardBuilder {

    fun build(
        snapshot: HealthSnapshot,
        today: LocalDate,
        thresholds: Thresholds = Thresholds(),
        dayCount: Int = 14,
        weekCount: Int = 6,
        nightCount: Int = 14,
    ): Dashboard {
        val byDate = snapshot.days.associateBy { it.date }
        val days = (0 until dayCount).map { offset ->
            val date = today.minusDays(offset.toLong())
            val entry = byDate[date]
            DayRow(date, entry?.steps ?: 0L, entry != null)
        }

        val currentWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sessionsByWeek = snapshot.sessions.groupBy {
            snapshot.localDate(it.start).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
        val weeks = (0 until weekCount).map { offset ->
            val start = currentWeek.minusWeeks(offset.toLong())
            val list = sessionsByWeek[start].orEmpty()
            WeekRow(
                weekStart = start,
                minutes = snapshot.activeMinutes(start, start.plusDays(7)),
                sessions = list.size,
                intenseMinutes = list
                    .filter { (it.avgHeartRate ?: 0L) >= thresholds.intensityHeartRate }
                    .sumOf { it.durationMinutes },
                isCurrent = offset == 0,
            )
        }

        val nights = snapshot.mainSleeps
            .sortedByDescending { it.end }
            .take(nightCount)
            .map {
                NightRow(
                    wakeDate = snapshot.localDate(it.end),
                    asleepMinutes = it.asleepMinutes,
                    bedtime = it.start.atZone(snapshot.zone).toLocalTime(),
                    wake = it.end.atZone(snapshot.zone).toLocalTime(),
                )
            }

        val sessionWeeks = sessionsByWeek.entries
            .sortedByDescending { it.key }
            .map { (start, list) ->
                SessionWeek(start, list.sumOf { it.durationMinutes }, list.sortedByDescending { it.start })
            }

        return Dashboard(
            today = byDate[today],
            days = days,
            weeks = weeks,
            nights = nights,
            sessionWeeks = sessionWeeks,
            stepScaleMax = maxOf(days.maxOfOrNull { it.steps } ?: 0L, thresholds.stepTarget + 3_000),
            weekScaleMax = maxOf(weeks.maxOfOrNull { it.minutes } ?: 0L, 300L),
        )
    }
}
