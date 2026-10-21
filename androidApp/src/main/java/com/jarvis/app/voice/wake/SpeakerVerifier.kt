package com.jarvis.app.voice.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * Turns a clip of your voice into a 192-number "voiceprint" (embedding).
 * The maths that compares voiceprints lives in shared/voice/VoiceLock.kt.
 */
class SpeakerVerifier private constructor(private val extractor: SpeakerEmbeddingExtractor) {

    /** Returns null when the clip is too short to judge. */
    @Synchronized
    fun embed(clip: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(clip, VoiceModels.SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun release() = extractor.release()

    companion object {
        /** Loads the downloaded model, or returns null if it isn't installed yet. */
        fun load(context: Context): SpeakerVerifier? {
            if (!VoiceModels.hasSpeakerModel(context)) return null
            val config = SpeakerEmbeddingExtractorConfig(
                model = VoiceModels.speakerModel(context).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            return SpeakerVerifier(SpeakerEmbeddingExtractor(config = config))
        }
    }
}
