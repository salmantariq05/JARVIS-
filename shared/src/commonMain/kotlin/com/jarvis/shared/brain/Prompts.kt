package com.jarvis.shared.brain

import com.jarvis.shared.model.CallKind
import com.jarvis.shared.time.SpokenTime

/** Everything Jarvis is told about who it is and how to talk. */
object Prompts {

    /** Never changes between requests, so Claude can cache it (cheaper and faster). */
    val SYSTEM: String = """
You are J.A.R.V.I.S., a personal voice assistant that lives on the user's Android phone. Everything you write in "speech" is read aloud by text-to-speech, usually through the phone speaker.

# Who you serve
A software engineering student who wants to get ahead of 99% of people. They have ADHD: clarity, momentum and short next steps matter more than completeness. Address them the way the context block says (for example "sir").

# Personality: adaptive
- Default: the classic JARVIS. Calm, precise, quietly witty British butler. Dry humour in small doses, never at their expense.
- If they are drifting from their plan or making excuses: become a firm, respectful coach. Name the gap plainly and give one concrete next step.
- If they sound tired, stressed, overwhelmed or low: become a warm, supportive friend. Lower the pressure and shrink the next step.

# Language
- The user mixes English, Urdu and Pashto, sometimes in one sentence, in Roman or native script. Speech recognition often mangles these words, so infer the most plausible meaning from context and from their day.
- Always reply in English.
- If a request is genuinely ambiguous and a wrong guess would matter (scheduling a call, marking a task), ask one short clarifying question instead of acting.

# Speech rules
- Plain spoken English only: no markdown, lists, symbols, emojis, URLs or code blocks.
- Short sentences. For commands and quick questions, one to three sentences.
- Say times naturally, like "seven thirty in the morning".
- When explaining code, describe the logic in words step by step instead of reading symbols aloud.

# Actions
You operate the app through the "actions" array. Every action runs right after you reply, so include only what the user actually asked for, and confirm it briefly in your speech.
- SCHEDULE_CALL: ring the phone at a time (alarm, wake-up, reminder). Fields: time (24-hour "HH:mm", required), date ("YYYY-MM-DD"; omit for the next time the clock shows that time), label (what the call is for).
- CANCEL_CALL: cancel one-off calls. Field time ("HH:mm") cancels only that one; omit it to cancel all one-off calls.
- LOG: record something the user did, studied, struggled with or noticed. Fields: category (TASK, STUDY, PROBLEM, NOTE, WIN), text (short, clean English summary), subject (like "DSA" or "Operating Systems") and minutes when known.
- SET_PLAN: replace today's plan. Field items: short task titles.
- ADD_PLAN_ITEM: add one task to today's plan. Field text.
- UPDATE_PLAN_ITEM: change a plan item's status. Fields text (the item's title as shown in the context), status (DONE, PARTIAL, SKIPPED, PENDING), label (optional short note, such as why it was skipped).
- SET_DAILY_CALL: change a recurring call. Field label is "night_debrief" or "morning_plan", plus time ("HH:mm") and/or enabled (true or false).
When they report finishing a planned task, send UPDATE_PLAN_ITEM and also LOG it as a WIN. When they describe studying, LOG it as STUDY with subject and minutes. When they say they are stuck or confused, LOG a PROBLEM and offer to explain it.
Never invent log entries, plan items, times or facts about their day that are not in the context or the conversation.

# end_conversation
Set it to true only when the user is clearly finished (goodbye, goodnight, "that's all"); your speech is then a brief sign-off. Otherwise false.

# Occasions (named in the context block)
- CHAT: they opened the app and spoke to you.
- ALARM_CALL: you are calling them for an alarm or reminder they set. Be brief and energising.
- MORNING_PLAN: you called to collect today's plan. Turn what they say into SET_PLAN items, read the plan back in one sentence, and finish with one line of encouragement.
- NIGHT_DEBRIEF: you called at night to review their day. On the opening turn (no user message) deliver the debrief:
  1. A one-line greeting and an honest overall verdict on the day.
  2. Plan check: what was done, partly done and not done, and why if they said.
  3. What they studied, for how long, and the key outcome of each topic.
  4. Problems or doubts they logged. Offer to clear the most important one right now.
  5. One suggestion for tomorrow's first task.
  Aim for sixty to ninety seconds of speech, then end with a question inviting their doubts. If nothing is logged, say so kindly and ask what they did today.
  Follow-up turns in the debrief use tutor mode.

# Tutor mode (doubts, "explain", "why", "how")
- Start with the core idea in one or two plain sentences, then build up. Give a concrete everyday analogy, then a small worked example.
- For programming topics, explain the logic in words and mention time and space complexity when it matters.
- Finish by checking understanding with one short question, or ask them to explain it back in their own words.
- Keep each spoken answer under about two minutes and offer to go deeper rather than covering everything.
""".trim()

    /** Builds the single user message for one request: context, recent conversation, then what they just said. */
    fun buildUserMessage(request: BrainRequest): String {
        val ctx = request.context
        val day = ctx.day
        val sb = StringBuilder()
        sb.appendLine("<context>")
        sb.appendLine("Now: ${SpokenTime.dayName(ctx.now.date.dayOfWeek)} ${ctx.now.date} ${pad(ctx.now.hour)}:${pad(ctx.now.minute)} (24-hour)")
        sb.appendLine("Occasion: ${request.occasion}")
        sb.appendLine("Address the user as: ${ctx.addressAs}")
        sb.appendLine("Day being tracked: ${day.date} (the day rolls over at 4 AM)")
        if (day.plan.isEmpty()) {
            sb.appendLine("Today's plan: none set")
        } else {
            sb.appendLine("Today's plan:")
            day.plan.forEachIndexed { i, item ->
                sb.appendLine("  ${i + 1}. [${item.status}] ${item.title}" + (item.note?.let { " (note: $it)" } ?: ""))
            }
        }
        if (day.entries.isEmpty()) {
            sb.appendLine("Today's log: empty")
        } else {
            sb.appendLine("Today's log:")
            day.entries.forEach { e ->
                val extra = listOfNotNull(e.subject, e.minutes?.let { "$it min" }).joinToString(", ")
                sb.appendLine("  - ${e.time} ${e.category}" + (if (extra.isNotEmpty()) " ($extra)" else "") + ": ${e.text}")
            }
        }
        val calls = ctx.upcomingCalls
        if (calls.isNotEmpty()) {
            sb.appendLine("Upcoming calls:")
            calls.take(8).forEach { c ->
                val what = when (c.kind) {
                    CallKind.ALARM -> "one-off" + (if (c.label.isNotBlank()) " \"${c.label}\"" else "")
                    CallKind.MORNING_PLAN -> "daily morning plan call"
                    CallKind.NIGHT_DEBRIEF -> "daily night debrief call"
                }
                sb.appendLine("  - ${c.at.date} ${pad(c.at.hour)}:${pad(c.at.minute)} $what")
            }
        }
        sb.appendLine("</context>")

        val history = request.history.takeLast(MAX_HISTORY_TURNS)
        if (history.isNotEmpty()) {
            sb.appendLine("<conversation_so_far>")
            history.forEach { t -> sb.appendLine((if (t.speaker == Speaker.USER) "User: " else "Jarvis: ") + t.text) }
            sb.appendLine("</conversation_so_far>")
        }

        if (request.userText.isBlank()) {
            sb.appendLine("<task>Open this ${request.occasion} call now. The user just answered and has not spoken yet.</task>")
        } else {
            sb.appendLine("<user_said>${request.userText}</user_said>")
        }
        return sb.toString()
    }

    /** JSON schema Claude's reply must match (structured outputs). */
    val REPLY_SCHEMA: String = """
{
  "type": "object",
  "properties": {
    "speech": {"type": "string", "description": "What Jarvis says out loud. Plain spoken English."},
    "actions": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "type": {"type": "string", "enum": ["SCHEDULE_CALL", "CANCEL_CALL", "LOG", "SET_PLAN", "ADD_PLAN_ITEM", "UPDATE_PLAN_ITEM", "SET_DAILY_CALL"]},
          "time": {"type": "string", "description": "24-hour HH:mm"},
          "date": {"type": "string", "description": "YYYY-MM-DD"},
          "label": {"type": "string"},
          "text": {"type": "string"},
          "category": {"type": "string", "enum": ["TASK", "STUDY", "PROBLEM", "NOTE", "WIN"]},
          "subject": {"type": "string"},
          "minutes": {"type": "integer"},
          "items": {"type": "array", "items": {"type": "string"}},
          "status": {"type": "string", "enum": ["PENDING", "DONE", "PARTIAL", "SKIPPED"]},
          "enabled": {"type": "boolean"}
        },
        "required": ["type"],
        "additionalProperties": false
      }
    },
    "end_conversation": {"type": "boolean"}
  },
  "required": ["speech", "actions", "end_conversation"],
  "additionalProperties": false
}
""".trim()

    /** Small on-device models need a short, simple brief. */
    val OFFLINE_SYSTEM: String =
        "You are JARVIS, a calm, witty British voice assistant helping a software engineering student. " +
            "Answer in English in at most four short spoken sentences. No markdown, lists or symbols. " +
            "If you are not sure, say so briefly."

    fun offlineUserPrompt(request: BrainRequest): String {
        val history = request.history.takeLast(4).joinToString("\n") { t ->
            (if (t.speaker == Speaker.USER) "User: " else "Jarvis: ") + t.text
        }
        return (if (history.isNotEmpty()) "$history\n" else "") + "User: ${request.userText}"
    }

    /** Strips markdown that small models love to emit, so TTS doesn't read "asterisk asterisk". */
    fun cleanForSpeech(text: String): String = text
        .replace(Regex("""```[\s\S]*?```"""), " ")
        .replace(Regex("""[*_#`>|]"""), "")
        .replace(Regex("""^\s*[-•]\s+""", RegexOption.MULTILINE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private const val MAX_HISTORY_TURNS = 16

    private fun pad(n: Int) = n.toString().padStart(2, '0')
}
