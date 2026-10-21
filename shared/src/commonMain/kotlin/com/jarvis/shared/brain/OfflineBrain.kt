package com.jarvis.shared.brain

import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.time.SpokenTime
import com.jarvis.shared.time.TimeParser

/**
 * The brain that works with no internet and costs nothing.
 * Commands are understood by [IntentParser]. Free-form questions go to an
 * on-device language model when one is installed.
 */
class OfflineBrain(
    private val localLlm: LocalLlm? = null,
    private val parser: IntentParser = IntentParser(),
) : Brain {

    override suspend fun reply(request: BrainRequest): BrainReply {
        val ctx = request.context
        val sir = ctx.addressAs
        val text = request.userText.trim()

        if (text.isEmpty()) {
            return if (request.occasion == Occasion.NIGHT_DEBRIEF) {
                BrainReply(DebriefComposer.compose(ctx.day, sir, ctx.now))
            } else {
                BrainReply("Yes, $sir?")
            }
        }

        val intent = parser.parse(text, ctx.now)
            ?: followUpTime(request)
            ?: morningPlan(request)
            ?: return freeform(request)

        return when (intent) {
            is Intent.ScheduleCall -> {
                val label = intent.label.ifBlank { "" }
                val about = if (label.isNotEmpty()) " about \"$label\"" else ""
                BrainReply(
                    "Done. I'll call you at ${SpokenTime.dateTime(intent.at, ctx.now)}$about.",
                    listOf(
                        JarvisAction(
                            ActionType.SCHEDULE_CALL,
                            time = intent.at.time.let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" },
                            date = intent.at.date.toString(),
                            label = label,
                        ),
                    ),
                )
            }
            Intent.MissingTime -> BrainReply("Certainly, $sir. At what time should I call you?")
            Intent.CancelCalls -> BrainReply(
                "Cancelled your one-off calls, $sir. The daily calls stay as they are.",
                listOf(JarvisAction(ActionType.CANCEL_CALL)),
            )
            is Intent.SetDailyCall -> dailyCall(intent, sir)
            is Intent.Log -> logReply(intent, sir)
            is Intent.SetPlan -> BrainReply(
                "Got it. ${intent.items.size} on today's plan: ${DebriefComposer.joinSpoken(intent.items)}. I'll check in on them tonight.",
                listOf(JarvisAction(ActionType.SET_PLAN, items = intent.items)),
            )
            is Intent.AddPlanItem -> BrainReply(
                "Added ${intent.title} to today's plan.",
                listOf(JarvisAction(ActionType.ADD_PLAN_ITEM, text = intent.title)),
            )
            is Intent.Completed -> {
                val praise = when (intent.status) {
                    PlanStatus.DONE -> "Excellent work, $sir. Marked ${intent.what} as done."
                    PlanStatus.PARTIAL -> "Noted: ${intent.what} is partly done. We'll finish it."
                    PlanStatus.SKIPPED -> "Understood, ${intent.what} is skipped for today."
                    PlanStatus.PENDING -> "Moved ${intent.what} back to pending."
                }
                val actions = mutableListOf(JarvisAction(ActionType.UPDATE_PLAN_ITEM, text = intent.what, status = intent.status))
                if (intent.status == PlanStatus.DONE) {
                    actions += JarvisAction(ActionType.LOG, category = LogCategory.WIN, text = "Finished ${intent.what}")
                }
                BrainReply(praise, actions)
            }
            Intent.ListPlan -> BrainReply(planSummary(request))
            Intent.Debrief -> BrainReply(DebriefComposer.compose(ctx.day, sir, ctx.now))
            Intent.AskTime -> {
                val d = ctx.now.date
                BrainReply(
                    "It's ${SpokenTime.time(ctx.now.time)}, $sir. ${SpokenTime.dayName(d.dayOfWeek)}, " +
                        "${d.day} ${SpokenTime.monthName(d.month.ordinal)}.",
                )
            }
            Intent.Help -> BrainReply(
                "I can call you at any time, keep today's plan, log what you study and run your night debrief. " +
                    "Try: call me at 7 AM. Or: my plan for today is DSA and OS. Or: log, finished linked lists.",
            )
            Intent.Greeting -> BrainReply("At your service, $sir.")
            Intent.Goodbye -> BrainReply(
                if (ctx.now.hour >= 21 || ctx.now.hour < 4) "Goodnight, $sir. Rest well." else "Anytime, $sir.",
                endConversation = true,
            )
        }
    }

    /** "At what time?" -> "at 7" : finish the call request from the previous turn. */
    private fun followUpTime(request: BrainRequest): Intent? {
        val lastJarvis = request.history.lastOrNull { it.speaker == Speaker.JARVIS }?.text ?: return null
        if (!lastJarvis.contains("At what time should I call you")) return null
        val match = TimeParser.parse(request.userText, request.context.now) ?: return null
        val earlier = request.history.lastOrNull { it.speaker == Speaker.USER }?.text.orEmpty()
        val label = Regex("""\b(?:to|for|about)\s+(.+)$""").find(earlier.lowercase())?.groupValues?.get(1).orEmpty()
        return Intent.ScheduleCall(match.at, label)
    }

    /** On the morning call, anything that isn't a command is the plan itself. */
    private fun morningPlan(request: BrainRequest): Intent? {
        if (request.occasion != Occasion.MORNING_PLAN) return null
        val items = IntentParser.splitItems(IntentParser.normalize(request.userText))
        return if (items.isEmpty()) null else Intent.SetPlan(items)
    }

    private fun dailyCall(intent: Intent.SetDailyCall, sir: String): BrainReply {
        val name = if (intent.kind == CallKind.NIGHT_DEBRIEF) "night debrief" else "morning call"
        val label = if (intent.kind == CallKind.NIGHT_DEBRIEF) "night_debrief" else "morning_plan"
        val speech = when {
            intent.enabled == false -> "The $name is switched off, $sir."
            intent.time != null -> "Your $name is now at ${SpokenTime.time(intent.time)}."
            else -> "The $name is switched on."
        }
        val time = intent.time?.let { "${it.hour}:${it.minute.toString().padStart(2, '0')}" }
        return BrainReply(speech, listOf(JarvisAction(ActionType.SET_DAILY_CALL, label = label, time = time, enabled = intent.enabled)))
    }

    private fun logReply(intent: Intent.Log, sir: String): BrainReply {
        val speech = when (intent.category) {
            LogCategory.PROBLEM -> "Noted the problem, $sir. We'll go through it in tonight's debrief."
            LogCategory.STUDY -> "Logged your study session" +
                (intent.minutes?.let { ": ${SpokenTime.duration(it)}" } ?: "") + ". Keep it up."
            LogCategory.WIN -> "Logged. Nicely done, $sir."
            else -> "Logged, $sir."
        }
        return BrainReply(
            speech,
            listOf(JarvisAction(ActionType.LOG, category = intent.category, text = intent.text, subject = intent.subject, minutes = intent.minutes)),
        )
    }

    private fun planSummary(request: BrainRequest): String {
        val plan = request.context.day.plan
        if (plan.isEmpty()) return "You haven't set a plan for today, ${request.context.addressAs}. What would you like to get done?"
        val open = plan.filter { it.status != PlanStatus.DONE && it.status != PlanStatus.SKIPPED }.map { it.title }
        val done = plan.count { it.status == PlanStatus.DONE }
        return if (open.isEmpty()) {
            "All ${plan.size} tasks are done. Outstanding."
        } else {
            "$done of ${plan.size} done. Remaining: ${DebriefComposer.joinSpoken(open)}."
        }
    }

    private suspend fun freeform(request: BrainRequest): BrainReply {
        val llm = localLlm
        if (llm != null && llm.isReady()) {
            val answer = runCatching { llm.generate(Prompts.OFFLINE_SYSTEM, Prompts.offlineUserPrompt(request)) }.getOrNull()
            if (!answer.isNullOrBlank()) return BrainReply(Prompts.cleanForSpeech(answer), usedOnDeviceAi = true)
        }
        return BrainReply(
            "I'm on my offline brain right now, ${request.context.addressAs}, so that one is beyond me. " +
                "I can manage your calls, plan, log and debrief. For deeper questions, connect Claude in settings " +
                "or install the on-device model.",
        )
    }
}
