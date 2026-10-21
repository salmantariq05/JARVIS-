package com.jarvis.app.calls

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.app.MainActivity
import com.jarvis.shared.CallScheduler
import com.jarvis.shared.model.ScheduledCall
import kotlinx.datetime.toJavaLocalDateTime
import java.time.ZoneId

/**
 * Arms Android's alarm clock. setAlarmClock() is the most reliable alarm type:
 * it fires at the exact minute even in Doze / battery saver, and Android shows
 * the alarm icon in the status bar.
 */
class AndroidCallScheduler(private val context: Context) : CallScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(call: ScheduledCall) {
        val triggerAt = call.at.toJavaLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val operation = PendingIntent.getBroadcast(
            context,
            call.id,
            CallAlarmReceiver.intent(context, call),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (canScheduleExact()) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showApp), operation)
            } else {
                // Without the exact-alarm permission we can still ring, just up to a few minutes late.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    override fun cancel(callId: Int) {
        val intent = Intent(context, CallAlarmReceiver::class.java).setData(dataUri(callId))
        PendingIntent.getBroadcast(context, callId, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
    }

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    companion object {
        /** A unique URI per call, so Android keeps one alarm per call ID. */
        fun dataUri(callId: Int): Uri = Uri.parse("jarvis://call/$callId")
    }
}
