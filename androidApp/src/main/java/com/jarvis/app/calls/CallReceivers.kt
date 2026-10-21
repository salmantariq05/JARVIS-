package com.jarvis.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.app.container
import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.ScheduledCall
import kotlinx.coroutines.launch

/** Wakes up when an alarm fires, and makes the phone ring. */
class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { CallKind.valueOf(it) }.getOrNull() } ?: return
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        CallNotifier.ring(context, CallArgs(id, kind, label))

        // goAsync() lets us finish async work (saving, re-arming tomorrow's call) after onReceive returns.
        val pending = goAsync()
        val container = context.container
        container.appScope.launch {
            try {
                container.assistant.onCallFired(id, kind)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "call_id"
        const val EXTRA_KIND = "call_kind"
        const val EXTRA_LABEL = "call_label"

        fun intent(context: Context, call: ScheduledCall): Intent =
            Intent(context, CallAlarmReceiver::class.java)
                .setData(AndroidCallScheduler.dataUri(call.id))
                .putExtra(EXTRA_ID, call.id)
                .putExtra(EXTRA_KIND, call.kind.name)
                .putExtra(EXTRA_LABEL, call.label)
    }
}

/** "Decline" button on the ringing notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DECLINE) CallNotifier.stopRinging(context)
    }

    companion object {
        const val ACTION_DECLINE = "com.jarvis.app.DECLINE_CALL"
    }
}

/** Alarms are wiped when the phone restarts or the clock changes, so re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.appScope.launch {
            try {
                container.assistant.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
