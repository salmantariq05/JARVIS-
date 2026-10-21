package com.jarvis.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Jarvis's voice: Android text-to-speech, preferring a British English voice.
 * [speak] suspends until Jarvis has finished talking, which keeps the
 * conversation loop simple: speak, then listen, then speak again.
 */
class VoiceOutput(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val _speaking = MutableStateFlow(false)
    private var appliedVoice: String? = "unset"

    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pending.remove(it)?.complete(Unit) }
            }
        })
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    /** Offline English voices installed on this phone (for the Settings picker). */
    suspend fun englishVoices(): List<String> {
        if (!ready.await()) return emptyList()
        return tts.voices.orEmpty()
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .sortedWith(compareBy<Voice>({ it.locale.country != "GB" }, { it.name }))
            .map { it.name }
    }

    suspend fun speak(text: String, voiceName: String? = null) {
        if (text.isBlank() || !ready.await()) return
        applyVoice(voiceName)
        val chunks = chunk(text, TextToSpeech.getMaxSpeechInputLength() - 200)
        val waits = chunks.map { piece ->
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            tts.speak(piece, TextToSpeech.QUEUE_ADD, Bundle(), id)
            done
        }
        try {
            waits.forEach { it.await() }
        } catch (e: CancellationException) {
            stop()
            throw e
        } finally {
            _speaking.value = false
        }
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
        _speaking.value = false
    }

    private fun applyVoice(voiceName: String?) {
        if (voiceName == appliedVoice) return
        appliedVoice = voiceName
        val voices = tts.voices.orEmpty()
        val chosen = voices.firstOrNull { it.name == voiceName }
            ?: voices.firstOrNull { it.locale == Locale.UK && !it.isNetworkConnectionRequired }
        if (chosen != null) tts.voice = chosen else tts.language = Locale.UK
        tts.setPitch(0.92f) // a touch deeper, more butler
        tts.setSpeechRate(1.0f)
    }

    /** TTS engines have a max length per call; split long debriefs at sentence ends. */
    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        text.split(Regex("""(?<=[.!?])\s+""")).forEach { sentence ->
            if (current.length + sentence.length + 1 > max && current.isNotEmpty()) {
                out += current.toString()
                current.clear()
            }
            current.append(sentence).append(' ')
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }
}
