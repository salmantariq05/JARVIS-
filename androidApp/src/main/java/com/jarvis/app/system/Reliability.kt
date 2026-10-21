package com.jarvis.app.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.calls.CallNotifier
import com.jarvis.app.container

/** One thing that must be switched on for Jarvis to work reliably, plus the settings screen that fixes it. */
data class ReliabilityCheck(val title: String, val ok: Boolean, val detail: String, val fix: Intent)

object Reliability {
    fun checks(context: Context): List<ReliabilityCheck> {
        val packageUri = Uri.parse("package:${context.packageName}")
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val exact = context.container.scheduler.canScheduleExact()
        val fullScreen = CallNotifier.canUseFullScreen(context)
        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val speech = SpeechRecognizer.isRecognitionAvailable(context)

        return listOf(
            ReliabilityCheck("Microphone", mic, "So Jarvis can hear you", appDetails),
            ReliabilityCheck(
                "Notifications",
                notifications,
                "So Jarvis can ring you",
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            ),
            ReliabilityCheck("Exact alarms", exact, "Calls ring at the exact minute", Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)),
            ReliabilityCheck(
                "Full-screen calls",
                fullScreen,
                "Call screen appears over the lock screen",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri)
                } else {
                    appDetails
                },
            ),
            ReliabilityCheck(
                "Battery: unrestricted",
                battery,
                "Stops Samsung's battery saver from putting Jarvis to sleep",
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            ),
            ReliabilityCheck(
                "Speech recognition",
                speech,
                if (speech) "Speech engine found" else "Install \"Speech Recognition & Synthesis from Google\"",
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            ),
        )
    }

    /** One UI (Samsung) specific steps that apps can't switch on by themselves. */
    val SAMSUNG_STEPS = listOf(
        "Settings › Apps › Jarvis › Battery › choose Unrestricted.",
        "Settings › Battery › Background usage limits › Never sleeping apps › add Jarvis.",
        "Settings › Apps › Jarvis › Notifications › Jarvis calls › Alert, with sound and pop-up on.",
        "Settings › Notifications › Lock screen notifications › Show content.",
        "If Do Not Disturb is on at night: Settings › Notifications › Do not disturb › App notifications › add Jarvis.",
    )
}
