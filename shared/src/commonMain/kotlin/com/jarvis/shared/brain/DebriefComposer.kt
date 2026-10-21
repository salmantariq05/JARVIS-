package com.jarvis.shared.brain

import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.time.SpokenTime
import kotlinx.datetime.LocalDateTime

/** Builds the night debrief from your log with plain templates: no AI needed, never invents facts. */
object DebriefComposer {
    fun compose(day: DayRecord, addressAs: String, now: LocalDateTime): String {
        val dayName = SpokenTime.dayName(day.date.dayOfWeek)
        val parts = mutableListOf<String>()
        parts += if (now.hour >= 23 || now.hour < 4) {
            "It's late, $addressAs. Here's your debrief for $dayName."
        } else {
            "${SpokenTime.greeting(now)}, $addressAs. Here's your debrief for $dayName."
        }

        if (day.plan.isEmpty() && day.entries.isEmpty()) {
            parts += "I have nothing logged for today. Tell me what you did and I'll record it, then we can plan tomorrow."
            return parts.joinToString(" ")
        }

        if (day.plan.isEmpty()) {
            parts += "You didn't set a plan today."
        } else {
            val done = day.plan.filter { it.status == PlanStatus.DONE }.map { it.title }
            val partial = day.plan.filter { it.status == PlanStatus.PARTIAL }.map { it.title }
            val pending = day.plan.filter { it.status == PlanStatus.PENDING }.map { it.title }
            val verdict = when {
                done.size == day.plan.size -> "a clean sweep. Well done"
                done.size * 2 >= day.plan.size -> "solid progress"
                else -> "a tough day"
            }
            parts += "You planned ${count(day.plan.size, "task")} and completed ${done.size}: $verdict."
            if (done.isNotEmpty()) parts += "Done: ${joinSpoken(done)}."
            if (partial.isNotEmpty()) parts += "Partly done: ${joinSpoken(partial)}."
            if (pending.isNotEmpty()) parts += "Still open: ${joinSpoken(pending)}."
        }

        val study = day.entries.filter { it.category == LogCategory.STUDY }
        if (study.isNotEmpty()) {
            val bySubject = study.groupBy { it.subject ?: it.text }
            val described = bySubject.map { (subject, entries) ->
                val minutes = entries.sumOf { it.minutes ?: 0 }
                if (minutes > 0) "$subject for ${SpokenTime.duration(minutes)}" else subject
            }
            parts += "You studied ${joinSpoken(described)}."
        }

        val wins = day.entries.filter { it.category == LogCategory.WIN || it.category == LogCategory.TASK }.map { it.text }
        if (wins.isNotEmpty()) parts += "Wins worth noting: ${joinSpoken(wins)}."

        val problems = day.entries.filter { it.category == LogCategory.PROBLEM }.map { it.text }
        if (problems.isNotEmpty()) {
            parts += "You flagged ${count(problems.size, "problem")}: ${joinSpoken(problems)}."
        }

        val firstOpen = day.plan.firstOrNull { it.status == PlanStatus.PENDING || it.status == PlanStatus.PARTIAL }
        if (firstOpen != null) parts += "I suggest starting tomorrow with ${firstOpen.title}."

        parts += if (problems.isNotEmpty()) {
            "Shall we clear up that doubt before you sleep?"
        } else {
            "Any doubts you'd like to clear before you sleep?"
        }
        return parts.joinToString(" ")
    }

    fun joinSpoken(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
