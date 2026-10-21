package com.jarvis.shared.brain

import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.time.CallPlanner
import com.jarvis.shared.time.TimeParser
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** What the user wants, understood without any AI (works offline, instantly, for free). */
sealed interface Intent {
    data class ScheduleCall(val at: LocalDateTime, val label: String) : Intent
    data class Log(val category: LogCategory, val text: String, val subject: String?, val minutes: Int?) : Intent
    data class SetPlan(val items: List<String>) : Intent
    data class AddPlanItem(val title: String) : Intent
    data class Completed(val what: String, val status: PlanStatus) : Intent
    data class SetDailyCall(val kind: CallKind, val time: LocalTime?, val enabled: Boolean?) : Intent
    data object MissingTime : Intent
    data object CancelCalls : Intent
    data object ListPlan : Intent
    data object Debrief : Intent
    data object AskTime : Intent
    data object Help : Intent
    data object Greeting : Intent
    data object Goodbye : Intent
}

/**
 * Rule-based understanding for the commands you use every day.
 * Order matters: more specific rules run first.
 */
/** Every pattern ignores case, so "DSA" stays "DSA" in your plan instead of becoming "dsa". */
private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

class IntentParser {
    fun parse(raw: String, now: LocalDateTime): Intent? {
        val text = normalize(raw)
        if (text.isEmpty()) return null

        if (GOODBYE_EXACT.matches(text) || GOODBYE_ANY.containsMatchIn(text)) return Intent.Goodbye
        if (GREETING.matches(text)) return Intent.Greeting
        dailyCall(text)?.let { return it }
        if (DEBRIEF.containsMatchIn(text)) return Intent.Debrief
        if (CANCEL.containsMatchIn(text)) return Intent.CancelCalls

        if (CALL_TRIGGER.containsMatchIn(text)) {
            val match = TimeParser.parse(text, now) ?: return Intent.MissingTime
            return Intent.ScheduleCall(match.at, label(text, match.spans))
        }

        LIST_PLAN.find(text)?.let { return Intent.ListPlan }
        SET_PLAN.find(text)?.let { m -> return Intent.SetPlan(splitItems(m.groupValues.drop(1).first { it.isNotEmpty() })) }
        ADD_PLAN.find(text)?.let { m -> return Intent.AddPlanItem(m.groupValues[1].trim()) }
        completed(text)?.let { return it }
        log(text)?.let { return it }

        if (ASK_TIME.containsMatchIn(text)) return Intent.AskTime
        if (HELP.containsMatchIn(text)) return Intent.Help
        return null
    }

    private fun dailyCall(text: String): Intent? {
        val kind = when {
            NIGHT_CALL.containsMatchIn(text) -> CallKind.NIGHT_DEBRIEF
            MORNING_CALL.containsMatchIn(text) -> CallKind.MORNING_PLAN
            else -> return null
        }
        val enabled = when {
            rx("""\b(turn off|disable|stop|switch off|band)\b""").containsMatchIn(text) -> false
            rx("""\b(turn on|enable|switch on)\b""").containsMatchIn(text) -> true
            else -> null
        }
        val time = rx("""\b(?:to|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""").find(text)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            when (m.groupValues[3].lowercase()) {
                "pm" -> if (h < 12) h += 12
                "am" -> if (h == 12) h = 0
                else -> if (kind == CallKind.NIGHT_DEBRIEF && h in 8..11) h += 12
            }
            CallPlanner.parseClock("$h:$min")
        }
        if (time == null && enabled == null) return null
        return Intent.SetDailyCall(kind, time, enabled)
    }

    private fun completed(text: String): Intent? {
        COMPLETED_PATTERNS.forEach { (regex, status) ->
            regex.find(text)?.let { m -> return Intent.Completed(m.groupValues[1].trim(), status) }
        }
        return null
    }

    private fun log(text: String): Intent? {
        val body = EXPLICIT_LOG.find(text)?.groupValues?.get(1)
            ?: IMPLICIT_LOG.find(text)?.value
            ?: return null
        val category = when {
            PROBLEM_WORDS.containsMatchIn(body) -> LogCategory.PROBLEM
            STUDY_WORDS.containsMatchIn(body) -> LogCategory.STUDY
            WIN_WORDS.containsMatchIn(body) -> LogCategory.WIN
            else -> LogCategory.NOTE
        }
        val minutes = duration(body)
        val subject = SUBJECT.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Intent.Log(category, body.trim().replaceFirstChar { it.uppercase() }, subject, minutes)
    }

    /** "for 2 hours" -> 120, "for 45 minutes" -> 45, "1.5 ghante" -> 90. */
    private fun duration(text: String): Int? {
        val m = rx("""(\d+(?:\.\d+)?|an?|half an?)\s*(hours?|hrs?|h|ghante|ghanta|minutes?|mins?)\b""").find(text) ?: return null
        val amount = when (val a = m.groupValues[1].lowercase()) {
            "a", "an" -> 1.0
            "half a", "half an" -> 0.5
            else -> a.toDouble()
        }
        val isMinutes = m.groupValues[2].lowercase().startsWith("min")
        return (if (isMinutes) amount else amount * 60).toInt().takeIf { it > 0 }
    }

    private fun label(text: String, spans: List<IntRange>): String {
        val sb = StringBuilder(text)
        spans.sortedByDescending { it.first }.forEach { sb.setRange(it.first, it.last + 1, " ") }
        return sb.toString()
            .replace(CALL_TRIGGER, " ")
            .replace(rx("""\b(please|jarvis|me|mujhe|ma|to|for|about|that|and|at|on|by|ko|pa|kar|karna|dena|do)\b"""), " ")
            .replace(rx("""\s+"""), " ")
            .trim(' ', ',', '.')
    }

    companion object {
        private val WAKE = rx("""^((hey|hi|ok|okay|yo)\s+)?jarvis[,!.]?\s*""")

        fun normalize(raw: String): String = raw
            .replace('’', '\'')
            .replace(rx("""\ba\.m\.?"""), "am")
            .replace(rx("""\bp\.m\.?"""), "pm")
            .trim()
            .replace(WAKE, "")
            .trim(' ', '.', '!', '?', ',')

        /** "DSA, OS and the physics lab" -> [DSA, OS, the physics lab] */
        fun splitItems(text: String): List<String> = text
            .split(rx("""\s*(?:,|;|\n|\band then\b|\bthen\b|\band\b|\baur\b|\bphir\b|\bao\b)\s*"""))
            .map { it.trim(' ', '.', ':') }
            .filter { it.isNotEmpty() }

        private val GOODBYE_EXACT = rx(
            """^(no|nope|nothing|nah|thanks|thank you|thankyou|shukriya|shukria|manana|that's all|that is all|that's it|stop|ok bye|okay bye|done)( jarvis)?$""",
        )
        private val GOODBYE_ANY = rx("""\b(bye|goodbye|good night|goodnight|khuda hafiz|allah hafiz|end (the )?call|hang up|shab ba khair|shpa de pa khair)\b""")
        private val GREETING = rx(
            """^(hi|hello|hey|yo|sup|what's up|salam|salaam|assalam ?o? ?alaikum|assalamualaikum|as-salamu alaykum|good (morning|afternoon|evening)|staray mashay|sta sanga ye)$""",
        )
        private val DEBRIEF = rx(
            """\b(debrief|summari[sz]e (my |the )?day|summary of (my |the )?day|day'?s summary|how was my day|what did i do today|aaj ka summary|aaj kya kiya)\b""",
        )
        private val NIGHT_CALL = rx("""\b(night|nightly|debrief)\b.*\bcall\b|\bdebrief\b.*\b(to|at)\b|\bcall\b.*\b(night|debrief)\b""")
        private val MORNING_CALL = rx("""\bmorning\b.*\bcall\b|\bcall\b.*\bmorning\b""")
        private val CANCEL = rx("""\b(cancel|delete|remove)\b.*\b(alarms?|calls?|reminders?|wake)\b""")
        private val CALL_TRIGGER = rx(
            """\b(call me|wake me( up)?|remind me|reminder|set (an |a )?alarm|alarm|ring me|utha dena|utha do|uthana|jaga dena|jaga do|jagana|call karna|call kar dena|call kardena|yaad dila(na|do|dena)?|ma bidar ka|bidar)\b""",
        )
        private val LIST_PLAN = rx("""\b(what'?s|what is) (my|the|today'?s) plan\b|\b(what'?s|what is) left\b|\bremaining tasks\b|\bplan kya hai\b""")
        private val SET_PLAN = rx("""^(?:my |today'?s )?plan (?:for today )?(?:is|:|hai)\s*:?\s*(.+)$|^today i (?:will|want to|need to|have to|am going to)\s+(.+)$|^aaj (?:ka plan|mujhe)\s+(.+)$""")
        private val ADD_PLAN = rx("""^add\s+(.+?)\s+to\s+(?:my |the |today'?s )?(?:plan|list|tasks)$""")

        private val COMPLETED_PATTERNS: List<Pair<Regex, PlanStatus>> = listOf(
            rx("""^mark\s+(.+?)\s+(?:as\s+)?(?:partly|partially|half)\s+done$""") to PlanStatus.PARTIAL,
            rx("""^mark\s+(.+?)\s+(?:as\s+)?(?:done|complete|completed|finished)$""") to PlanStatus.DONE,
            rx("""^(?:i )?(?:skipped|skip)\s+(.+)$""") to PlanStatus.SKIPPED,
            rx("""^i (?:partly|partially|half) (?:did|finished|completed)\s+(.+)$""") to PlanStatus.PARTIAL,
            rx("""^i (?:finished|completed|did|am done with|have finished|have completed)\s+(.+)$""") to PlanStatus.DONE,
            rx("""^(.+?)\s+(?:is done|is complete|is finished|done hai|ho gaya|ho gayi|khatam)$""") to PlanStatus.DONE,
        )

        private val EXPLICIT_LOG = rx("""^(?:log|note|log this|note this|add to (?:my )?log|write down|remember that)\s*[:,-]?\s+(.+)$""")
        private val IMPLICIT_LOG = rx(
            """^i (?:studied|learned|learnt|revised|practiced|practised|read|worked on|solved|am stuck|got stuck|don't understand|didn't understand|can't understand|watched|wrote|built|coded)\b.*$""",
        )
        private val STUDY_WORDS = rx("""\b(stud(y|ied)|learn(ed|t)?|revis(e|ed)|practi[cs]ed?|read|lecture|course|chapter|parha|seekha|zda)\b""")
        private val PROBLEM_WORDS = rx(
            """\b(stuck|confus\w*|problem|issue|bug|error|doubt|don't understand|didn't understand|can't understand|samajh nahi|samjh nahi|difficult|mushkil|hard time)\b""",
        )
        private val WIN_WORDS = rx("""\b(finished|completed|solved|passed|won|built|shipped|submitted|got)\b""")
        private val SUBJECT = rx(
            """\b(?:studied|learned|learnt|revised|practiced|practised|read|worked on|stuck on|confused about)\s+(.+?)(?:\s+for\b|\s+from\b|\s+with\b|\s+in\b|\s+today\b|$)""",
        )
        private val ASK_TIME = rx("""\b(what time|what's the time|what is the time|time kya|kitne baje|time kitna|what day|what's the date|what is the date|aaj kya date|tso baje|tsu baje)\b""")
        private val HELP = rx("""\b(what can you do|help|commands|how do i use you)\b""")
    }
}
