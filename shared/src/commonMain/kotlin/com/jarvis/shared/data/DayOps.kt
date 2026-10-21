package com.jarvis.shared.data

import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.LogEntry
import com.jarvis.shared.model.PlanItem
import com.jarvis.shared.model.PlanStatus

/**
 * Pure functions that change a [DayRecord]. "Pure" means: same input, same
 * output, no files or network, which makes them trivial to unit-test.
 */
object DayOps {
    fun addLog(day: DayRecord, entry: LogEntry): DayRecord = day.copy(entries = day.entries + entry)

    fun setPlan(day: DayRecord, titles: List<String>): DayRecord =
        day.copy(plan = titles.map { it.trim() }.filter { it.isNotEmpty() }.map { PlanItem(it) })

    fun addPlanItem(day: DayRecord, title: String): DayRecord =
        if (title.isBlank()) day else day.copy(plan = day.plan + PlanItem(title.trim()))

    /** Returns the updated day and the matched item, or null when nothing in the plan matches. */
    fun updatePlanItem(day: DayRecord, query: String, status: PlanStatus, note: String? = null): Pair<DayRecord, PlanItem?> {
        val index = PlanMatcher.bestMatchIndex(day.plan.map { it.title }, query) ?: return day to null
        val updated = day.plan[index].copy(status = status, note = note ?: day.plan[index].note)
        val plan = day.plan.toMutableList().also { it[index] = updated }
        return day.copy(plan = plan) to updated
    }
}

/** Finds which plan item the user meant ("mark the OS one done" -> "Operating Systems assignment"). */
object PlanMatcher {
    private val STOP_WORDS = setOf(
        "the", "a", "an", "my", "of", "for", "to", "and", "is", "it", "with", "on", "in", "at", "one",
        "task", "item", "work", "done", "finished", "completed", "mark", "as",
        "ka", "ki", "ke", "ko", "se", "aur", "hai", "da", "de", "wala", "wali",
    )
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "last" to -1,
    )
    private val NUMBERED = Regex("""(?:task|item|number|no\.?|#)\s*(\d+)""")

    fun bestMatchIndex(titles: List<String>, query: String): Int? {
        if (titles.isEmpty()) return null
        val lower = query.lowercase()
        NUMBERED.find(lower)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1..titles.size) return n - 1
        }
        for ((word, n) in ORDINALS) {
            if (Regex("""\b$word\b""").containsMatchIn(lower)) {
                return if (n == -1) titles.lastIndex else (n - 1).takeIf { it < titles.size }
            }
        }
        val queryTokens = tokens(lower)
        if (queryTokens.isEmpty()) return null
        var best = -1
        var bestScore = 0.0
        titles.forEachIndexed { i, title ->
            val titleLower = title.lowercase()
            val titleTokens = tokens(titleLower)
            val shared = queryTokens.count { q -> titleTokens.any { t -> t == q || t.startsWith(q) || q.startsWith(t) } }
            var score = if (titleTokens.isEmpty()) 0.0 else shared.toDouble() / minOf(queryTokens.size, titleTokens.size)
            if (titleLower in lower || lower in titleLower) score = maxOf(score, 1.0)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best.takeIf { bestScore >= 0.5 }
    }

    private fun tokens(text: String): List<String> =
        text.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 && it !in STOP_WORDS }
}
