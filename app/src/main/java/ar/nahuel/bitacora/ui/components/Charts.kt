package ar.nahuel.bitacora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ar.nahuel.bitacora.domain.Format
import ar.nahuel.bitacora.domain.SleepWindow
import ar.nahuel.bitacora.domain.StageShare
import ar.nahuel.bitacora.domain.TimePoint
import ar.nahuel.bitacora.domain.TrendsBuilder
import ar.nahuel.bitacora.ui.theme.Ink
import ar.nahuel.bitacora.ui.theme.LocalInk
import ar.nahuel.bitacora.ui.theme.Type
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class ColumnDatum(
    val label: String,
    val value: Float,
    val part: Float = 0f,
    val accent: Boolean = false,
    val valueText: String? = null,
)

private fun DrawScope.text(measurer: TextMeasurer, value: String, style: TextStyle, x: Float, y: Float, alignEnd: Boolean = false, center: Boolean = false) {
    val layout = measurer.measure(value, style)
    val left = when {
        alignEnd -> x - layout.size.width
        center -> x - layout.size.width / 2f
        else -> x
    }.coerceIn(0f, max(0f, size.width - layout.size.width))
    drawText(layout, topLeft = Offset(left, y))
}

private fun DrawScope.dashedLine(color: Color, start: Offset, end: Offset) {
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
    )
}

private fun axisStyle(ink: Ink) = Type.figureSmall.copy(color = ink.inkSoft, fontSize = 10.sp)

private fun valueStyle(ink: Ink) = Type.figureSmall.copy(color = ink.ink, fontSize = 10.sp)

@Composable
fun ColumnChart(
    data: List<ColumnDatum>,
    maxValue: Float,
    description: String,
    modifier: Modifier = Modifier,
    reference: Float? = null,
    referenceLabel: String? = null,
    labelEvery: Int = 1,
    height: Dp = 170.dp,
) {
    val ink = LocalInk.current
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (data.isEmpty() || maxValue <= 0f) return@Canvas
        val top = 18.dp.toPx()
        val labelBand = 18.dp.toPx()
        val chartHeight = size.height - top - labelBand
        val baseY = top + chartHeight
        val slot = size.width / data.size
        val barWidth = slot * 0.64f
        val scale = chartHeight / maxValue

        data.forEachIndexed { index, datum ->
            val x = index * slot + (slot - barWidth) / 2f
            val h = datum.value.coerceIn(0f, maxValue) * scale
            drawRect(if (datum.accent) ink.ink else ink.barMuted, Offset(x, baseY - h), Size(barWidth, h))
            if (datum.part > 0f) {
                val ph = datum.part.coerceIn(0f, maxValue) * scale
                drawRect(ink.ink, Offset(x, baseY - ph), Size(barWidth, ph))
            }
            datum.valueText?.let {
                text(measurer, it, valueStyle(ink), x + barWidth / 2f, (baseY - h - 14.dp.toPx()).coerceAtLeast(0f), center = true)
            }
            if (index % labelEvery == 0) {
                text(measurer, datum.label, axisStyle(ink), x + barWidth / 2f, baseY + 4.dp.toPx(), center = true)
            }
        }
        drawLine(ink.ruleStrong, Offset(0f, baseY), Offset(size.width, baseY), 1.dp.toPx())
        if (reference != null && reference <= maxValue) {
            val y = baseY - reference * scale
            dashedLine(ink.signal, Offset(0f, y), Offset(size.width, y))
            referenceLabel?.let { text(measurer, it, axisStyle(ink).copy(color = ink.signal), size.width, y - 13.dp.toPx(), alignEnd = true) }
        }
    }
}

@Composable
fun TimeSeriesChart(
    points: List<TimePoint>,
    start: LocalDate,
    end: LocalDate,
    description: String,
    valueFormat: (Double) -> String,
    modifier: Modifier = Modifier,
    trend: List<TimePoint>? = null,
    connect: Boolean = false,
    reference: Double? = null,
    referenceLabel: String? = null,
    height: Dp = 180.dp,
) {
    val ink = LocalInk.current
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (points.isEmpty()) return@Canvas
        val values = points.map { it.value } + trend.orEmpty().map { it.value } + listOfNotNull(reference)
        var low = values.min()
        var high = values.max()
        if (high - low < 1e-6) {
            low -= 1.0
            high += 1.0
        }
        val pad = (high - low) * 0.12
        low -= pad
        high += pad

        val left = 40.dp.toPx()
        val top = 8.dp.toPx()
        val bottomBand = 18.dp.toPx()
        val width = size.width - left
        val chartHeight = size.height - top - bottomBand
        val days = max(1L, ChronoUnit.DAYS.between(start, end)).toFloat()
        fun x(date: LocalDate) = left + ChronoUnit.DAYS.between(start, date) / days * width
        fun y(value: Double) = top + ((high - value) / (high - low)).toFloat() * chartHeight

        for (step in 0..2) {
            val v = low + (high - low) * (step + 0.5) / 3.0
            val gy = y(v)
            drawLine(ink.rule, Offset(left, gy), Offset(size.width, gy), 1f)
            text(measurer, valueFormat(v), axisStyle(ink), left - 6.dp.toPx(), gy - 7.dp.toPx(), alignEnd = true)
        }

        var month = start.withDayOfMonth(1).plusMonths(1)
        while (!month.isAfter(end)) {
            val mx = x(month)
            drawLine(ink.rule, Offset(mx, top), Offset(mx, top + chartHeight), 1f)
            text(measurer, month.month.getDisplayName(DateTextStyle.SHORT, Format.locale).trimEnd('.'), axisStyle(ink), mx + 3.dp.toPx(), top + chartHeight + 4.dp.toPx())
            month = month.plusMonths(1)
        }

        if (reference != null) {
            val ry = y(reference)
            dashedLine(ink.signal, Offset(left, ry), Offset(size.width, ry))
            referenceLabel?.let { text(measurer, it, axisStyle(ink).copy(color = ink.signal), size.width, ry - 13.dp.toPx(), alignEnd = true) }
        }

        if (connect && points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value)) }
            drawPath(path, ink.ink, style = Stroke(width = 1.5.dp.toPx()))
        }
        val dotColor = if (trend != null) ink.barMuted else ink.ink
        points.forEach { drawCircle(dotColor, radius = 2.6.dp.toPx(), center = Offset(x(it.date), y(it.value))) }

        if (trend != null && trend.size > 1) {
            val path = Path()
            trend.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value)) }
            drawPath(path, ink.ink, style = Stroke(width = 2.dp.toPx()))
        }

        val last = points.last()
        text(measurer, valueFormat(last.value), valueStyle(ink), x(last.date), y(last.value) - 16.dp.toPx(), alignEnd = true)
    }
}

@Composable
fun SleepWindowChart(windows: List<SleepWindow>, description: String, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val measurer = rememberTextMeasurer()
    val rowHeight = 22.dp
    val header = 20.dp
    Canvas(
        modifier
            .fillMaxWidth()
            .height(header + rowHeight * windows.size)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        val labelWidth = 48.dp.toPx()
        val valueWidth = 50.dp.toPx()
        val axisStart = labelWidth
        val axisWidth = size.width - labelWidth - valueWidth
        val windowFrom = (20 - TrendsBuilder.WINDOW_START_HOUR) * 60f
        val windowSpan = 16 * 60f
        fun x(offset: Long) = axisStart + ((offset - windowFrom) / windowSpan).coerceIn(0f, 1f) * axisWidth
        val headerPx = header.toPx()
        val rowPx = rowHeight.toPx()

        for (hour in listOf(22, 0, 2, 4, 6, 8, 10)) {
            val offset = (((hour - TrendsBuilder.WINDOW_START_HOUR) + 24) % 24) * 60
            val gx = x(offset)
            drawLine(ink.rule, Offset(gx, headerPx - 2.dp.toPx()), Offset(gx, size.height), 1f)
            text(measurer, hour.toString().padStart(2, '0'), axisStyle(ink), gx, 0f, center = true)
        }

        windows.forEachIndexed { i, w ->
            val rowTop = headerPx + i * rowPx
            text(measurer, Format.shortDay(w.wakeDate), axisStyle(ink), 0f, rowTop + 4.dp.toPx())
            val color = when {
                w.asleepMinutes >= 420 -> ink.ink
                w.asleepMinutes >= 360 -> ink.barMuted
                else -> ink.signal
            }
            val barHeight = 9.dp.toPx()
            val from = x(w.bedOffsetMinutes)
            val to = x(w.wakeOffsetMinutes)
            drawRect(color, Offset(from, rowTop + (rowPx - barHeight) / 2f), Size(max(2f, to - from), barHeight))
            text(measurer, Format.hours(w.asleepMinutes), valueStyle(ink), size.width, rowTop + 4.dp.toPx(), alignEnd = true)
        }
    }
}

@Composable
fun StageBar(stages: StageShare, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val segments = listOf(
        Triple("Profundo", stages.deep, ink.ink),
        Triple("REM", stages.rem, ink.ink.copy(alpha = 0.6f)),
        Triple("Ligero", stages.light, ink.barMuted),
        Triple("Despierto", stages.awake, ink.rule),
    )
    val description = segments.joinToString(", ") { "${it.first} ${(it.second * 100).roundToInt()} por ciento" }
    Column(modifier.clearAndSetSemantics { contentDescription = "Etapas de sueño: $description" }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            var x = 0f
            segments.forEach { (_, share, color) ->
                val w = (share * size.width).toFloat()
                drawRect(color, Offset(x, 0f), Size(w, size.height))
                x += w
            }
        }
        Spacer(Modifier.height(8.dp))
        segments.forEach { (label, share, color) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { drawRect(color) }
                Spacer(Modifier.width(10.dp))
                Text(label, style = Type.body, color = ink.ink, modifier = Modifier.weight(1f))
                Text("${(share * 100).roundToInt()} %", style = Type.figure, color = ink.ink)
            }
        }
    }
}

@Composable
fun RowBar(
    label: String,
    value: Float,
    maxValue: Float,
    valueText: String,
    description: String,
    modifier: Modifier = Modifier,
    reference: Float? = null,
    emphasized: Boolean = false,
    over: Boolean = false,
) {
    val ink = LocalInk.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.figureSmall, color = if (emphasized) ink.ink else ink.inkSoft, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f).height(18.dp)) {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val barHeight = 9.dp.toPx()
                val w = if (maxValue > 0) size.width * (value.coerceIn(0f, maxValue) / maxValue) else 0f
                drawRect(if (over) ink.ink else ink.barMuted, Offset(0f, (size.height - barHeight) / 2f), Size(w, barHeight))
                if (reference != null && maxValue > 0) {
                    val rx = size.width * (reference / maxValue)
                    dashedLine(ink.signal, Offset(rx, 0f), Offset(rx, size.height))
                }
            }
        }
        Text(valueText, style = Type.figureSmall, color = if (over) ink.ink else ink.inkSoft, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}
