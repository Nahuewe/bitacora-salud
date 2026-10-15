package ar.nahuel.bitacora.domain

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {
    val locale: Locale = Locale.forLanguageTag("es-AR")
    private val integer: NumberFormat = NumberFormat.getIntegerInstance(locale)
    private val oneDecimal: NumberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)

    fun int(value: Long): String = synchronized(integer) { integer.format(value) }

    fun decimal(value: Double): String = synchronized(oneDecimal) { oneDecimal.format(value) }

    fun hours(minutes: Long): String = "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"

    fun hoursDecimal(minutes: Double): String = decimal(minutes / 60.0) + " h"

    fun shortDay(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.') + " " + date.dayOfMonth

    fun longDay(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, locale) + " " + date.dayOfMonth

    fun dayMonth(date: LocalDate): String = date.format(dayMonth).trimEnd('.')
}
