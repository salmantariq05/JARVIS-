package com.jarvis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What came back from one listening attempt. Empty text = you stayed silent. */
data class Heard(val text: String, val error: String? = null)

/**
 * Jarvis's ears: Android's built-in speech recognizer, wrapped in a coroutine
 * so the rest of the code can simply write `val heard = speechInput.listen()`.
 */
class SpeechInput(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val _level = MutableStateFlow(0f)
    private val _partial = MutableStateFlow("")
    private var active: SpeechRecognizer? = null

    /** Microphone loudness 0..1, drives the HUD's voice wave. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Words recognised so far, shown live while you talk. */
    val partial: StateFlow<String> = _partial.asStateFlow()

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listen(languageTag: String): Heard = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext Heard("", "Speech recognition isn't available on this phone")
        _partial.value = ""
        suspendCancellableCoroutine { cont ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            active = recognizer

            fun finish(result: Heard) {
                _level.value = 0f
                recognizer.destroy()
                if (active === recognizer) active = null
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    // rms is roughly -2..10 dB; squash it into 0..1
                    _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    _level.value = 0f
                }
                override fun onError(error: Int) {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet for this language"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "This speech language isn't installed. Pick another in Settings"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy"
                        else -> "Couldn't hear you (code $error)"
                    }
                    finish(Heard("", message))
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finish(Heard(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _partial.value = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            }
            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                main.post {
                    recognizer.cancel()
                    recognizer.destroy()
                    _level.value = 0f
                }
            }
        }
    }

    fun cancel() {
        main.post { active?.cancel() }
    }
}
