package com.jarvis.shared

import com.jarvis.shared.brain.ActionType
import com.jarvis.shared.brain.BrainContext
import com.jarvis.shared.brain.BrainKind
import com.jarvis.shared.brain.BrainRequest
import com.jarvis.shared.brain.BrainRouter
import com.jarvis.shared.brain.JarvisAction
import com.jarvis.shared.brain.ModelRouter
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.brain.Speaker
import com.jarvis.shared.brain.Turn
import com.jarvis.shared.data.DayOps
import com.jarvis.shared.data.JarvisRepository
import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.JarvisSettings
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.LogEntry
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.model.ScheduledCall
import com.jarvis.shared.time.CallPlanner
import com.jarvis.shared.time.JarvisClock
import com.jarvis.shared.time.LogicalDay
import com.jarvis.shared.time.SpokenTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/** The phone's alarm system, seen from shared code. Android implements it with AlarmManager. */
interface CallScheduler {
    fun schedule(call: ScheduledCall)
    fun cancel(callId: Int)
}

/** Fixed IDs for the recurring calls; one-off alarms start at 100. */
object CallIds {
    const val MORNING = 1
    const val NIGHT = 2
    const val TEST = 3
}

/**
 * One conversation: a tap on the HUD, a wake word, or one phone call from Jarvis.
 * [verifiedSpeaker] is true when the voice lock confirmed it's you; Phase 3
 * will require it before sending messages on your behalf.
 */
class ConversationSession(val occasion: Occasion, startedAt: LocalDateTime, val verifiedSpeaker: Boolean = false) {
    val logicalDate: LocalDate = LogicalDay.of(startedAt)
    internal val history = mutableListOf<Turn>()
    internal var fallbackAnnounced = false
}

/** What the UI needs after each exchange. */
data class AssistantTurn(
    val speech: String,
    val brain: BrainKind,
    val endConversation: Boolean,
    val performed: List<String>,
)

/**
 * The conductor. It gathers context, asks the brain, runs the actions the
 * brain chose, and remembers the conversation. Screens only call [open] and
 * [respond]; they never talk to brains or storage directly.
 */
class JarvisAssistant(
    private val repository: JarvisRepository,
    private val router: BrainRouter,
    private val scheduler: CallScheduler,
    private val clock: JarvisClock,
) {
    fun newSession(occasion: Occasion, verifiedSpeaker: Boolean = false) =
        ConversationSession(occasion, clock.now(), verifiedSpeaker)

    /** Jarvis's first words when you answer one of its calls. */
    suspend fun open(session: ConversationSession, label: String = ""): AssistantTurn {
        val now = clock.now()
        val sir = repository.settings().addressAs
        val speech = when (session.occasion) {
            Occasion.ALARM_CALL -> {
                val about = if (label.isNotBlank()) " You asked me to call about: $label." else ""
                "${SpokenTime.greeting(now)}, $sir. It's ${SpokenTime.time(now.time)}.$about Anything you need?"
            }
            Occasion.MORNING_PLAN -> "Good morning, $sir. It's ${SpokenTime.time(now.time)}. What's the plan for today?"
            Occasion.NIGHT_DEBRIEF -> return converse(session, "")
            Occasion.CHAT -> "Yes, $sir?"
        }
        session.history += Turn(Speaker.JARVIS, speech)
        return AssistantTurn(speech, BrainKind.OFFLINE, endConversation = false, performed = emptyList())
    }

    suspend fun respond(session: ConversationSession, userText: String): AssistantTurn = converse(session, userText.trim())

    private suspend fun converse(session: ConversationSession, userText: String): AssistantTurn {
        val now = clock.now()
        val settings = repository.settings()
        val day = repository.day(session.logicalDate)
        val request = BrainRequest(
            userText = userText,
            history = session.history.toList(),
            context = BrainContext(now, day, upcomingCalls(settings, now), settings),
            depth = ModelRouter.depthFor(userText, session.occasion),
            occasion = session.occasion,
        )
        val routed = router.reply(request)
        val outcomes = routed.reply.actions.map { execute(it, session, now) }

        val parts = mutableListOf<String>()
        val reason = routed.fallbackReason
        if (reason != null && !session.fallbackAnnounced) {
            parts += "Quick note, ${settings.addressAs}: I'm on my offline brain because $reason."
            session.fallbackAnnounced = true
        }
        parts += routed.reply.speech
        outcomes.mapNotNull { it.problem }.forEach { parts += it }
        val speech = parts.joinToString(" ")

        if (userText.isNotEmpty()) session.history += Turn(Speaker.USER, userText)
        session.history += Turn(Speaker.JARVIS, speech)
        if (session.occasion == Occasion.NIGHT_DEBRIEF && userText.isEmpty()) {
            repository.updateDay(session.logicalDate) { it.copy(debrief = speech) }
        }
        return AssistantTurn(speech, routed.kind, routed.reply.endConversation, outcomes.mapNotNull { it.summary })
    }

    private data class Outcome(val summary: String?, val problem: String? = null)

    private suspend fun execute(action: JarvisAction, session: ConversationSession, now: LocalDateTime): Outcome {
        val date = session.logicalDate
        return when (action.type) {
            ActionType.SCHEDULE_CALL -> {
                val time = action.time?.let(CallPlanner::parseClock)
                    ?: return Outcome(null, "I didn't catch a valid time for that call, so I haven't scheduled it.")
                val day = action.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val at = CallPlanner.resolve(time, day, now)
                if (at <= now) return Outcome(null, "That time has already passed, so I haven't scheduled it.")
                val call = repository.addCall(CallKind.ALARM, at, action.label.orEmpty())
                scheduler.schedule(call)
                Outcome("Call set for ${SpokenTime.dateTime(at, now)}")
            }
            ActionType.CANCEL_CALL -> {
                val time = action.time?.let(CallPlanner::parseClock)
                val removed = repository.removeCalls { it.kind == CallKind.ALARM && (time == null || it.at.time == time) }
                removed.forEach { scheduler.cancel(it.id) }
                Outcome("Cancelled ${removed.size} call(s)")
            }
            ActionType.LOG -> {
                val text = action.text ?: action.label ?: return Outcome(null)
                val entry = LogEntry(
                    time = LocalTime(now.hour, now.minute),
                    category = action.category ?: LogCategory.NOTE,
                    text = text,
                    subject = action.subject,
                    minutes = action.minutes,
                )
                repository.updateDay(date) { DayOps.addLog(it, entry) }
                Outcome("Logged: $text")
            }
            ActionType.SET_PLAN -> {
                val items = action.items.orEmpty()
                repository.updateDay(date) { DayOps.setPlan(it, items) }
                Outcome("Plan set: ${items.size} tasks")
            }
            ActionType.ADD_PLAN_ITEM -> {
                val title = action.text ?: return Outcome(null)
                repository.updateDay(date) { DayOps.addPlanItem(it, title) }
                Outcome("Added to plan: $title")
            }
            ActionType.UPDATE_PLAN_ITEM -> {
                val query = action.text ?: return Outcome(null)
                var matched = false
                repository.updateDay(date) { day ->
                    val (updated, item) = DayOps.updatePlanItem(day, query, action.status ?: PlanStatus.DONE, action.label)
                    matched = item != null
                    updated
                }
                if (matched) Outcome("Plan updated: $query") else Outcome(null, "I couldn't find $query in today's plan.")
            }
            ActionType.SET_DAILY_CALL -> {
                val time = action.time?.let(CallPlanner::parseClock)
                val night = action.label != "morning_plan"
                repository.updateSettings { s ->
                    if (night) {
                        s.copy(nightDebriefTime = time ?: s.nightDebriefTime, nightDebriefEnabled = action.enabled ?: s.nightDebriefEnabled)
                    } else {
                        s.copy(morningCallTime = time ?: s.morningCallTime, morningCallEnabled = action.enabled ?: s.morningCallEnabled)
                    }
                }
                syncDailyCalls()
                Outcome(if (night) "Night debrief updated" else "Morning call updated")
            }
        }
    }

    /** (Re)arms the recurring morning and night calls from settings. Safe to call any time. */
    suspend fun syncDailyCalls() {
        val settings = repository.settings()
        val now = clock.now()
        dailyCalls(settings, now).forEach(scheduler::schedule)
        if (!settings.morningCallEnabled) scheduler.cancel(CallIds.MORNING)
        if (!settings.nightDebriefEnabled) scheduler.cancel(CallIds.NIGHT)
    }

    /** After a reboot or time change: re-arm everything, drop calls that already passed. */
    suspend fun rescheduleAll() {
        syncDailyCalls()
        val now = clock.now()
        repository.removeCalls { it.at <= now }
        repository.calls().forEach(scheduler::schedule)
    }

    /** Called by the alarm receiver right after a call rings. */
    suspend fun onCallFired(callId: Int, kind: CallKind) {
        if (kind == CallKind.ALARM) repository.removeCalls { it.id == callId } else syncDailyCalls()
    }

    /** Schedules a test call a few seconds from now, so you can try the call screen. */
    fun scheduleTestCall(secondsFromNow: Int) {
        val now = clock.now()
        var total = now.hour * 3600 + now.minute * 60 + now.second + secondsFromNow
        var date = now.date
        if (total >= 86_400) {
            total -= 86_400
            date = date.plus(1, DateTimeUnit.DAY)
        }
        val at = LocalDateTime(date, LocalTime(total / 3600, (total % 3600) / 60, total % 60))
        scheduler.schedule(ScheduledCall(CallIds.TEST, CallKind.ALARM, at, "Test call"))
    }

    suspend fun upcomingCalls(): List<ScheduledCall> = upcomingCalls(repository.settings(), clock.now())

    private suspend fun upcomingCalls(settings: JarvisSettings, now: LocalDateTime): List<ScheduledCall> =
        (repository.calls().filter { it.at > now } + dailyCalls(settings, now)).sortedBy { it.at }

    private fun dailyCalls(settings: JarvisSettings, now: LocalDateTime): List<ScheduledCall> = buildList {
        if (settings.morningCallEnabled) {
            add(ScheduledCall(CallIds.MORNING, CallKind.MORNING_PLAN, CallPlanner.nextOccurrence(settings.morningCallTime, now), "Morning plan"))
        }
        if (settings.nightDebriefEnabled) {
            add(ScheduledCall(CallIds.NIGHT, CallKind.NIGHT_DEBRIEF, CallPlanner.nextOccurrence(settings.nightDebriefTime, now), "Night debrief"))
        }
    }
}
