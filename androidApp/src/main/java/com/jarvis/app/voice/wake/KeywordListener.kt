package com.jarvis.app.voice.wake

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.shared.model.WakePhrase
import com.jarvis.shared.voice.EnergyGate
import com.jarvis.shared.voice.FloatRingBuffer
import com.jarvis.shared.voice.SpeechTrimmer
import com.jarvis.shared.voice.rms
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Listens to the microphone on its own thread and reports when it hears the
 * wake word, together with the audio clip of you saying it (for the voice lock).
 *
 *  mic ─► 100 ms frames ─► ring buffer (last 2 s)
 *                      └─► energy gate ─(only when someone talks)─► keyword spotter ─► onDetected(keyword, clip)
 */
class KeywordListener(private val context: Context) {
    @Volatile private var running = false
    @Volatile private var paused = false
    private var worker: Thread? = null

    val isRunning: Boolean get() = running

    /** Returns false (and does nothing) if the microphone permission is missing. */
    fun start(phrase: WakePhrase, onDetected: (keyword: String, clip: FloatArray) -> Unit, onLevel: (Float) -> Unit = {}): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        running = true
        paused = false
        worker = thread(name = "jarvis-wake-word", isDaemon = true) {
            try {
                loop(phrase, onDetected, onLevel)
            } catch (e: Throwable) {
                Log.e(TAG, "Wake word listener crashed", e)
            } finally {
                running = false
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(1_000)
        worker = null
    }

    /** Releases the mic (e.g. while Jarvis is talking with you) without unloading the model. */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun loop(phrase: WakePhrase, onDetected: (String, FloatArray) -> Unit, onLevel: (Float) -> Unit) {
        val spotter = VoiceModels.keywordSpotter(context.assets, phrase)
        var stream = spotter.createStream()
        val ring = FloatRingBuffer(VoiceModels.SAMPLE_RATE * 2)
        val gate = EnergyGate()
        val frame = ShortArray(VoiceModels.SAMPLE_RATE / 10) // 100 ms
        var record: AudioRecord? = null
        var wasActive = false
        try {
            while (running) {
                if (paused) {
                    record?.let { it.stop(); it.release() }
                    record = null
                    onLevel(0f)
                    Thread.sleep(150)
                    continue
                }
                val mic = record ?: openMic().also {
                    record = it
                    ring.clear()
                    spotter.reset(stream)
                }
                val n = mic.read(frame, 0, frame.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { frame[it] / 32768f }
                ring.write(samples)
                onLevel(rms(samples) * 8f)

                // Battery saver: skip the neural network while the room is quiet.
                if (!gate.isActive(samples)) {
                    wasActive = false
                    continue
                }
                // When speech starts, include 300 ms from before it so the first syllable isn't lost.
                stream.acceptWaveform(if (wasActive) samples else ring.latest(VoiceModels.SAMPLE_RATE * 3 / 10), VoiceModels.SAMPLE_RATE)
                wasActive = true

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        spotter.reset(stream)
                        val clip = SpeechTrimmer.trim(ring.latest(VoiceModels.SAMPLE_RATE * 18 / 10), VoiceModels.SAMPLE_RATE)
                        onDetected(keyword, clip)
                    }
                }
            }
        } finally {
            record?.let { it.stop(); it.release() }
            stream.release()
            spotter.release()
            onLevel(0f)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(VoiceModels.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            VoiceModels.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuffer, VoiceModels.SAMPLE_RATE), // at least 0.5 s of buffer
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        record.startRecording()
        return record
    }

    private companion object {
        const val TAG = "KeywordListener"
    }
}
