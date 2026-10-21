package com.jarvis.shared.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** Injected "now", so tests can pretend it's 11:55 PM on any day. */
fun interface JarvisClock {
    fun now(): LocalDateTime
}

/**
 * Night owls study past midnight. Jarvis treats 00:00-03:59 as part of the
 * previous day, so a 1 AM log still counts toward "today's" debrief.
 */
object LogicalDay {
    const val DAY_STARTS_AT_HOUR = 4

    fun of(now: LocalDateTime): LocalDate =
        if (now.hour < DAY_STARTS_AT_HOUR) now.date.minus(1, DateTimeUnit.DAY) else now.date
}

object CallPlanner {
    /** The next moment (strictly after [now]) that the clock shows [time]. */
    fun nextOccurrence(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val today = now.date.atTime(time)
        return if (today > now) today else now.date.plus(1, DateTimeUnit.DAY).atTime(time)
    }

    /** A time with an optional date: no date means "the next time it's that o'clock". */
    fun resolve(time: LocalTime, date: LocalDate?, now: LocalDateTime): LocalDateTime =
        date?.atTime(time) ?: nextOccurrence(time, now)

    /** Accepts "7:05", "07:05" and "7". Returns null for anything else. */
    fun parseClock(text: String): LocalTime? {
        val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        return if (h in 0..23 && min in 0..59) LocalTime(h, min) else null
    }
}

/** Formats times the way a person would say them out loud. */
object SpokenTime {
    fun time(t: LocalTime): String {
        val h12 = when (val h = t.hour % 12) { 0 -> 12; else -> h }
        val suffix = if (t.hour < 12) "AM" else "PM"
        val minutes = if (t.minute == 0) "" else ":" + t.minute.toString().padStart(2, '0')
        return "$h12$minutes $suffix"
    }

    fun dateTime(at: LocalDateTime, now: LocalDateTime): String {
        val days = now.date.daysUntil(at.date)
        val day = when {
            days == 0 -> "today"
            days == 1 -> "tomorrow"
            days in 2..6 -> "on " + dayName(at.date.dayOfWeek)
            else -> "on ${at.date.day} ${monthName(at.date.month.ordinal)}"
        }
        return "${time(at.time)} $day"
    }

    fun dayName(d: DayOfWeek): String = d.name.lowercase().replaceFirstChar { it.uppercase() }

    fun monthName(zeroBasedMonth: Int): String = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )[zeroBasedMonth]

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hours = when (h) { 0 -> ""; 1 -> "1 hour"; else -> "$h hours" }
        val mins = when (m) { 0 -> ""; 1 -> "1 minute"; else -> "$m minutes" }
        return listOf(hours, mins).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "0 minutes" }
    }

    fun greeting(now: LocalDateTime): String = when (now.hour) {
        in 4..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}
