package com.jarvis.app.voice.wake

import android.content.Context
import android.content.res.AssetManager
import com.jarvis.shared.model.WakePhrase
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The two neural networks behind Phase 2:
 *  - Wake word: a 3.3M-parameter streaming Zipformer (bundled in the APK, ~5 MB).
 *  - Voice lock: NeMo TitaNet-small speaker embeddings (downloaded on demand, 40 MB).
 * Both run 100% on the phone through sherpa-onnx.
 */
object VoiceModels {
    const val SAMPLE_RATE = 16_000
    const val SPEAKER_MODEL_ID = "nemo_en_titanet_small"
    private const val SPEAKER_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx"
    private const val SPEAKER_SHA256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e"
    private const val KWS = "kws"

    /** Tuned on 60 "Hey Jarvis" / "Jarvis" clips and 100 look-alike phrases (see docs/lessons/phase-2.md). */
    fun keywordSpotter(assets: AssetManager, phrase: WakePhrase): KeywordSpotter {
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$KWS/encoder.int8.onnx",
                    decoder = "$KWS/decoder.int8.onnx",
                    joiner = "$KWS/joiner.int8.onnx",
                ),
                tokens = "$KWS/tokens.txt",
                numThreads = 1,
                provider = "cpu",
            ),
            maxActivePaths = 4,
            keywordsFile = if (phrase == WakePhrase.BOTH) "$KWS/keywords_both.txt" else "$KWS/keywords_hey.txt",
            keywordsScore = 2.0f,
            keywordsThreshold = 0.2f,
            numTrailingBlanks = 1,
        )
        return KeywordSpotter(assets, config)
    }

    fun speakerModel(context: Context): File = File(context.filesDir, "models/titanet_small.onnx")

    fun hasSpeakerModel(context: Context): Boolean = speakerModel(context).let { it.isFile && it.length() > 0 }

    /** Downloads the voice-lock model once, checking its SHA-256 fingerprint before using it. */
    suspend fun downloadSpeakerModel(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = speakerModel(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "titanet.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = URL(SPEAKER_URL).openConnection()
        val total = connection.contentLengthLong
        connection.getInputStream().use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != SPEAKER_SHA256) {
            temp.delete()
            error("Download was corrupted (fingerprint mismatch). Please try again.")
        }
        if (!temp.renameTo(target)) error("Couldn't save the voice model")
    }
}
