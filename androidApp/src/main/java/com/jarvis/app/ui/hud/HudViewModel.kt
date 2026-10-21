package com.jarvis.app.ui.hud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.container
import com.jarvis.app.voice.ConversationRunner
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.model.DayRecord
import com.jarvis.shared.model.JarvisSettings
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.model.ScheduledCall
import com.jarvis.shared.time.LogicalDay
import com.jarvis.shared.time.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the command-center panels show. */
data class Dashboard(
    val day: DayRecord? = null,
    val calls: List<ScheduledCall> = emptyList(),
    val settings: JarvisSettings = JarvisSettings(),
    val claudeConnected: Boolean = false,
    val offlineModelInstalled: Boolean = false,
)

/**
 * ViewModel = the screen's brain. It survives screen rotation, holds state,
 * and exposes it as StateFlows the UI simply observes (unidirectional data flow).
 */
class HudViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val runner = ConversationRunner(c.assistant, c.speechInput, c.voiceOutput, c.repository)
    private var talkJob: Job? = null
    private val refreshTrigger = MutableStateFlow(0)

    private val everyMinute = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val dashboard: StateFlow<Dashboard> = combine(c.repository.version, refreshTrigger, everyMinute) { _, _, _ -> }
        .map { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Dashboard())

    private suspend fun load(): Dashboard {
        val today = LogicalDay.of(SystemClock.now())
        return Dashboard(
            day = c.repository.day(today),
            calls = c.assistant.upcomingCalls(),
            settings = c.repository.settings(),
            claudeConnected = !c.secrets.claudeKey().isNullOrBlank(),
            offlineModelInstalled = c.onDeviceLlm.isInstalled(),
        )
    }

    /** Tap the reactor: start talking, or stop Jarvis mid-sentence. */
    fun onCoreTapped() {
        if (talkJob?.isActive == true) {
            stopTalking()
        } else {
            talkJob = viewModelScope.launch { runner.chat() }
        }
    }

    fun onTyped(text: String) {
        if (text.isBlank()) return
        stopTalking()
        talkJob = viewModelScope.launch { runner.chat(typed = text) }
    }

    fun onDebrief() {
        stopTalking()
        talkJob = viewModelScope.launch { runner.call(Occasion.NIGHT_DEBRIEF, "") }
    }

    fun togglePlanItem(index: Int) {
        viewModelScope.launch {
            c.repository.updateDay(LogicalDay.of(SystemClock.now())) { day ->
                val item = day.plan.getOrNull(index) ?: return@updateDay day
                val next = if (item.status == PlanStatus.DONE) PlanStatus.PENDING else PlanStatus.DONE
                day.copy(plan = day.plan.toMutableList().also { it[index] = item.copy(status = next) })
            }
        }
    }

    fun cancelCall(id: Int) {
        viewModelScope.launch {
            c.repository.removeCalls { it.id == id }
            c.scheduler.cancel(id)
        }
    }

    fun refresh() {
        refreshTrigger.value++
    }

    private fun stopTalking() {
        talkJob?.cancel()
        runner.stop()
    }

    override fun onCleared() {
        runner.stop()
    }
}
