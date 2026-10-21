package com.jarvis.app

import android.app.Application
import android.content.Context
import com.jarvis.app.brain.OnDeviceLlm
import com.jarvis.app.calls.AndroidCallScheduler
import com.jarvis.app.calls.CallNotifier
import com.jarvis.app.data.FileTextStorage
import com.jarvis.app.data.SecretStore
import com.jarvis.app.system.Connectivity
import com.jarvis.app.voice.SpeechInput
import com.jarvis.app.voice.VoiceOutput
import com.jarvis.shared.JarvisAssistant
import com.jarvis.shared.brain.BrainRouter
import com.jarvis.shared.brain.ClaudeBrain
import com.jarvis.shared.brain.OfflineBrain
import com.jarvis.shared.data.JarvisRepository
import com.jarvis.shared.time.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class JarvisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CallNotifier.createChannels(this)
        // Make sure the morning and night calls are armed every time the app starts.
        container.appScope.launch { container.assistant.rescheduleAll() }
    }
}

/** Handy shortcut: context.container from anywhere. */
val Context.container: AppContainer get() = (applicationContext as JarvisApp).container

/**
 * Manual dependency injection: every long-lived object is created once, here,
 * and handed to whoever needs it. (Libraries like Hilt automate exactly this.)
 */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository = JarvisRepository(FileTextStorage(File(app.filesDir, "jarvis")))
    val secrets = SecretStore(app)
    val scheduler = AndroidCallScheduler(app)
    val onDeviceLlm = OnDeviceLlm(app)

    val claudeBrain = ClaudeBrain(apiKey = { secrets.claudeKey() })
    private val offlineBrain = OfflineBrain(localLlm = onDeviceLlm)

    private val router = BrainRouter(
        claude = claudeBrain,
        offline = offlineBrain,
        hasClaudeKey = { !secrets.claudeKey().isNullOrBlank() },
        isOnline = { Connectivity.isOnline(app) },
    )

    val assistant = JarvisAssistant(repository, router, scheduler, SystemClock)

    val speechInput by lazy { SpeechInput(app) }
    val voiceOutput by lazy { VoiceOutput(app) }
}
