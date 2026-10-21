package com.jarvis.shared.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Root-mean-square loudness of a chunk of audio (samples in -1..1). */
fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
    if (to <= from) return 0f
    var sum = 0.0
    for (i in from until to) sum += samples[i] * samples[i]
    return sqrt(sum / (to - from)).toFloat()
}

/**
 * Keeps the last N seconds of microphone audio, overwriting the oldest.
 * When the wake word fires, the audio of "Hey Jarvis" is already in here,
 * ready for the voice lock to check.
 */
class FloatRingBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var writePos = 0
    private var filled = 0

    fun write(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            data[writePos] = samples[i]
            writePos = (writePos + 1) % capacity
        }
        filled = min(capacity, filled + count)
    }

    /** The most recent [count] samples, oldest first. */
    fun latest(count: Int): FloatArray {
        val n = min(count, filled)
        val out = FloatArray(n)
        val start = (writePos - n + capacity) % capacity
        for (i in 0 until n) out[i] = data[(start + i) % capacity]
        return out
    }

    fun clear() {
        filled = 0
        writePos = 0
    }
}

/**
 * Cuts the silence off both ends of a clip, so the voice lock judges your
 * voice and not the room noise. Works on 20 ms frames.
 */
object SpeechTrimmer {
    fun trim(samples: FloatArray, sampleRate: Int, keepRatio: Float = 0.2f, marginMs: Int = 80): FloatArray {
        val frame = sampleRate / 50
        if (samples.size < frame * 3) return samples
        val energies = (0 until samples.size / frame).map { f -> rms(samples, f * frame, (f + 1) * frame) }
        val peak = energies.maxOrNull() ?: return samples
        if (peak == 0f) return samples
        val loud = energies.indices.filter { energies[it] >= peak * keepRatio }
        val margin = marginMs * sampleRate / 1000
        val start = max(0, loud.first() * frame - margin)
        val end = min(samples.size, (loud.last() + 1) * frame + margin)
        return samples.copyOfRange(start, end)
    }
}

/**
 * A tiny voice-activity detector that saves battery: the wake-word model only
 * runs when the room is louder than its usual background noise.
 *
 * It learns the noise floor slowly while quiet, and says "active" when a frame
 * is clearly above it. A short "hangover" keeps it active between words.
 */
class EnergyGate(
    private val ratio: Float = 2.5f,
    private val minLevel: Float = 0.004f,
    private val hangoverFrames: Int = 15,
) {
    private var noiseFloor = 0.01f
    private var hangover = 0

    /** Feed one frame (e.g. 100 ms). Returns true if the wake-word model should hear it. */
    fun isActive(frame: FloatArray): Boolean {
        val level = rms(frame)
        val loud = level > max(minLevel, noiseFloor * ratio)
        if (loud) {
            hangover = hangoverFrames
        } else {
            // Adapt to the room only while it's quiet, so speech doesn't raise the floor.
            noiseFloor = 0.95f * noiseFloor + 0.05f * level
            if (hangover > 0) hangover--
        }
        return loud || hangover > 0
    }

    val currentNoiseFloor: Float get() = noiseFloor
}
