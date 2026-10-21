package com.jarvis.app.voice.wake

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.app.container
import com.jarvis.shared.voice.WakeDecider
import com.jarvis.shared.voice.WakeDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the background listener is doing, shown on the HUD. */
data class WakeStatus(
    val listening: Boolean = false,
    val voiceLock: Boolean = false,
    val lastEvent: String? = null,
)

/**
 * Jarvis's always-on ears.
 *
 * Android only lets an app use the microphone in the background from a
 * "foreground service": a service that shows a permanent notification so you
 * always know you're being listened to. That's this class.
 *
 *  wake word heard ─► voice lock check ─► chime ─► shared chat (same as tapping the core)
 */
class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listener by lazy { KeywordListener(this) }
    private val decider = WakeDecider()
    private var verifier: SpeakerVerifier? = null
    private var conversation: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The settings the engine was started with; a change means restart. */
    private data class EngineConfig(val phrase: Any, val lockOn: Boolean, val enrolledOn: String)

    private var activeConfig: EngineConfig? = null
    private var engineStarting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Pause the wake word while any conversation (HUD, call, enrollment) is using the mic.
        scope.launch {
            container.mic.busyFlow().distinctUntilChanged().collect { busy ->
                if (busy) listener.pause() else listener.resume()
            }
        }
        // Stop when switched off; restart when the wake phrase or voice lock changes.
        scope.launch {
            container.repository.version.drop(1).collect {
                if (!container.repository.settings().alwaysListening) {
                    stopSelf()
                } else if (activeConfig != null && currentConfig() != activeConfig) {
                    stopEngine()
                    startEngine()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                container.repository.updateSettings { it.copy(alwaysListening = false) }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        createChannel(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Say \"Hey Jarvis\""), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Android blocks starting background microphone use unless Jarvis is on screen (e.g. right after a reboot).
            Log.w(TAG, "Not allowed to start listening right now", e)
            notifyResumeNeeded(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listener.isRunning && !engineStarting) scope.launch { startEngine() }
        return START_STICKY
    }

    private suspend fun currentConfig(): EngineConfig {
        val s = container.repository.settings()
        val profile = container.repository.voiceProfile()
        return EngineConfig(s.wakePhrase, s.voiceLockEnabled && profile != null, profile?.enrolledOn.orEmpty())
    }

    private suspend fun startEngine() {
        engineStarting = true
        try {
            val settings = container.repository.settings()
            val config = currentConfig()
            activeConfig = config
            verifier = if (config.lockOn) withContext(Dispatchers.Default) { SpeakerVerifier.load(this@ListeningService) } else null

            val started = listener.start(
                phrase = settings.wakePhrase,
                onDetected = { keyword, clip -> scope.launch { onWakeWord(keyword, clip) } },
            )
            if (!started) {
                container.wakeStatus.value = WakeStatus(lastEvent = "Microphone permission is off")
                stopSelf()
                return
            }
            if (container.mic.isBusy) listener.pause()
            acquireWakeLock()
            container.wakeStatus.value = WakeStatus(
                listening = true,
                voiceLock = verifier != null,
                lastEvent = "Listening for ${settings.wakePhrase.label}",
            )
        } finally {
            engineStarting = false
        }
    }

    private suspend fun stopEngine() {
        withContext(Dispatchers.Default) { listener.stop() }
        verifier?.release()
        verifier = null
    }

    private suspend fun onWakeWord(keyword: String, clip: FloatArray) {
        if (conversation?.isActive == true || container.mic.isBusy) return
        val settings = container.repository.settings()
        val profile = container.repository.voiceProfile()
        val verifier = verifier
        val lockOn = settings.voiceLockEnabled && profile != null && verifier != null
        val embedding = if (lockOn) withContext(Dispatchers.Default) { verifier?.embed(clip) } else null

        when (val decision = decider.decide(System.currentTimeMillis(), lockOn, profile, settings.lockStrictness, embedding)) {
            is WakeDecision.Accepted -> {
                val who = decision.score?.let { "Recognised you (match %.2f)".format(it) } ?: "Heard \"${keyword.replace('_', ' ').lowercase()}\""
                container.wakeStatus.value = container.wakeStatus.value.copy(lastEvent = who)
                conversation = scope.launch {
                    container.chatRunner.chat(verifiedSpeaker = decision.score != null, beepFirst = true)
                    // Some phones refuse speech recognition from the background: offer a one-tap fallback.
                    container.chatRunner.state.value.error?.let { notifyTapToTalk(this@ListeningService, it) }
                }
            }
            is WakeDecision.Rejected -> container.wakeStatus.value = container.wakeStatus.value.copy(
                lastEvent = "Ignored a voice that isn't yours (match %.2f)".format(decision.score),
            )
            WakeDecision.CoolingDown -> Unit
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        // Keeps the CPU awake for the listener while the screen is off. Costs some battery: that's the price of "always on".
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake-word")
            .apply { acquire() }
    }

    override fun onDestroy() {
        listener.stop()
        verifier?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        container.wakeStatus.value = WakeStatus(lastEvent = "Not listening")
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 11, Intent(this, ListeningService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis is listening")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .build()
    }

    companion object {
        private const val TAG = "ListeningService"
        private const val CHANNEL = "jarvis_listening"
        private const val NOTIFICATION_ID = 7100
        private const val RESUME_ID = 7101
        private const val TALK_ID = 7102
        private const val ACTION_STOP = "com.jarvis.app.STOP_LISTENING"

        /** Must be called while Jarvis is on screen (Android rule for background microphone use). */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                context.container.wakeStatus.value = WakeStatus(lastEvent = "Allow the microphone to use \"Hey Jarvis\"")
                return
            }
            ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Always listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Jarvis listens for \"Hey Jarvis\""
                },
            )
        }

        /** After a reboot Android won't let us start the mic in the background, so ask for one tap. */
        fun notifyResumeNeeded(context: Context) {
            post(context, RESUME_ID, "Tap to turn \"Hey Jarvis\" back on", Intent(context, MainActivity::class.java))
        }

        fun notifyTapToTalk(context: Context, reason: String) {
            post(context, TALK_ID, "Tap to talk to Jarvis ($reason)", Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_TALK))
        }

        private fun post(context: Context, id: Int, text: String, intent: Intent) {
            createChannel(context)
            val pending = PendingIntent.getActivity(
                context, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("Jarvis")
                .setContentText(text)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                // Notifications are off; nothing more we can do.
            }
        }
    }
}
