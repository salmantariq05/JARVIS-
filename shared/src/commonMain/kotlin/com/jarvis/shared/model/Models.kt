package com.jarvis.shared.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** What kind of thing you told Jarvis about. */
@Serializable
enum class LogCategory { TASK, STUDY, PROBLEM, NOTE, WIN }

/** One thing that happened today ("studied DSA for 2 hours"). */
@Serializable
data class LogEntry(
    val time: LocalTime,
    val category: LogCategory,
    val text: String,
    val subject: String? = null,
    val minutes: Int? = null,
)

@Serializable
enum class PlanStatus { PENDING, DONE, PARTIAL, SKIPPED }

/** One task from the morning plan. */
@Serializable
data class PlanItem(
    val title: String,
    val status: PlanStatus = PlanStatus.PENDING,
    val note: String? = null,
)

/** Everything Jarvis knows about one day. */
@Serializable
data class DayRecord(
    val date: LocalDate,
    val plan: List<PlanItem> = emptyList(),
    val entries: List<LogEntry> = emptyList(),
    val debrief: String? = null,
) {
    val doneCount: Int get() = plan.count { it.status == PlanStatus.DONE }
    val studyMinutes: Int
        get() = entries.filter { it.category == LogCategory.STUDY }.sumOf { it.minutes ?: 0 }
}

@Serializable
enum class CallKind { ALARM, MORNING_PLAN, NIGHT_DEBRIEF }

/** A moment when Jarvis will ring your phone. */
@Serializable
data class ScheduledCall(
    val id: Int,
    val kind: CallKind,
    val at: LocalDateTime,
    val label: String = "",
)

/** Which brain answers you. AUTO = Claude when possible, offline otherwise. */
@Serializable
enum class BrainMode { AUTO, CLAUDE, OFFLINE }

@Serializable
data class JarvisSettings(
    val brainMode: BrainMode = BrainMode.AUTO,
    val addressAs: String = "sir",
    val nightDebriefEnabled: Boolean = true,
    val nightDebriefTime: LocalTime = LocalTime(23, 55),
    val morningCallEnabled: Boolean = true,
    val morningCallTime: LocalTime = LocalTime(8, 0),
    /** BCP-47 tag for speech recognition. en-IN copes best with Roman Urdu/Pashto mixed in. */
    val speechLanguage: String = "en-IN",
    val voiceName: String? = null,
)
