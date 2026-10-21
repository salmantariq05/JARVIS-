package com.jarvis.shared

import com.jarvis.shared.time.CallPlanner
import com.jarvis.shared.time.LogicalDay
import com.jarvis.shared.time.SpokenTime
import com.jarvis.shared.time.TimeParser
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeParserTest {
    // Wednesday 21 October 2026, 10 PM
    private val night = LocalDateTime(2026, 10, 21, 22, 0)
    private val afternoon = LocalDateTime(2026, 10, 21, 14, 0)

    private fun at(text: String, now: LocalDateTime) = TimeParser.parse(text, now)?.at

    @Test fun explicitAmPm() {
        assertEquals(LocalDateTime(2026, 10, 22, 7, 0), at("call me at 7 am", night))
        assertEquals(LocalDateTime(2026, 10, 21, 19, 30), at("remind me at 7:30 pm", afternoon))
        assertEquals(LocalDateTime(2026, 10, 22, 6, 15), at("wake me up at 6.15am", night))
    }

    @Test fun ambiguousHourPicksNextSensibleTime() {
        // At 2 PM, "at 5" means 5 PM today.
        assertEquals(LocalDateTime(2026, 10, 21, 17, 0), at("call me at 5", afternoon))
        // At 10 PM, "at 7" means 7 AM tomorrow.
        assertEquals(LocalDateTime(2026, 10, 22, 7, 0), at("call me at 7", night))
    }

    @Test fun twentyFourHourClock() {
        assertEquals(LocalDateTime(2026, 10, 21, 19, 45), at("alarm 19:45", afternoon))
    }

    @Test fun relativeTimes() {
        assertEquals(LocalDateTime(2026, 10, 21, 14, 10), at("remind me in 10 minutes", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 16, 0), at("call me in 2 hours", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 14, 30), at("in half an hour remind me", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 14, 15), at("15 minute baad call karna", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 23, 0), at("1 ghanta baad yaad dilana", night))
    }

    @Test fun tomorrowDefaults() {
        assertEquals(LocalDateTime(2026, 10, 22, 9, 0), at("tomorrow at 9", afternoon))
        assertEquals(LocalDateTime(2026, 10, 22, 15, 0), at("tomorrow at 3", afternoon))
        // 1 AM is still "Wednesday night" for Jarvis, so "tomorrow" is Thursday.
        assertEquals(LocalDateTime(2026, 10, 22, 8, 0), at("tomorrow at 8", LocalDateTime(2026, 10, 22, 1, 0)))
    }

    @Test fun romanUrdu() {
        assertEquals(LocalDateTime(2026, 10, 22, 7, 0), at("kal subah saat baje utha dena", afternoon))
        assertEquals(LocalDateTime(2026, 10, 22, 7, 30), at("kal saade saat baje", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 18, 45), at("shaam paune saat baje call karna", afternoon))
        assertEquals(LocalDateTime(2026, 10, 22, 0, 0), at("raat 12 baje", night))
        assertEquals(LocalDateTime(2026, 10, 21, 17, 15), at("sawa 5 baje", afternoon))
    }

    @Test fun romanPashto() {
        assertEquals(LocalDateTime(2026, 10, 22, 7, 0), at("sabaa sahar pa owa bajo ma bidar ka", afternoon))
        assertEquals(LocalDateTime(2026, 10, 21, 20, 0), at("pa ata bajo", afternoon))
    }

    @Test fun noonAndMidnight() {
        assertEquals(LocalDateTime(2026, 10, 22, 0, 0), at("call me at midnight", night))
        assertEquals(LocalDateTime(2026, 10, 22, 12, 0), at("remind me at noon", night))
    }

    @Test fun noTimeMeansNull() {
        assertNull(at("call me", night))
        assertNull(at("I studied for 2 hours", afternoon))
    }

    @Test fun nextOccurrence() {
        val t = LocalTime(23, 55)
        assertEquals(LocalDateTime(2026, 10, 21, 23, 55), CallPlanner.nextOccurrence(t, night))
        // Right when the call fires, the next one is tomorrow.
        assertEquals(LocalDateTime(2026, 10, 22, 23, 55), CallPlanner.nextOccurrence(t, LocalDateTime(2026, 10, 21, 23, 55)))
    }

    @Test fun logicalDayRollsAtFourAm() {
        assertEquals(LocalDate(2026, 10, 21), LogicalDay.of(LocalDateTime(2026, 10, 22, 3, 59)))
        assertEquals(LocalDate(2026, 10, 22), LogicalDay.of(LocalDateTime(2026, 10, 22, 4, 0)))
    }

    @Test fun spokenFormats() {
        assertEquals("11:55 PM", SpokenTime.time(LocalTime(23, 55)))
        assertEquals("12 AM", SpokenTime.time(LocalTime(0, 0)))
        assertEquals("7 AM tomorrow", SpokenTime.dateTime(LocalDateTime(2026, 10, 22, 7, 0), night))
        assertEquals("2 hours 15 minutes", SpokenTime.duration(135))
    }
}
