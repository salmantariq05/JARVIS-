package com.jarvis.app.ui.voicelock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.container
import com.jarvis.app.voice.wake.KeywordListener
import com.jarvis.app.voice.wake.SpeakerVerifier
import com.jarvis.app.voice.wake.VoiceModels
import com.jarvis.shared.model.WakePhrase
import com.jarvis.shared.time.SystemClock
import com.jarvis.shared.voice.EnrollmentQuality
import com.jarvis.shared.voice.VoiceProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EnrollStage { NEED_MODEL, DOWNLOADING, READY, RECORDING, DONE, TESTING, ERROR }

data class EnrollUi(
    val stage: EnrollStage = EnrollStage.READY,
    val downloadProgress: Float = 0f,
    val samples: Int = 0,
    val message: String = "",
    val quality: EnrollmentQuality? = null,
    val level: Float = 0f,
    val testScore: Float? = null,
    val testPassed: Boolean? = null,
)

/**
 * Records your voiceprint. It reuses the real wake-word engine, so every
 * sample is exactly what Jarvis will hear later: you saying "Hey Jarvis".
 */
class EnrollmentViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val listener = KeywordListener(app)
    private var verifier: SpeakerVerifier? = null
    private val embeddings = mutableListOf<FloatArray>()
    private var lastSampleAt = 0L
    private var session: Job? = null

    private val _ui = MutableStateFlow(EnrollUi())
    val ui: StateFlow<EnrollUi> = _ui.asStateFlow()

    init {
        _ui.value = EnrollUi(
            stage = if (VoiceModels.hasSpeakerModel(app)) EnrollStage.READY else EnrollStage.NEED_MODEL,
            message = if (VoiceModels.hasSpeakerModel(app)) "Ready when you are." else "First, download the voice model (40 MB, once).",
        )
    }

    fun downloadModel() {
        viewModelScope.launch {
            _ui.update { it.copy(stage = EnrollStage.DOWNLOADING, message = "Downloading the voice model…") }
            try {
                VoiceModels.downloadSpeakerModel(getApplication()) { p -> _ui.update { it.copy(downloadProgress = p) } }
                _ui.update { it.copy(stage = EnrollStage.READY, message = "Model ready. Find a quiet spot and tap START.") }
            } catch (e: Exception) {
                _ui.update { it.copy(stage = EnrollStage.ERROR, message = "Download failed: ${e.message}. Check your internet and retry.") }
            }
        }
    }

    fun startEnrollment() {
        embeddings.clear()
        _ui.update { it.copy(stage = EnrollStage.RECORDING, samples = 0, quality = null, message = prompt(1)) }
        runListener { clip ->
            val embedding = withContext(Dispatchers.Default) { verifier?.embed(clip) }
            if (embedding == null) {
                _ui.update { it.copy(message = "Too short. Say \"Hey Jarvis\" again.") }
                return@runListener false
            }
            embeddings += embedding
            if (embeddings.size < VoiceProfile.SAMPLES_NEEDED) {
                _ui.update { it.copy(samples = embeddings.size, message = "Got it. ${prompt(embeddings.size + 1)}") }
                return@runListener false
            }
            val profile = VoiceProfile.of(VoiceModels.SPEAKER_MODEL_ID, embeddings.toList(), SystemClock.now().date.toString())
            c.repository.saveVoiceProfile(profile)
            val settings = c.repository.updateSettings { it.copy(voiceLockEnabled = true) }
            val quality = profile.quality(settings.lockStrictness)
            _ui.update {
                it.copy(stage = EnrollStage.DONE, samples = embeddings.size, quality = quality, message = "Voice lock is ON. ${quality.advice}")
            }
            true
        }
    }

    /** Say "Hey Jarvis" once and see how closely it matches your voiceprint. */
    fun testVoice() {
        _ui.update { it.copy(stage = EnrollStage.TESTING, testScore = null, testPassed = null, message = "Say \"Hey Jarvis\" to test…") }
        runListener { clip ->
            val profile = c.repository.voiceProfile() ?: return@runListener true
            val embedding = withContext(Dispatchers.Default) { verifier?.embed(clip) } ?: return@runListener false
            val score = profile.score(embedding)
            val passed = score >= c.repository.settings().lockStrictness.threshold
            _ui.update {
                it.copy(
                    stage = EnrollStage.DONE,
                    testScore = score,
                    testPassed = passed,
                    message = if (passed) "✓ That's you." else "✗ Not recognised. Try Relaxed strictness or re-enroll.",
                )
            }
            true
        }
    }

    fun cancel() {
        session?.cancel()
        _ui.update { it.copy(stage = EnrollStage.READY, message = "Cancelled.") }
    }

    /**
     * Holds the microphone (pausing the background listener), runs the wake-word
     * engine and hands each detected clip to [onClip] until it returns true.
     */
    private fun runListener(onClip: suspend (FloatArray) -> Boolean) {
        session?.cancel()
        session = viewModelScope.launch {
            c.mic.hold {
                if (verifier == null) verifier = withContext(Dispatchers.Default) { SpeakerVerifier.load(getApplication()) }
                if (verifier == null) {
                    _ui.update { it.copy(stage = EnrollStage.NEED_MODEL, message = "Download the voice model first.") }
                    return@hold
                }
                val finished = CompletableDeferred<Unit>()
                val started = listener.start(
                    phrase = WakePhrase.BOTH,
                    onDetected = { _, clip ->
                        viewModelScope.launch {
                            val now = System.currentTimeMillis()
                            if (now - lastSampleAt < 1_200 || finished.isCompleted) return@launch
                            lastSampleAt = now
                            if (onClip(clip)) finished.complete(Unit)
                        }
                    },
                    onLevel = { level -> _ui.update { it.copy(level = level.coerceIn(0f, 1f)) } },
                )
                if (!started) {
                    _ui.update { it.copy(stage = EnrollStage.ERROR, message = "Microphone permission is off.") }
                    return@hold
                }
                val nudge = launch {
                    while (true) {
                        delay(10_000)
                        if (System.currentTimeMillis() - lastSampleAt > 10_000) {
                            _ui.update { it.copy(message = "I didn't catch it. Hold the phone about 30 cm away and say \"Hey Jarvis\" clearly.") }
                        }
                    }
                }
                try {
                    finished.await()
                } finally {
                    nudge.cancel()
                    withContext(Dispatchers.Default) { listener.stop() }
                    _ui.update { it.copy(level = 0f) }
                }
            }
        }
    }

    private fun prompt(n: Int) = "Say \"Hey Jarvis\" ($n of ${VoiceProfile.SAMPLES_NEEDED})"

    override fun onCleared() {
        listener.stop()
        verifier?.release()
    }
}
