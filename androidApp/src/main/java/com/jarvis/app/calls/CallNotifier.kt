package com.jarvis.app.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.jarvis.app.MainActivity
import com.jarvis.app.R
import com.jarvis.shared.model.CallKind

/** Who is calling and why. Travels inside Intents between the alarm, notification and call screen. */
data class CallArgs(val id: Int, val kind: CallKind, val label: String) {
    fun title(): String = when (kind) {
        CallKind.ALARM -> "J.A.R.V.I.S"
        CallKind.MORNING_PLAN -> "J.A.R.V.I.S · Morning plan"
        CallKind.NIGHT_DEBRIEF -> "J.A.R.V.I.S · Night debrief"
    }

    fun subtitle(): String = when (kind) {
        CallKind.ALARM -> label.ifBlank { "Your scheduled call" }
        CallKind.MORNING_PLAN -> "Let's plan your day"
        CallKind.NIGHT_DEBRIEF -> "Your day, summarised"
    }

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_KIND, kind.name)
        .putExtra(EXTRA_LABEL, label)

    companion object {
        private const val EXTRA_ID = "args_id"
        private const val EXTRA_KIND = "args_kind"
        private const val EXTRA_LABEL = "args_label"

        fun from(intent: Intent): CallArgs = CallArgs(
            id = intent.getIntExtra(EXTRA_ID, -1),
            kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { CallKind.valueOf(it) }.getOrNull() } ?: CallKind.ALARM,
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
        )
    }
}

/**
 * Makes the phone ring like a real incoming call: ringtone on loop, vibration,
 * and a full-screen call screen even when the phone is locked.
 */
object CallNotifier {
    private const val CHANNEL_CALLS = "jarvis_calls"
    private const val CHANNEL_MISSED = "jarvis_missed"
    private const val RING_ID = 7001
    private const val MISSED_ID = 7002
    const val RING_TIMEOUT_MS = 60_000L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val calls = NotificationChannel(CHANNEL_CALLS, "Jarvis calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarms, morning plan and night debrief calls"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 900, 700, 900, 700)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(CHANNEL_MISSED, "Missed Jarvis calls", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannels(listOf(calls, missed))
    }

    fun ring(context: Context, args: CallArgs) {
        if (!canNotify(context)) {
            // No notification permission: open the call screen directly as a best effort.
            runCatching { context.startActivity(IncomingCallActivity.intent(context, args, answered = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreen = PendingIntent.getActivity(context, 1, IncomingCallActivity.intent(context, args, answered = false), flags)
        val answer = PendingIntent.getActivity(context, 2, IncomingCallActivity.intent(context, args, answered = true), flags)
        val decline = PendingIntent.getBroadcast(
            context,
            3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            flags,
        )
        val jarvis = Person.Builder().setName("J.A.R.V.I.S").setImportant(true).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(args.title())
            .setContentText(args.subtitle())
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)

        val notification = try {
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(jarvis, decline, answer)).build()
        } catch (e: IllegalArgumentException) {
            // Some Android builds reject call-style notifications; plain buttons work everywhere.
            builder.setStyle(null)
                .addAction(0, "Decline", decline)
                .addAction(0, "Answer", answer)
                .build()
        }
        notification.flags = notification.flags or Notification.FLAG_INSISTENT // keep ringing until answered
        notify(context, RING_ID, notification)
    }

    fun stopRinging(context: Context) {
        NotificationManagerCompat.from(context).cancel(RING_ID)
    }

    fun missed(context: Context, args: CallArgs) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java)
                .setAction(if (args.kind == CallKind.NIGHT_DEBRIEF) MainActivity.ACTION_DEBRIEF else Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Missed call from Jarvis")
            .setContentText(if (args.kind == CallKind.NIGHT_DEBRIEF) "Tap to hear your night debrief" else args.subtitle())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, MISSED_ID, notification)
    }

    fun canUseFullScreen(context: Context): Boolean =
        NotificationManagerCompat.from(context).canUseFullScreenIntent()

    private fun canNotify(context: Context): Boolean {
        // The notification permission only exists from Android 13; before that it's always granted.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission was revoked between the check and now; nothing else we can do.
        }
    }
}
