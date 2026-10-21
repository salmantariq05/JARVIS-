package com.jarvis.shared.brain

import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.JarvisSettings
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.model.ScheduledCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Why Jarvis is talking: you tapped it, or it called you. */
enum class Occasion { CHAT, ALARM_CALL, MORNING_PLAN, NIGHT_DEBRIEF }

/** QUICK -> fast cheap model. DEEP -> strongest model (debrief, tutoring). */
enum class Depth { QUICK, DEEP }

/** Shown on the HUD so you always know which brain answered. */
enum class BrainKind(val label: String) {
    CLAUDE_QUICK("CLAUDE · HAIKU"),
    CLAUDE_DEEP("CLAUDE · OPUS"),
    OFFLINE("OFFLINE · RULES"),
    OFFLINE_AI("OFFLINE · ON-DEVICE AI"),
}

enum class Speaker { USER, JARVIS }

data class Turn(val speaker: Speaker, val text: String)

/** Everything a brain needs to know about your day before it answers. */
data class BrainContext(
    val now: LocalDateTime,
    val day: DayRecord,
    val upcomingCalls: List<ScheduledCall>,
    val settings: JarvisSettings,
) {
    val addressAs: String get() = settings.addressAs
}

data class BrainRequest(
    val userText: String,
    val history: List<Turn>,
    val context: BrainContext,
    val depth: Depth,
    val occasion: Occasion,
)

@Serializable
enum class ActionType { SCHEDULE_CALL, CANCEL_CALL, LOG, SET_PLAN, ADD_PLAN_ITEM, UPDATE_PLAN_ITEM, SET_DAILY_CALL }

/**
 * Something the brain wants the app to DO. Deliberately flat (every field
 * optional except [type]) so it maps 1:1 onto Claude's JSON schema.
 */
@Serializable
data class JarvisAction(
    val type: ActionType,
    val time: String? = null,
    val date: String? = null,
    val label: String? = null,
    val text: String? = null,
    val category: LogCategory? = null,
    val subject: String? = null,
    val minutes: Int? = null,
    val items: List<String>? = null,
    val status: PlanStatus? = null,
    val enabled: Boolean? = null,
)

/** What every brain returns: words to speak + actions to run. */
@Serializable
data class BrainReply(
    val speech: String,
    val actions: List<JarvisAction> = emptyList(),
    @SerialName("end_conversation") val endConversation: Boolean = false,
    @Transient val usedOnDeviceAi: Boolean = false,
)

/**
 * The Strategy pattern: ClaudeBrain and OfflineBrain both promise to
 * implement this one function, so the rest of the app can swap them freely.
 */
interface Brain {
    suspend fun reply(request: BrainRequest): BrainReply
}

/** Thrown when a brain can't answer right now (no credit, no network...). */
class BrainUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A small language model running on the phone itself (no internet). */
interface LocalLlm {
    suspend fun isReady(): Boolean
    suspend fun generate(system: String, prompt: String): String
}
