package com.jarvis.shared.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

/** A time found inside a sentence, plus where it was so the rest can become a label. */
data class TimeMatch(val at: LocalDateTime, val spans: List<IntRange>)

/**
 * Turns spoken time phrases into a real date-time, offline.
 *
 * Understands English ("at 7:30 pm", "in 10 minutes", "tomorrow at 9"),
 * Roman Urdu ("kal subah saade saat baje", "10 minute baad") and
 * Roman Pashto ("sabaa pa owa bajo").
 */
object TimeParser {
    private enum class Meridiem { AM, PM }
    private enum class Period { MORNING, AFTERNOON, EVENING, NIGHT }

    /** A clock reading pulled out of the text, before we decide AM/PM and the day. */
    private data class ClockHit(val hour: Int, val minute: Int, val meridiem: Meridiem?, val range: IntRange)

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        // English
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        // Urdu
        "ek" to 1, "aik" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4,
        "panch" to 5, "paanch" to 5, "chay" to 6, "chhe" to 6, "chhay" to 6, "che" to 6,
        "saat" to 7, "sat" to 7, "aath" to 8, "ath" to 8, "nau" to 9, "das" to 10,
        "gyarah" to 11, "gyara" to 11, "giyara" to 11, "barah" to 12, "bara" to 12, "baara" to 12,
        // Pashto
        "yaw" to 1, "dwa" to 2, "dre" to 3, "dray" to 3, "salor" to 4, "pinza" to 5,
        "shpag" to 6, "shpug" to 6, "owa" to 7, "uwa" to 7, "ata" to 8, "atha" to 8,
        "naha" to 9, "nah" to 9, "las" to 10, "yawolas" to 11, "dwolas" to 12,
    )
    private val NUM = "(\\d{1,2}|" + NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private const val OCLOCK = "(?:baje|bajay|bajy|bje|baj|bajo|bajey|o'?clock|oclock)"
    private const val MINUTE_UNITS = "minutes?|mins?|minat|daqiqe"
    private const val HOUR_UNITS = "hours?|hrs?|ghante|ghanta|ghantay|gante|ganta|saata|saatoona"

    private val RELATIVE_EN = Regex("""\b(?:in|after)\s+(\d+|an?|half an?|one)\s*($MINUTE_UNITS|$HOUR_UNITS)\b""")
    private val RELATIVE_UR = Regex("""\b(\d+|aadha|adha)\s*($MINUTE_UNITS|$HOUR_UNITS)\s*(?:ke\s+)?(?:baad|bad|pas|wrosta|wrusta)\b""")
    private val WITH_MERIDIEM = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""")
    private val OCLOCK_PHRASE = Regex("""\b(?:(saade|sade|sarhe|sawa|paune|pone)\s+)?$NUM(?:[:.](\d{2}))?\s*$OCLOCK\b""")
    private val AT_PHRASE = Regex("""\b(?:at|by|pa)\s+$NUM(?:[:.](\d{2}))?\b""")
    private val COLON_TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val NOON = Regex("""\b(noon|midday|midnight)\b""")

    private val PERIODS: List<Pair<Period, Regex>> = listOf(
        Period.MORNING to Regex("""\b(morning|subah|subha|sobah|sahar|sehar|savere|sawere|fajr|gahiz)\b"""),
        Period.AFTERNOON to Regex("""\b(afternoon|dopahar|dopehar|zuhr|maspakhin)\b"""),
        Period.EVENING to Regex("""\b(evening|shaam|sham|maghrib|maskhotan|makham|maxam)\b"""),
        Period.NIGHT to Regex("""\b(tonight|night|raat|rat|shpa|shpe)\b"""),
    )
    private val DAY_AFTER = Regex("""\b(day after tomorrow|parson|parso)\b""")
    private val TOMORROW = Regex("""\b(tomorrow|tmrw|kal|sabaa|saba)\b""")
    private val TODAY = Regex("""\b(today|aaj|nan)\b""")

    fun parse(text: String, now: LocalDateTime): TimeMatch? {
        val s = text.lowercase()
        relative(s, now)?.let { return it }

        val clock = findClock(s) ?: return null
        if (clock.hour !in 0..23 || clock.minute !in 0..59) return null
        val spans = mutableListOf(clock.range)

        val period = PERIODS.firstNotNullOfOrNull { (p, regex) -> regex.find(s)?.let { spans += it.range; p } }
        val dayOffset = DAY_AFTER.find(s)?.let { spans += it.range; 2 }
            ?: TOMORROW.find(s)?.let { spans += it.range; 1 }
        TODAY.find(s)?.let { spans += it.range }

        val at = resolve(clock, period, dayOffset, now)
        return TimeMatch(at, spans.sortedBy { it.first })
    }

    private fun findClock(s: String): ClockHit? {
        WITH_MERIDIEM.find(s)?.let { m ->
            val meridiem = if (m.groupValues[3] == "am") Meridiem.AM else Meridiem.PM
            return ClockHit(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), meridiem, m.range)
        }
        OCLOCK_PHRASE.find(s)?.let { m ->
            val base = number(m.groupValues[2])
            if (base != null) {
                var hour = base
                var minute = m.groupValues[3].ifEmpty { "0" }.toInt()
                when (m.groupValues[1]) {
                    "saade", "sade", "sarhe" -> minute = 30 // saade saat = 7:30
                    "sawa" -> minute = 15 // sawa saat = 7:15
                    "paune", "pone" -> { // paune saat = 6:45
                        minute = 45
                        hour = if (base == 1) 12 else base - 1
                    }
                }
                return ClockHit(hour, minute, null, m.range)
            }
        }
        AT_PHRASE.find(s)?.let { m ->
            val hour = number(m.groupValues[1])
            if (hour != null) return ClockHit(hour, m.groupValues[2].ifEmpty { "0" }.toInt(), null, m.range)
        }
        COLON_TIME.find(s)?.let { m ->
            return ClockHit(m.groupValues[1].toInt(), m.groupValues[2].toInt(), null, m.range)
        }
        NOON.find(s)?.let { m ->
            return if (m.value == "midnight") ClockHit(0, 0, Meridiem.AM, m.range) else ClockHit(12, 0, Meridiem.PM, m.range)
        }
        return null
    }

    private fun relative(s: String, now: LocalDateTime): TimeMatch? {
        val m = RELATIVE_EN.find(s) ?: RELATIVE_UR.find(s) ?: return null
        val amountText = m.groupValues[1]
        val isHours = Regex("^($HOUR_UNITS)$").matches(m.groupValues[2])
        val minutes = when {
            amountText.startsWith("half") || amountText == "aadha" || amountText == "adha" ->
                if (isHours) 30 else return null
            amountText == "a" || amountText == "an" || amountText == "one" -> if (isHours) 60 else 1
            else -> amountText.toInt() * (if (isHours) 60 else 1)
        }
        if (minutes <= 0 || minutes > 7 * 24 * 60) return null
        return TimeMatch(now.plusMinutes(minutes), listOf(m.range))
    }

    private fun resolve(clock: ClockHit, period: Period?, dayOffset: Int?, now: LocalDateTime): LocalDateTime {
        val hour = clock.hour
        val minute = clock.minute
        val ambiguous = clock.meridiem == null && period == null && hour in 1..12
        val h = when {
            hour > 12 -> hour // 24-hour clock like 19:30: nothing to guess
            clock.meridiem == Meridiem.PM -> if (hour < 12) hour + 12 else hour
            clock.meridiem == Meridiem.AM -> if (hour == 12) 0 else hour
            period == Period.AFTERNOON -> if (hour in 1..6) hour + 12 else hour
            period == Period.EVENING -> if (hour in 1..11) hour + 12 else hour
            period == Period.NIGHT -> when (hour) { 12 -> 0; in 6..11 -> hour + 12; else -> hour }
            else -> hour
        }

        if (dayOffset != null) {
            val date = LogicalDay.of(now).plus(dayOffset, DateTimeUnit.DAY)
            // "tomorrow at 7" almost always means 7 AM; "tomorrow at 3" means 3 PM.
            return date.atTime(LocalTime(if (ambiguous) defaultHour(hour) else h, minute))
        }
        val candidates = if (ambiguous) listOf(hour % 12, hour % 12 + 12) else listOf(h)
        val laterToday = candidates.map { now.date.atTime(LocalTime(it, minute)) }.filter { it > now }.minOrNull()
        if (laterToday != null) return laterToday
        return now.date.plus(1, DateTimeUnit.DAY).atTime(LocalTime(if (ambiguous) defaultHour(hour) else h, minute))
    }

    /** 7-11 -> morning, 12 -> noon, 1-6 -> afternoon/evening. */
    private fun defaultHour(hour: Int): Int = when (hour) {
        in 7..11 -> hour
        12 -> 12
        else -> hour + 12
    }

    private fun number(token: String): Int? = token.toIntOrNull() ?: NUMBER_WORDS[token]

    private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime {
        var total = hour * 60 + minute + minutes
        var date = this.date
        while (total >= 24 * 60) {
            total -= 24 * 60
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return date.atTime(LocalTime(total / 60, total % 60, second))
    }
}
