package com.jarvis.shared.brain

import com.jarvis.shared.model.BrainMode
import kotlin.coroutines.cancellation.CancellationException

/** Decides how hard a question is, so cheap questions use the cheap model. */
object ModelRouter {
    private val DEEP_HINTS = listOf(
        "explain", "why", "how does", "how do", "how is", "how to", "difference between", "teach", "understand",
        "doubt", "confus", "solve", "derive", "prove", "compare", "example", "debrief", "summar", "quiz",
        "plan my", "study plan", "roadmap", "interview", "algorithm", "complexity",
        // Urdu / Pashto
        "samjha", "samjhao", "samajh", "kya hota", "kyun", "kaise", "farq", "sanga",
    )

    fun depthFor(text: String, occasion: Occasion): Depth {
        if (occasion == Occasion.NIGHT_DEBRIEF && text.isBlank()) return Depth.DEEP
        val lower = text.lowercase()
        return if (lower.length > 220 || DEEP_HINTS.any { it in lower }) Depth.DEEP else Depth.QUICK
    }
}

/** The answer plus which brain gave it (and why it fell back, if it did). */
data class RoutedReply(val reply: BrainReply, val kind: BrainKind, val fallbackReason: String? = null)

/**
 * Graceful degradation: try Claude, and if anything goes wrong (no key,
 * no internet, credit ran out), quietly hand the question to the offline brain.
 */
class BrainRouter(
    private val claude: Brain?,
    private val offline: Brain,
    private val hasClaudeKey: suspend () -> Boolean,
    private val isOnline: () -> Boolean,
) {
    suspend fun reply(request: BrainRequest): RoutedReply {
        val mode = request.context.settings.brainMode
        if (mode == BrainMode.OFFLINE || claude == null) return offlineReply(request, null)

        if (!hasClaudeKey()) {
            // In AUTO mode a missing key is a choice, not an error, so stay quiet about it.
            return offlineReply(request, if (mode == BrainMode.CLAUDE) "no Claude API key is set" else null)
        }
        if (!isOnline()) return offlineReply(request, "there's no internet connection")

        return try {
            val reply = claude.reply(request)
            RoutedReply(reply, if (request.depth == Depth.DEEP) BrainKind.CLAUDE_DEEP else BrainKind.CLAUDE_QUICK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            offlineReply(request, e.message ?: "Claude couldn't be reached")
        }
    }

    private suspend fun offlineReply(request: BrainRequest, reason: String?): RoutedReply {
        val reply = offline.reply(request)
        return RoutedReply(reply, if (reply.usedOnDeviceAi) BrainKind.OFFLINE_AI else BrainKind.OFFLINE, reason)
    }
}
