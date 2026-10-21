package com.jarvis.app.brain

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.jarvis.shared.brain.LocalLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline AI: a small language model (for example Gemma) running on the
 * phone's own CPU through Google's LiteRT-LM runtime. No internet, no cost.
 * You import the model file (.litertlm) once from Settings.
 */
class OnDeviceLlm(private val context: Context) : LocalLlm {
    private val modelFile = File(context.filesDir, "models/jarvis-model.litertlm")
    private val mutex = Mutex()
    private var engine: Engine? = null

    fun isInstalled(): Boolean = modelFile.isFile && modelFile.length() > 0

    fun sizeMb(): Long = if (isInstalled()) modelFile.length() / (1024 * 1024) else 0

    override suspend fun isReady(): Boolean = isInstalled()

    override suspend fun generate(system: String, prompt: String): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val active = engine ?: Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath,
                ),
            ).also {
                it.initialize() // loads the model into memory: slow the first time
                engine = it
            }
            active.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { conversation ->
                conversation.sendMessage(prompt).toString()
            }
        }
    }

    /** Copies a model file the user picked (Downloads, Drive...) into private storage. */
    suspend fun install(uri: Uri, onProgress: (Float) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            release()
            modelFile.parentFile?.mkdirs()
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val temp = File(modelFile.parentFile, "incoming.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            } ?: error("Couldn't open the selected file")
            if (!temp.renameTo(modelFile)) {
                temp.copyTo(modelFile, overwrite = true)
                temp.delete()
            }
        }
    }

    suspend fun remove() = mutex.withLock {
        release()
        modelFile.delete()
    }

    private fun release() {
        runCatching { engine?.close() }
        engine = null
    }
}
