package com.jarvis.shared

import com.jarvis.shared.data.InMemoryTextStorage
import com.jarvis.shared.data.JarvisRepository
import com.jarvis.shared.voice.EnergyGate
import com.jarvis.shared.voice.EnrollmentQuality
import com.jarvis.shared.voice.FloatRingBuffer
import com.jarvis.shared.voice.LockStrictness
import com.jarvis.shared.voice.SpeechTrimmer
import com.jarvis.shared.voice.VoiceMath
import com.jarvis.shared.voice.VoiceProfile
import com.jarvis.shared.voice.WakeDecider
import com.jarvis.shared.voice.WakeDecision
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceLockTest {
    private val rnd = Random(42)

    /** A fake "voice": a fixed direction plus a little noise per recording. */
    private fun voice(seed: Int) = Random(seed).let { r -> FloatArray(192) { r.nextFloat() - 0.5f } }.let { base ->
        { noise: Float -> FloatArray(192) { base[it] + (rnd.nextFloat() - 0.5f) * noise } }
    }

    @Test fun cosineBasics() {
        val a = floatArrayOf(1f, 0f)
        assertEquals(1f, VoiceMath.cosine(a, floatArrayOf(3f, 0f)), 1e-6f)
        assertEquals(0f, VoiceMath.cosine(a, floatArrayOf(0f, 2f)), 1e-6f)
        assertEquals(-1f, VoiceMath.cosine(a, floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test fun ownerScoresHigherThanStranger() {
        val me = voice(1)
        val stranger = voice(2)
        val profile = VoiceProfile.of("test", List(8) { me(0.3f) }, "2026-10-21")
        val mine = profile.score(me(0.3f))
        val theirs = profile.score(stranger(0.3f))
        assertTrue(mine > 0.8f, "owner score $mine")
        assertTrue(abs(theirs) < 0.3f, "stranger score $theirs")
        assertEquals(EnrollmentQuality.GREAT, profile.quality(LockStrictness.BALANCED))
    }

    @Test fun noisyEnrollmentIsFlagged() {
        val me = voice(3)
        val profile = VoiceProfile.of("test", List(8) { me(4f) }, "2026-10-21")
        assertEquals(EnrollmentQuality.NOISY, profile.quality(LockStrictness.BALANCED))
    }

    @Test fun deciderAppliesLockAndCooldown() {
        val me = voice(4)
        val profile = VoiceProfile.of("test", List(8) { me(0.3f) }, "2026-10-21")
        val decider = WakeDecider(cooldownMs = 2_000)
        assertIs<WakeDecision.Rejected>(decider.decide(0, true, profile, LockStrictness.BALANCED, voice(5)(0.3f)))
        assertIs<WakeDecision.Accepted>(decider.decide(100, true, profile, LockStrictness.BALANCED, me(0.3f)))
        assertEquals(WakeDecision.CoolingDown, decider.decide(1_000, true, profile, LockStrictness.BALANCED, me(0.3f)))
        val unlocked = decider.decide(5_000, false, null, LockStrictness.BALANCED, null)
        assertNull(assertIs<WakeDecision.Accepted>(unlocked).score)
    }

    @Test fun ringBufferKeepsNewestSamples() {
        val ring = FloatRingBuffer(5)
        ring.write(floatArrayOf(1f, 2f, 3f))
        ring.write(floatArrayOf(4f, 5f, 6f, 7f))
        assertEquals(listOf(3f, 4f, 5f, 6f, 7f), ring.latest(10).toList())
        assertEquals(listOf(6f, 7f), ring.latest(2).toList())
    }

    @Test fun trimmerRemovesSilence() {
        val sr = 16_000
        val silence = FloatArray(sr) { (rnd.nextFloat() - 0.5f) * 0.002f }
        val tone = FloatArray(sr / 2) { 0.5f * sin(2 * PI * 220 * it / sr).toFloat() }
        val trimmed = SpeechTrimmer.trim(silence + tone + silence, sr)
        assertTrue(trimmed.size in (sr / 2)..(sr / 2 + sr / 5), "trimmed to ${trimmed.size} samples")
    }

    @Test fun energyGateWakesOnSpeechAndLearnsQuietRooms() {
        val gate = EnergyGate(hangoverFrames = 2)
        val quiet = FloatArray(1600) { (rnd.nextFloat() - 0.5f) * 0.004f }
        val loud = FloatArray(1600) { 0.3f * sin(2 * PI * 200 * it / 16_000).toFloat() }
        repeat(50) { gate.isActive(quiet) }
        assertTrue(!gate.isActive(quiet), "quiet room stays asleep")
        assertTrue(gate.isActive(loud), "speech wakes the gate")
        assertTrue(gate.isActive(quiet), "hangover keeps it open between words")
        gate.isActive(quiet)
        assertTrue(!gate.isActive(quiet), "closes after the hangover")
    }

    @Test fun profileIsSavedAndDeleted() = runTest {
        val repo = JarvisRepository(InMemoryTextStorage())
        val profile = VoiceProfile.of("titanet", List(8) { voice(6)(0.2f) }, "2026-10-21")
        repo.saveVoiceProfile(profile)
        assertEquals(profile, repo.voiceProfile())
        repo.deleteVoiceProfile()
        assertNull(repo.voiceProfile())
    }
}
