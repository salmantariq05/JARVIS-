package com.jarvis.shared.voice

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Speaker verification maths.
 *
 * A neural network turns ~1 second of your voice into an "embedding": a list
 * of 192 numbers that describe HOW you sound (not WHAT you said). Two clips of
 * the same person point in nearly the same direction, so we compare them with
 * cosine similarity: 1.0 = identical direction, 0.0 = unrelated.
 */
object VoiceMath {
    fun normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding sizes differ: ${a.size} vs ${b.size}" }
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    /** Average direction of several embeddings (each normalised first, so loud clips don't dominate). */
    fun centroid(samples: List<FloatArray>): FloatArray {
        require(samples.isNotEmpty())
        val sum = FloatArray(samples[0].size)
        samples.map(::normalize).forEach { s -> for (i in sum.indices) sum[i] += s[i] }
        return normalize(sum)
    }
}

/**
 * How picky the voice lock is. Thresholds come from measuring the speaker model
 * (NeMo TitaNet-small) on real recordings with 1.2 s clips (see docs/lessons/phase-2.md):
 *   0.30 -> accepted the owner 95% of the time, let others in 2.9%
 *   0.35 -> accepted the owner 95% of the time, let others in 2.1%
 *   0.40 -> accepted the owner 89% of the time, let others in 0.4%
 */
@Serializable
enum class LockStrictness(val threshold: Float, val label: String) {
    RELAXED(0.30f, "Relaxed · rarely misses you"),
    BALANCED(0.35f, "Balanced · recommended"),
    STRICT(0.40f, "Strict · best at blocking others"),
}

/** Your enrolled voice: the embeddings from the "Hey Jarvis" samples you recorded. */
@Serializable
data class VoiceProfile(
    val modelId: String,
    val samples: List<List<Float>>,
    val enrolledOn: String,
) {
    private val vectors: List<FloatArray> get() = samples.map { it.toFloatArray() }

    val centroid: FloatArray get() = VoiceMath.centroid(vectors)

    fun score(embedding: FloatArray): Float = VoiceMath.cosine(centroid, embedding)

    /**
     * Leave-one-out check: how well does each sample match the average of the
     * OTHER samples? Low numbers mean noisy or inconsistent recordings.
     */
    fun selfConsistency(): List<Float> {
        val v = vectors
        if (v.size < 2) return emptyList()
        return v.indices.map { i -> VoiceMath.cosine(VoiceMath.centroid(v.filterIndexed { j, _ -> j != i }), v[i]) }
    }

    /** Advice shown after enrollment, based on the leave-one-out scores. */
    fun quality(strictness: LockStrictness): EnrollmentQuality {
        val loo = selfConsistency()
        if (loo.isEmpty()) return EnrollmentQuality.TOO_FEW
        val weakest = loo.minOrNull() ?: 0f
        return when {
            weakest >= strictness.threshold + 0.15f -> EnrollmentQuality.GREAT
            weakest >= strictness.threshold + 0.05f -> EnrollmentQuality.OK
            else -> EnrollmentQuality.NOISY
        }
    }

    companion object {
        const val SAMPLES_NEEDED = 8

        fun of(modelId: String, embeddings: List<FloatArray>, enrolledOn: String) =
            VoiceProfile(modelId, embeddings.map { VoiceMath.normalize(it).toList() }, enrolledOn)
    }
}

enum class EnrollmentQuality(val advice: String) {
    GREAT("Excellent: your samples are very consistent."),
    OK("Good. If Jarvis sometimes ignores you, re-enroll somewhere quieter."),
    NOISY("Your samples vary a lot. Re-enroll in a quiet room, phone about 30 cm away."),
    TOO_FEW("Not enough samples yet."),
}

/** The outcome of hearing a wake word. */
sealed interface WakeDecision {
    data class Accepted(val score: Float?) : WakeDecision
    data class Rejected(val score: Float) : WakeDecision
    data object CoolingDown : WakeDecision
}

/**
 * Decides whether a detected wake word should wake Jarvis.
 * Cooldown stops one long "Jaaarvis" from triggering twice.
 */
class WakeDecider(private val cooldownMs: Long = 2_500) {
    private var lastAcceptedAt = Long.MIN_VALUE / 2

    fun decide(nowMs: Long, lockEnabled: Boolean, profile: VoiceProfile?, strictness: LockStrictness, embedding: FloatArray?): WakeDecision {
        if (nowMs - lastAcceptedAt < cooldownMs) return WakeDecision.CoolingDown
        if (!lockEnabled || profile == null || embedding == null) {
            lastAcceptedAt = nowMs
            return WakeDecision.Accepted(null)
        }
        val score = profile.score(embedding)
        return if (score >= strictness.threshold) {
            lastAcceptedAt = nowMs
            WakeDecision.Accepted(score)
        } else {
            WakeDecision.Rejected(score)
        }
    }
}
