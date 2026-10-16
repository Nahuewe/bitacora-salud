package ar.nahuel.bitacora.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class Severity(val label: String) {
    ALERT("Alerta"),
    ATTENTION("Atención"),
    INFO("Sugerencia"),
    GOOD("Bien"),
}

data class Recommendation(
    val id: String,
    val severity: Severity,
    val title: String,
    val detail: String,
)

data class Thresholds(
    val stepFloor: Long = 6_000,
    val stepTarget: Long = 7_000,
    val rampRatio: Double = 1.3,
    val rampBaselineMinutes: Double = 30.0,
    val rampAbsoluteMinutes: Long = 150,
    val intensityHeartRate: Long = 135,
    val intensityMinMinutes: Long = 20,
    val strengthWindowDays: Long = 14,
    val strengthWeeklyTarget: Int = 2,
    val sleepTargetMinutes: Long = 420,
    val shortSleepMinutes: Long = 360,
    val wakeVariabilityMinutes: Double = 60.0,
    val nightHeartRateRise: Double = 3.0,
    val nightHeartRateDrop: Double = 2.0,
    val weightReminderDays: Long = 30,
)

class RecommendationEngine(private val t: Thresholds = Thresholds()) {

    fun evaluate(snapshot: HealthSnapshot, today: LocalDate): List<Recommendation> = listOfNotNull(
        loadRamp(snapshot, today),
        stepFloor(snapshot, today),
        strength(snapshot, today),
        intensity(snapshot, today),
        sleepDuration(snapshot, today),
        wakeRegularity(snapshot, today),
        nightHeartRate(snapshot, today),
        weight(snapshot, today),
    ).sortedBy { it.severity.ordinal }

    internal fun exerciseMinutes(snapshot: HealthSnapshot, fromInclusive: LocalDate, toExclusive: LocalDate): Long =
        snapshot.sessions
            .filter { val d = snapshot.localDate(it.start); d >= fromInclusive && d < toExclusive }
            .sumOf { it.durationMinutes }

    private fun loadRamp(s: HealthSnapshot, today: LocalDate): Recommendation? {
        if (s.sessions.isEmpty()) return null
        val current = exerciseMinutes(s, today.minusDays(6), today.plusDays(1))
        val baseline = exerciseMinutes(s, today.minusDays(34), today.minusDays(6)) / 4.0
        val steepRamp = baseline >= t.rampBaselineMinutes && current > baseline * t.rampRatio
        val coldStart = baseline < t.rampBaselineMinutes && current >= t.rampAbsoluteMinutes
        if (!steepRamp && !coldStart) return null
        val comparison = if (steepRamp) {
            val pct = ((current / baseline - 1) * 100).roundToLong()
            "Esta semana llevás ${Format.int(current)} min de ejercicio contra ${Format.int(baseline.roundToLong())} de promedio en las cuatro anteriores (+$pct%)."
        } else {
            "Esta semana llevás ${Format.int(current)} min de ejercicio, viniendo de casi nada en las cuatro anteriores."
        }
        return Recommendation(
            id = "ramp",
            severity = if (steepRamp) Severity.ALERT else Severity.ATTENTION,
            title = "Subiste la carga muy rápido",
            detail = "$comparison Sostené este volumen dos o tres semanas antes de sumar y después subí como máximo un 10% por semana. Si te duelen tibias, talones o rodillas, bajá un cambio.",
        )
    }

    private fun stepFloor(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val byDate = s.days.associateBy { it.date }
        val week = (1L..7L).map { today.minusDays(it) }
        if (week.none { byDate.containsKey(it) }) return null
        val under = week.filter { (byDate[it]?.steps ?: 0L) < t.stepFloor }
        if (under.isEmpty()) {
            return Recommendation(
                id = "floor",
                severity = Severity.GOOD,
                title = "Siete de siete días sobre el piso",
                detail = "Ningún día de la última semana bajó de ${Format.int(t.stepFloor)} pasos. Esa constancia es lo que más rinde.",
            )
        }
        if (under.size < 3) return null
        val weekend = under.count { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        val weekendNote = if (weekend == 2) ", incluido todo el fin de semana" else ""
        return Recommendation(
            id = "floor",
            severity = if (under.size >= 4) Severity.ATTENTION else Severity.INFO,
            title = "Sostené un piso de ${Format.int(t.stepFloor)} pasos",
            detail = "En la última semana, ${under.size} de 7 días quedaron por debajo$weekendNote. Un piso diario rinde más que alternar días fuertes con días quietos; apuntá a ${Format.int(t.stepTarget)}.",
        )
    }

    private fun strength(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val strength = s.sessions.filter { it.kind == ExerciseKind.STRENGTH }
        val windowStart = today.minusDays(t.strengthWindowDays - 1)
        val recent = strength.filter { s.localDate(it.start) >= windowStart }
        val thisWeek = strength.count { s.localDate(it.start) >= today.minusDays(6) }
        return when {
            recent.isEmpty() -> Recommendation(
                id = "strength",
                severity = Severity.ATTENTION,
                title = "Sumá fuerza dos veces por semana",
                detail = "No hay sesiones de fuerza en los últimos ${t.strengthWindowDays} días. Con 30 a 40 minutos alcanza: sentadilla a un banco, remo, press, peso muerto rumano y plancha. Es lo que más cambia la composición corporal y protege las articulaciones.",
            )
            thisWeek < t.strengthWeeklyTarget -> Recommendation(
                id = "strength",
                severity = Severity.INFO,
                title = "Falta una sesión de fuerza esta semana",
                detail = "Llevás $thisWeek de ${t.strengthWeeklyTarget}. Registrala en Samsung Health como entrenamiento de fuerza para que cuente acá.",
            )
            else -> null
        }
    }

    private fun intensity(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val week = s.sessions.filter { s.localDate(it.start) >= today.minusDays(6) }
        if (week.isEmpty()) return null
        val intense = week.any {
            (it.avgHeartRate ?: 0L) >= t.intensityHeartRate && it.durationMinutes >= t.intensityMinMinutes
        }
        if (intense) return null
        return Recommendation(
            id = "intensity",
            severity = Severity.INFO,
            title = "Agregá intensidad a una caminata",
            detail = "Ninguna sesión de esta semana promedió ${t.intensityHeartRate} ppm durante ${t.intensityMinMinutes} minutos o más. Probá 5 o 6 bloques de 3 minutos rápidos o en subida con 2 minutos suaves; las demás caminatas, tranquilas.",
        )
    }

    private fun recentNights(s: HealthSnapshot, today: LocalDate, days: Long): List<SleepSession> =
        s.mainSleeps.filter {
            val wake = s.localDate(it.end)
            wake <= today && wake > today.minusDays(days)
        }

    private fun sleepDuration(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val nights = recentNights(s, today, 14)
        if (nights.size < 5) return null
        val median = median(nights.map { it.asleepMinutes.toDouble() })
        val short = nights.count { it.asleepMinutes < t.shortSleepMinutes }
        if (median >= t.sleepTargetMinutes && short * 4 < nights.size) return null
        return Recommendation(
            id = "sleep",
            severity = if (median < t.shortSleepMinutes) Severity.ALERT else Severity.ATTENTION,
            title = "Dormí más de 7 horas",
            detail = "En las últimas ${nights.size} noches dormiste una mediana de ${Format.hoursDecimal(median)}, y $short quedaron por debajo de 6 h. La recuperación entre caminatas depende de esto.",
        )
    }

    private fun wakeRegularity(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val nights = recentNights(s, today, 14)
        if (nights.size < 5) return null
        val wakeMinutes = nights.map { val time = it.end.atZone(s.zone).toLocalTime(); time.hour * 60.0 + time.minute }
        val spread = standardDeviation(wakeMinutes)
        if (spread <= t.wakeVariabilityMinutes) return null
        return Recommendation(
            id = "wake",
            severity = Severity.INFO,
            title = "Despertate siempre a la misma hora",
            detail = "Tu hora de despertarte varía ±${spread.roundToLong()} minutos de un día a otro. Fijarla, incluso el fin de semana, ordena el sueño y baja el pulso nocturno.",
        )
    }

    private fun nightHeartRate(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val withHr = s.mainSleeps.filter { it.avgHeartRate != null }
        val recent = withHr.filter { s.localDate(it.end) > today.minusDays(7) && s.localDate(it.end) <= today }
        val baseline = withHr.filter {
            val d = s.localDate(it.end)
            d <= today.minusDays(7) && d > today.minusDays(60)
        }
        if (recent.size < 4 || baseline.size < 10) return null
        val recentAvg = recent.map { it.avgHeartRate!!.toDouble() }.average()
        val baselineAvg = baseline.map { it.avgHeartRate!!.toDouble() }.average()
        val diff = recentAvg - baselineAvg
        return when {
            diff >= t.nightHeartRateRise -> Recommendation(
                id = "night-hr",
                severity = Severity.ATTENTION,
                title = "Tu pulso nocturno subió",
                detail = "Promedio de ${recentAvg.roundToLong()} ppm durmiendo en la última semana contra ${baselineAvg.roundToLong()} en los dos meses anteriores. Puede ser cansancio acumulado, poco sueño o el inicio de una enfermedad: si se sostiene, bajá la carga unos días.",
            )
            diff <= -t.nightHeartRateDrop -> Recommendation(
                id = "night-hr",
                severity = Severity.GOOD,
                title = "Tu pulso nocturno bajó",
                detail = "${baselineAvg.roundToLong()} → ${recentAvg.roundToLong()} ppm durmiendo. Es una señal de que el cuerpo se está adaptando al entrenamiento.",
            )
            else -> null
        }
    }

    private fun weight(s: HealthSnapshot, today: LocalDate): Recommendation? {
        val last = s.weights.maxByOrNull { it.time }
        if (last == null) {
            return Recommendation(
                id = "weight",
                severity = Severity.INFO,
                title = "Registrá tu peso una vez por mes",
                detail = "No hay registros de peso en Health Connect. Sin ese dato no se puede ver si el entrenamiento está cambiando tu composición.",
            )
        }
        val days = ChronoUnit.DAYS.between(s.localDate(last.time), today)
        if (days <= t.weightReminderDays) return null
        return Recommendation(
            id = "weight",
            severity = Severity.INFO,
            title = "Hace $days días que no te pesás",
            detail = "El último registro es de ${Format.decimal(last.kilograms)} kg. Pesate una vez por mes, a la misma hora y en ayunas.",
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
