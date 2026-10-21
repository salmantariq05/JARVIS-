package com.jarvis.app.voice

import com.jarvis.shared.AssistantTurn
import com.jarvis.shared.ConversationSession
import com.jarvis.shared.JarvisAssistant
import com.jarvis.shared.brain.BrainKind
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.data.JarvisRepository
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

/** Everything the screen shows about the current conversation. */
data class TalkState(
    val phase: Phase = Phase.IDLE,
    val heard: String = "",
    val reply: String = "",
    val brain: BrainKind? = null,
    val performed: List<String> = emptyList(),
    val error: String? = null,
    val ended: Boolean = false,
)

/**
 * The listen -> think -> speak loop, shared by the HUD and the call screen.
 * It knows nothing about buttons or layouts, so both screens can reuse it.
 */
class ConversationRunner(
    private val assistant: JarvisAssistant,
    private val ears: SpeechInput,
    private val voice: VoiceOutput,
    private val repository: JarvisRepository,
    private val mic: MicArbiter,
) {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state.asStateFlow()
    val level: StateFlow<Float> = ears.level
    val partial: StateFlow<String> = ears.partial

    private var chatSession: ConversationSession? = null
    private var lastChatAt = 0L

    /**
     * You tapped the core, typed, or said "Hey Jarvis". Keeps going while Jarvis asks follow-up questions.
     * [verifiedSpeaker] = the voice lock confirmed it's you. [beepFirst] = play the "I'm listening" chime.
     */
    suspend fun chat(typed: String? = null, verifiedSpeaker: Boolean = false, beepFirst: Boolean = false) = mic.hold {
        val session = chatSession(verifiedSpeaker)
        if (beepFirst) beep()
        var text: String = typed ?: listen() ?: return@hold idle()
        var turns = 0
        while (text.isNotBlank()) {
            val turn = think { assistant.respond(session, text) } ?: break
            speak(turn.speech)
            turns++
            if (turn.endConversation || !turn.speech.trimEnd().endsWith("?") || turns >= MAX_AUTO_TURNS) break
            text = listen() ?: break
        }
        lastChatAt = System.currentTimeMillis()
        idle()
    }

    /** Jarvis called you and you answered. Runs until you say goodbye or go quiet. */
    suspend fun call(occasion: Occasion, label: String) = mic.hold {
        _state.value = TalkState()
        val session = assistant.newSession(occasion)
        var turn: AssistantTurn = think { assistant.open(session, label) } ?: return@hold end()
        speak(turn.speech)
        var silences = 0
        while (!turn.endConversation) {
            val heard = listen() ?: break
            if (heard.isBlank()) {
                silences++
                val sir = repository.settings().addressAs
                if (silences >= 2) {
                    speak("I'll let you go, $sir. Call me back anytime.")
                    break
                }
                speak("I'm still here, $sir. Go ahead.")
                continue
            }
            silences = 0
            turn = think { assistant.respond(session, heard) } ?: break
            speak(turn.speech)
        }
        end()
    }

    fun stop() {
        voice.stop()
        ears.cancel()
        _state.update { it.copy(phase = Phase.IDLE) }
    }

    private fun chatSession(verifiedSpeaker: Boolean): ConversationSession {
        val now = System.currentTimeMillis()
        val current = chatSession
        // A pause longer than 10 minutes (or a change in who's verified) starts a fresh conversation.
        if (current != null && now - lastChatAt < SESSION_TIMEOUT_MS && current.verifiedSpeaker == verifiedSpeaker) return current
        return assistant.newSession(Occasion.CHAT, verifiedSpeaker).also { chatSession = it }
    }

    /** A short chime, much faster than saying "Yes, sir?" out loud. */
    private suspend fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            delay(220)
            tone.release()
        }
    }

    /** null = a real problem (no mic permission...), "" = silence. */
    private suspend fun listen(): String? {
        _state.update { it.copy(phase = Phase.LISTENING, heard = "", error = null) }
        val heard = ears.listen(repository.settings().speechLanguage)
        if (heard.error != null) {
            _state.update { it.copy(phase = Phase.IDLE, error = heard.error) }
            return null
        }
        _state.update { it.copy(heard = heard.text) }
        return heard.text
    }

    private suspend fun think(block: suspend () -> AssistantTurn): AssistantTurn? {
        _state.update { it.copy(phase = Phase.THINKING, error = null) }
        return try {
            block().also { turn ->
                _state.update { it.copy(reply = turn.speech, brain = turn.brain, performed = turn.performed) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = Phase.IDLE, error = "Something went wrong: ${e.message}") }
            null
        }
    }

    private suspend fun speak(text: String) {
        _state.update { it.copy(phase = Phase.SPEAKING, reply = text) }
        voice.speak(text, repository.settings().voiceName)
    }

    private fun idle() = _state.update { it.copy(phase = Phase.IDLE) }

    private fun end() = _state.update { it.copy(phase = Phase.IDLE, ended = true) }

    private companion object {
        const val MAX_AUTO_TURNS = 6
        const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
