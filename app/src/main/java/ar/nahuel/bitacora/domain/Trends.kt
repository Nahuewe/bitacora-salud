package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class WeekSteps(val weekStart: LocalDate, val avgSteps: Long, val daysWithData: Int, val daysOverTarget: Int)

data class WeekdaySteps(val day: DayOfWeek, val avgSteps: Long, val samples: Int)

data class WeekLoad(val weekStart: LocalDate, val minutes: Long, val intenseMinutes: Long, val sessions: Int)

data class TimePoint(val date: LocalDate, val value: Double)

data class SleepWindow(val wakeDate: LocalDate, val bedOffsetMinutes: Long, val wakeOffsetMinutes: Long, val asleepMinutes: Long)

data class WeekSleep(val weekStart: LocalDate, val avgMinutes: Long, val nights: Int)

data class StageShare(val deep: Double, val rem: Double, val light: Double, val awake: Double, val nights: Int)

enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER, NEUTRAL }

data class Metric(
    val label: String,
    val current: Double?,
    val previous: Double?,
    val unit: String,
    val direction: Direction,
    val decimals: Int = 0,
)

data class Trends(
    val weekCount: Int,
    val summary: List<Metric>,
    val weeklySteps: List<WeekSteps>,
    val weekdayProfile: List<WeekdaySteps>,
    val streakDays: Int,
    val weeklyLoad: List<WeekLoad>,
    val loadRatio: Double?,
    val walkSpeed: List<TimePoint>,
    val sleepWindows: List<SleepWindow>,
    val weeklySleep: List<WeekSleep>,
    val stages: StageShare?,
    val nightHeartRate: List<TimePoint>,
    val nightHeartRateBaseline: Double?,
    val weights: List<TimePoint>,
    val rangeStart: LocalDate,
    val rangeEnd: LocalDate,
)

object TrendsBuilder {

    const val WINDOW_START_HOUR = 18L
    private const val MIN_WALK_MINUTES = 20L

    fun build(
        snapshot: HealthSnapshot,
        today: LocalDate,
        thresholds: Thresholds = Thresholds(),
        weekCount: Int = 26,
    ): Trends {
        val currentWeek = monday(today)
        val weeks = (weekCount - 1 downTo 0).map { currentWeek.minusWeeks(it.toLong()) }
        val stepsByDate = snapshot.days.associateBy { it.date }

        val weeklySteps = weeks.map { start ->
            val days = (0L..6L).map { start.plusDays(it) }.filter { !it.isAfter(today) }.mapNotNull { stepsByDate[it] }
            WeekSteps(
                weekStart = start,
                avgSteps = if (days.isEmpty()) 0 else days.sumOf { it.steps } / days.size,
                daysWithData = days.size,
                daysOverTarget = days.count { it.steps >= thresholds.stepTarget },
            )
        }

        val profileDays = (1L..56L).map { today.minusDays(it) }.mapNotNull { stepsByDate[it] }
        val weekdayProfile = DayOfWeek.values().map { day ->
            val list = profileDays.filter { it.date.dayOfWeek == day }
            WeekdaySteps(day, if (list.isEmpty()) 0 else list.sumOf { it.steps } / list.size, list.size)
        }

        val sessionsByWeek = snapshot.sessions.groupBy { monday(snapshot.localDate(it.start)) }
        val weeklyLoad = weeks.map { start ->
            val list = sessionsByWeek[start].orEmpty()
            WeekLoad(
                weekStart = start,
                minutes = snapshot.activeMinutes(start, start.plusDays(7)),
                intenseMinutes = list.filter { (it.avgHeartRate ?: 0L) >= thresholds.intensityHeartRate }.sumOf { it.durationMinutes },
                sessions = list.size,
            )
        }

        val acute = snapshot.activeMinutes(today.minusDays(6), today.plusDays(1)).toDouble()
        val chronic = snapshot.activeMinutes(today.minusDays(34), today.minusDays(6)) / 4.0
        val loadRatio = if (chronic > 0) acute / chronic else null

        val walkSpeed = snapshot.sessions
            .filter { it.kind == ExerciseKind.WALKING && it.durationMinutes >= MIN_WALK_MINUTES && (it.distanceMeters ?: 0.0) > 0 }
            .mapNotNull { session ->
                val kmh = session.distanceMeters!! / 1000.0 / (session.durationMinutes / 60.0)
                if (kmh in 1.5..9.0) TimePoint(snapshot.localDate(session.start), kmh) else null
            }
            .sortedBy { it.date }

        val nights = snapshot.mainSleeps
        val sleepWindows = nights.takeLast(14).map { sleep ->
            val wakeDate = snapshot.localDate(sleep.end)
            val anchor = wakeDate.minusDays(1).atStartOfDay().plusHours(WINDOW_START_HOUR)
            SleepWindow(
                wakeDate = wakeDate,
                bedOffsetMinutes = minutesFrom(anchor, sleep.start.atZone(snapshot.zone).toLocalDateTime()),
                wakeOffsetMinutes = minutesFrom(anchor, sleep.end.atZone(snapshot.zone).toLocalDateTime()),
                asleepMinutes = sleep.asleepMinutes,
            )
        }

        val sleepByWeek = nights.groupBy { monday(snapshot.localDate(it.end)) }
        val weeklySleep = weeks.map { start ->
            val list = sleepByWeek[start].orEmpty()
            WeekSleep(start, if (list.isEmpty()) 0 else list.sumOf { it.asleepMinutes } / list.size, list.size)
        }

        val staged = nights
            .filter { snapshot.localDate(it.end) > today.minusDays(30) }
            .filter { it.deepMinutes + it.remMinutes + it.lightMinutes > 0 }
        val stages = if (staged.isEmpty()) null else {
            val deep = staged.sumOf { it.deepMinutes }.toDouble()
            val rem = staged.sumOf { it.remMinutes }.toDouble()
            val light = staged.sumOf { it.lightMinutes }.toDouble()
            val awake = staged.sumOf { it.awakeMinutes }.toDouble()
            val total = deep + rem + light + awake
            StageShare(deep / total, rem / total, light / total, awake / total, staged.size)
        }

        val nightHeartRate = nights
            .filter { it.avgHeartRate != null && snapshot.localDate(it.end) > today.minusDays(90) }
            .map { TimePoint(snapshot.localDate(it.end), it.avgHeartRate!!.toDouble()) }
        val baselinePoints = nightHeartRate.filter { it.date <= today.minusDays(7) }
        val nightHeartRateBaseline = if (baselinePoints.size >= 10) baselinePoints.map { it.value }.average() else null

        val weights = snapshot.weights
            .sortedBy { it.time }
            .map { TimePoint(snapshot.localDate(it.time), it.kilograms) }

        return Trends(
            weekCount = weekCount,
            summary = summary(snapshot, today, thresholds, walkSpeed, nightHeartRate),
            weeklySteps = weeklySteps,
            weekdayProfile = weekdayProfile,
            streakDays = streak(stepsByDate, today, thresholds.stepTarget),
            weeklyLoad = weeklyLoad,
            loadRatio = loadRatio,
            walkSpeed = walkSpeed,
            sleepWindows = sleepWindows,
            weeklySleep = weeklySleep,
            stages = stages,
            nightHeartRate = nightHeartRate,
            nightHeartRateBaseline = nightHeartRateBaseline,
            weights = weights,
            rangeStart = weeks.first(),
            rangeEnd = today,
        )
    }

    private fun summary(
        s: HealthSnapshot,
        today: LocalDate,
        t: Thresholds,
        walkSpeed: List<TimePoint>,
        nightHeartRate: List<TimePoint>,
    ): List<Metric> {
        val currentFrom = today.minusDays(27)
        val previousFrom = today.minusDays(55)
        fun inCurrent(d: LocalDate) = d >= currentFrom && d <= today
        fun inPrevious(d: LocalDate) = d >= previousFrom && d < currentFrom

        fun avgSteps(range: (LocalDate) -> Boolean): Double? =
            s.days.filter { range(it.date) }.map { it.steps.toDouble() }.takeIf { it.isNotEmpty() }?.average()

        fun daysOver(range: (LocalDate) -> Boolean): Double? =
            s.days.filter { range(it.date) }.takeIf { it.isNotEmpty() }?.count { it.steps >= t.stepTarget }?.toDouble()

        fun weeklyMinutes(range: (LocalDate) -> Boolean, intenseOnly: Boolean): Double =
            if (intenseOnly) {
                s.sessions
                    .filter { range(s.localDate(it.start)) }
                    .filter { (it.avgHeartRate ?: 0L) >= t.intensityHeartRate }
                    .sumOf { it.durationMinutes } / 4.0
            } else {
                s.days.filter { range(it.date) }.sumOf { it.activeMinutes } / 4.0
            }

        fun medianSpeed(range: (LocalDate) -> Boolean): Double? =
            walkSpeed.filter { range(it.date) }.map { it.value }.takeIf { it.isNotEmpty() }?.let(::median)

        fun avgSleepHours(range: (LocalDate) -> Boolean): Double? =
            s.mainSleeps.filter { range(s.localDate(it.end)) }
                .map { it.asleepMinutes / 60.0 }
                .takeIf { it.isNotEmpty() }?.average()

        fun avgNightHr(range: (LocalDate) -> Boolean): Double? =
            nightHeartRate.filter { range(it.date) }.map { it.value }.takeIf { it.isNotEmpty() }?.average()

        fun lastWeight(range: (LocalDate) -> Boolean): Double? =
            s.weights.filter { range(s.localDate(it.time)) }.maxByOrNull { it.time }?.kilograms

        return listOf(
            Metric("Pasos por día", avgSteps(::inCurrent), avgSteps(::inPrevious), "", Direction.HIGHER_IS_BETTER),
            Metric("Días sobre ${Format.int(t.stepTarget)}", daysOver(::inCurrent), daysOver(::inPrevious), "de 28", Direction.HIGHER_IS_BETTER),
            Metric("Activo por semana", weeklyMinutes(::inCurrent, false), weeklyMinutes(::inPrevious, false), "min", Direction.HIGHER_IS_BETTER),
            Metric("Intenso por semana", weeklyMinutes(::inCurrent, true), weeklyMinutes(::inPrevious, true), "min", Direction.HIGHER_IS_BETTER),
            Metric("Velocidad caminando", medianSpeed(::inCurrent), medianSpeed(::inPrevious), "km/h", Direction.HIGHER_IS_BETTER, 1),
            Metric("Sueño por noche", avgSleepHours(::inCurrent), avgSleepHours(::inPrevious), "h", Direction.HIGHER_IS_BETTER, 1),
            Metric("Pulso durmiendo", avgNightHr(::inCurrent), avgNightHr(::inPrevious), "ppm", Direction.LOWER_IS_BETTER),
            Metric("Peso", lastWeight(::inCurrent), lastWeight(::inPrevious), "kg", Direction.NEUTRAL, 1),
        )
    }

    private fun streak(byDate: Map<LocalDate, DayActivity>, today: LocalDate, target: Long): Int {
        var day = if ((byDate[today]?.steps ?: 0L) >= target) today else today.minusDays(1)
        var count = 0
        while ((byDate[day]?.steps ?: 0L) >= target) {
            count++
            day = day.minusDays(1)
        }
        return count
    }


    private fun minutesFrom(anchor: LocalDateTime, time: LocalDateTime): Long = Duration.between(anchor, time).toMinutes()

    private fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    fun rollingMedian(points: List<TimePoint>, window: Int = 5): List<TimePoint> =
        points.indices.map { i ->
            val from = maxOf(0, i - window / 2)
            val to = minOf(points.size, from + window)
            TimePoint(points[i].date, median(points.subList(maxOf(0, to - window), to).map { it.value }))
        }
}
