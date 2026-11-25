package com.vitrinapos.data

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

object Time {
    private val tz get() = TimeZone.currentSystemDefault()
    private val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val monthsLong = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    private val weekdays = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

    fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun local(ms: Long): LocalDateTime = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)

    fun startOfDay(ms: Long = now()): Long = local(ms).date.atStartOfDayIn(tz).toEpochMilliseconds()

    fun startOfDay(date: LocalDate): Long = date.atStartOfDayIn(tz).toEpochMilliseconds()

    fun addDays(dayStart: Long, days: Int): Long =
        local(dayStart).date.plus(days, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds()

    fun startOfMonth(ms: Long = now()): Long {
        val d = local(ms).date
        return LocalDate(d.year, d.monthNumber, 1).atStartOfDayIn(tz).toEpochMilliseconds()
    }

    /** 25/11/2026 */
    fun date(ms: Long): String {
        val d = local(ms)
        return "${d.dayOfMonth.toString().padStart(2, '0')}/${d.monthNumber.toString().padStart(2, '0')}/${d.year}"
    }

    /** 10:12 a. m. */
    fun time(ms: Long): String {
        val d = local(ms)
        val h12 = when (val h = d.hour % 12) { 0 -> 12; else -> h }
        val ampm = if (d.hour < 12) "a. m." else "p. m."
        return "$h12:${d.minute.toString().padStart(2, '0')} $ampm"
    }

    fun dateTime(ms: Long): String = "${date(ms)} ${time(ms)}"

    /** 25 nov */
    fun shortDate(ms: Long): String {
        val d = local(ms)
        return "${d.dayOfMonth} ${months[d.monthNumber - 1]}"
    }

    /** 25 de noviembre de 2026 */
    fun longDate(ms: Long): String {
        val d = local(ms)
        return "${d.dayOfMonth} de ${monthsLong[d.monthNumber - 1]} de ${d.year}"
    }

    fun weekday(ms: Long): String = weekdays[local(ms).dayOfWeek.ordinal]

    /** "Hoy", "Ayer" o la fecha corta. */
    fun relativeDay(ms: Long): String {
        val today = startOfDay()
        return when {
            ms >= today -> "Hoy"
            ms >= addDays(today, -1) -> "Ayer"
            else -> shortDate(ms)
        }
    }

    fun greeting(): String = when (local(now()).hour) {
        in 5..11 -> "Buenos días"
        in 12..18 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}
