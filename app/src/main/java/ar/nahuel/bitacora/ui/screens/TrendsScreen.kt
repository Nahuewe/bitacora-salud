package ar.nahuel.bitacora.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ar.nahuel.bitacora.domain.Direction
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.Metric
import ar.nahuel.bitacora.domain.Thresholds
import ar.nahuel.bitacora.domain.Trends
import ar.nahuel.bitacora.domain.TrendsBuilder
import ar.nahuel.bitacora.ui.ContentState
import ar.nahuel.bitacora.ui.components.ColumnChart
import ar.nahuel.bitacora.ui.components.ColumnDatum
import ar.nahuel.bitacora.ui.components.RowBar
import ar.nahuel.bitacora.ui.components.Rule
import ar.nahuel.bitacora.ui.components.SectionHeader
import ar.nahuel.bitacora.ui.components.SleepWindowChart
import ar.nahuel.bitacora.ui.components.StageBar
import ar.nahuel.bitacora.ui.components.TimeSeriesChart
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.roundToLong

private val limits = Thresholds()

@Composable
fun TrendsScreen(content: ContentState, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        item(key = "masthead") {
            Column(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("TENDENCIAS", style = Type.masthead, color = ink.ink, modifier = Modifier.weight(1f))
                    Text("26 semanas", style = Type.figureSmall, color = ink.inkSoft)
                }
                Spacer(Modifier.height(6.dp))
                Rule(thickness = 3.dp, strong = true)
            }
        }
        when (content) {
            is ContentState.Loaded -> trends(content.trends)
            ContentState.Loading -> item(key = "loading") {
                Text("Leyendo seis meses de Health Connect…", style = Type.body, color = ink.inkSoft, modifier = Modifier.padding(vertical = 24.dp))
            }
            ContentState.Empty -> item(key = "empty") {
                Text("Todavía no hay datos para mostrar tendencias.", style = Type.body, color = ink.inkSoft, modifier = Modifier.padding(vertical = 24.dp))
            }
            is ContentState.Error -> item(key = "error") {
                Text(content.message, style = Type.body, color = ink.signal, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
        item(key = "end") { Spacer(Modifier.height(32.dp)) }
    }
}

private fun LazyListScope.trends(t: Trends) {
    item(key = "summary-head") { SectionHeader("Últimas 4 semanas", "vs. 4 anteriores") }
    item(key = "summary") { SummaryTable(t.summary) }

    item(key = "steps") {
        SectionHeader("Pasos · promedio diario por semana")
        Caption("Barra negra: semana con promedio sobre ${Format.int(limits.stepTarget)}. Racha actual: ${t.streakDays} ${if (t.streakDays == 1) "día" else "días"} seguidos sobre el piso.")
        val max = maxOf(t.weeklySteps.maxOfOrNull { it.avgSteps } ?: 0L, limits.stepTarget + 2_000).toFloat()
        val last = t.weeklySteps.lastIndex
        ColumnChart(
            data = t.weeklySteps.mapIndexed { i, w ->
                ColumnDatum(
                    label = weekLabel(w.weekStart),
                    value = w.avgSteps.toFloat(),
                    accent = w.avgSteps >= limits.stepTarget,
                    valueText = if (i == last) shortK(w.avgSteps) else null,
                )
            },
            maxValue = max,
            reference = limits.stepTarget.toFloat(),
            referenceLabel = shortK(limits.stepTarget),
            labelEvery = 4,
            description = "Pasos promedio por semana en las últimas ${t.weekCount} semanas. Esta semana: ${Format.int(t.weeklySteps.lastOrNull()?.avgSteps ?: 0L)}.",
        )
    }

    item(key = "weekday") {
        SectionHeader("Pasos · por día de la semana", "8 semanas")
        Spacer(Modifier.height(6.dp))
        val max = maxOf(t.weekdayProfile.maxOfOrNull { it.avgSteps } ?: 0L, limits.stepTarget + 2_000).toFloat()
        val lowest = t.weekdayProfile.filter { it.samples > 0 }.minByOrNull { it.avgSteps }
        t.weekdayProfile.forEach { d ->
            val name = d.day.getDisplayName(TextStyle.SHORT, Format.locale).trimEnd('.')
            RowBar(
                label = name,
                value = d.avgSteps.toFloat(),
                maxValue = max,
                valueText = Format.int(d.avgSteps),
                description = "${d.day.getDisplayName(TextStyle.FULL, Format.locale)}: ${Format.int(d.avgSteps)} pasos de promedio",
                reference = limits.stepTarget.toFloat(),
                emphasized = d == lowest,
                over = d.avgSteps >= limits.stepTarget,
            )
        }
        lowest?.let {
            Caption("Tu día más flojo es el ${it.day.getDisplayName(TextStyle.FULL, Format.locale)}: es donde más rinde planificar una caminata fija.")
        }
    }

    item(key = "load") {
        SectionHeader("Ejercicio · minutos por semana")
        val ratio = t.loadRatio
        Caption(
            buildString {
                append("Negro: minutos con pulso promedio de ${limits.intensityHeartRate} ppm o más. Gris: el resto.")
                if (ratio != null) {
                    append(" Carga de esta semana contra el promedio del mes anterior: ").append(Format.decimal(ratio)).append("×")
                    append(if (ratio > limits.rampRatio) ", por encima de ${Format.decimal(limits.rampRatio)}× sube el riesgo de lesión." else ", dentro de un rango seguro.")
                }
            },
            warn = ratio != null && ratio > limits.rampRatio,
        )
        val max = maxOf(t.weeklyLoad.maxOfOrNull { it.minutes } ?: 0L, 150L).toFloat()
        val last = t.weeklyLoad.lastIndex
        ColumnChart(
            data = t.weeklyLoad.mapIndexed { i, w ->
                ColumnDatum(
                    label = weekLabel(w.weekStart),
                    value = w.minutes.toFloat(),
                    part = w.intenseMinutes.toFloat(),
                    valueText = if (i == last || w.minutes.toFloat() == max) "${w.minutes}" else null,
                )
            },
            maxValue = max * 1.1f,
            reference = 150f,
            referenceLabel = "150 min",
            labelEvery = 4,
            description = "Minutos de ejercicio por semana. Esta semana: ${t.weeklyLoad.lastOrNull()?.minutes ?: 0} minutos.",
        )
    }

    item(key = "speed") {
        SectionHeader("Velocidad caminando", "km/h · caminatas de 20 min o más")
        if (t.walkSpeed.size < 3) {
            Caption("Hacen falta al menos tres caminatas con distancia para ver la tendencia.")
        } else {
            val first = TrendsBuilder.median(t.walkSpeed.take(5).map { it.value })
            val recent = TrendsBuilder.median(t.walkSpeed.takeLast(5).map { it.value })
            Caption("Puntos: cada caminata. Línea: mediana móvil. Pasaste de ${Format.decimal(first)} a ${Format.decimal(recent)} km/h.")
            TimeSeriesChart(
                points = t.walkSpeed,
                trend = TrendsBuilder.rollingMedian(t.walkSpeed),
                start = t.rangeStart,
                end = t.rangeEnd,
                valueFormat = { Format.decimal(it) },
                description = "Velocidad de caminata: de ${Format.decimal(first)} a ${Format.decimal(recent)} kilómetros por hora.",
            )
        }
    }

    item(key = "sleep-window") {
        SectionHeader("Sueño · ventana de las últimas noches")
        if (t.sleepWindows.isEmpty()) {
            Caption("Sin registros de sueño.")
        } else {
            Caption("Cada barra va de la hora en que te dormiste a la que te despertaste. Negro: 7 h o más. Naranja: menos de 6 h. Sábados y domingos, con la fecha resaltada.")
            Spacer(Modifier.height(6.dp))
            SleepWindowChart(
                windows = t.sleepWindows,
                description = "Ventana de sueño de las últimas ${t.sleepWindows.size} noches.",
            )
        }
    }

    item(key = "sleep-weekly") {
        SectionHeader("Sueño · horas por noche, promedio semanal")
        val values = t.weeklySleep
        val last = values.lastIndex
        ColumnChart(
            data = values.mapIndexed { i, w ->
                ColumnDatum(
                    label = weekLabel(w.weekStart),
                    value = w.avgMinutes / 60f,
                    accent = w.avgMinutes >= limits.sleepTargetMinutes,
                    valueText = if (i == last && w.nights > 0) Format.decimal(w.avgMinutes / 60.0) else null,
                )
            },
            maxValue = 10f,
            reference = limits.sleepTargetMinutes / 60f,
            referenceLabel = "7 h",
            labelEvery = 4,
            description = "Horas de sueño por noche, promedio semanal.",
        )
    }

    t.stages?.let { stages ->
        item(key = "stages") {
            SectionHeader("Sueño · etapas", "${stages.nights} noches")
            Spacer(Modifier.height(10.dp))
            StageBar(stages)
        }
    }

    item(key = "night-hr") {
        SectionHeader("Pulso durmiendo", "ppm · 90 días")
        if (t.nightHeartRate.size < 3) {
            Caption("Hacen falta más noches con pulso registrado.")
        } else {
            val baseline = t.nightHeartRateBaseline
            Caption(
                if (baseline != null) {
                    "La línea punteada es tu base (${baseline.roundToLong()} ppm). Bajar con el tiempo es señal de adaptación; varias noches por encima suelen indicar cansancio o poco sueño."
                } else {
                    "Bajar con el tiempo es señal de adaptación al entrenamiento."
                },
            )
            TimeSeriesChart(
                points = t.nightHeartRate,
                connect = true,
                start = t.rangeEnd.minusDays(90),
                end = t.rangeEnd,
                reference = baseline,
                referenceLabel = baseline?.let { "base ${it.roundToLong()}" },
                valueFormat = { it.roundToLong().toString() },
                description = "Pulso promedio durante el sueño en los últimos 90 días.",
            )
        }
    }

    item(key = "weight") {
        SectionHeader("Peso", "kg")
        if (t.weights.size < 2) {
            Caption(
                t.weights.lastOrNull()?.let { "Último registro: ${Format.decimal(it.value)} kg el ${Format.dayMonth(it.date)}. Pesate una vez por mes para ver la curva." }
                    ?: "Sin registros de peso en Health Connect. Pesate una vez por mes y cargalo en Samsung Health.",
            )
        } else {
            TimeSeriesChart(
                points = t.weights,
                connect = true,
                start = t.weights.first().date,
                end = t.rangeEnd,
                valueFormat = { Format.decimal(it) },
                description = "Peso: de ${Format.decimal(t.weights.first().value)} a ${Format.decimal(t.weights.last().value)} kilos.",
            )
        }
    }
}

@Composable
private fun Caption(text: String, warn: Boolean = false) {
    val ink = LocalInk.current
    Text(text, style = Type.small, color = if (warn) ink.signal else ink.inkSoft, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
}

@Composable
private fun SummaryTable(metrics: List<Metric>) {
    val ink = LocalInk.current
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
            Text("", modifier = Modifier.weight(1f))
            Text("ahora", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
            Text("antes", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
            Text("cambio", style = Type.small, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
        }
        metrics.filter { it.current != null || it.previous != null }.forEach { m ->
            val delta = if (m.current != null && m.previous != null) m.current - m.previous else null
            val better = when {
                delta == null || abs(delta) < 1e-9 || m.direction == Direction.NEUTRAL -> null
                m.direction == Direction.HIGHER_IS_BETTER -> delta > 0
                else -> delta < 0
            }
            val deltaText = delta?.let { (if (it > 0) "▲ +" else if (it < 0) "▼ −" else "= ") + number(abs(it), m.decimals) } ?: "—"
            val description = "${m.label}: ahora ${m.current?.let { number(it, m.decimals) } ?: "sin datos"} ${m.unit}, antes ${m.previous?.let { number(it, m.decimals) } ?: "sin datos"}" +
                (better?.let { if (it) ", mejoró" else ", empeoró" } ?: "")
            Column(Modifier.clearAndSetSemantics { contentDescription = description }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = Type.body, color = ink.ink)
                        if (m.unit.isNotEmpty()) Text(m.unit, style = Type.small, color = ink.inkSoft)
                    }
                    Text(m.current?.let { number(it, m.decimals) } ?: "—", style = Type.figure, color = ink.ink, textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
                    Text(m.previous?.let { number(it, m.decimals) } ?: "—", style = Type.figure, color = ink.inkSoft, textAlign = TextAlign.End, modifier = Modifier.width(68.dp))
                    Text(
                        deltaText,
                        style = Type.figureSmall,
                        color = when (better) {
                            true -> ink.good
                            false -> ink.signal
                            null -> ink.inkSoft
                        },
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(72.dp),
                    )
                }
                Rule()
            }
        }
    }
}

private fun number(value: Double, decimals: Int): String =
    if (decimals == 0) Format.int(value.roundToLong()) else Format.decimal(value)

private fun shortK(value: Long): String =
    if (value >= 1_000) Format.decimal(value / 1000.0) + "k" else value.toString()

private fun weekLabel(date: java.time.LocalDate): String = "${date.dayOfMonth}/${date.monthValue}"
