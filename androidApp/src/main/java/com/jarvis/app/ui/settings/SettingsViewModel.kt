package com.jarvis.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.container
import com.jarvis.app.system.Reliability
import com.jarvis.app.system.ReliabilityCheck
import com.jarvis.shared.brain.BrainContext
import com.jarvis.shared.brain.BrainRequest
import com.jarvis.shared.brain.Depth
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.model.BrainMode
import com.jarvis.shared.model.JarvisSettings
import com.jarvis.shared.time.LogicalDay
import com.jarvis.shared.time.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

data class SettingsUi(
    val settings: JarvisSettings = JarvisSettings(),
    val hasClaudeKey: Boolean = false,
    val claudeStatus: String? = null,
    val testingClaude: Boolean = false,
    val modelInstalled: Boolean = false,
    val modelSizeMb: Long = 0,
    val importProgress: Float? = null,
    val modelStatus: String? = null,
    val voices: List<String> = emptyList(),
    val checks: List<ReliabilityCheck> = emptyList(),
    val toast: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    settings = c.repository.settings(),
                    hasClaudeKey = !c.secrets.claudeKey().isNullOrBlank(),
                    modelInstalled = c.onDeviceLlm.isInstalled(),
                    modelSizeMb = c.onDeviceLlm.sizeMb(),
                    voices = c.voiceOutput.englishVoices(),
                    checks = Reliability.checks(getApplication()),
                )
            }
        }
    }

    fun setBrainMode(mode: BrainMode) = update { it.copy(brainMode = mode) }

    fun setAddressAs(name: String) = update { it.copy(addressAs = name.trim().ifEmpty { "sir" }) }

    fun setSpeechLanguage(tag: String) = update { it.copy(speechLanguage = tag) }

    fun setNightCall(time: LocalTime? = null, enabled: Boolean? = null) = update {
        it.copy(nightDebriefTime = time ?: it.nightDebriefTime, nightDebriefEnabled = enabled ?: it.nightDebriefEnabled)
    }

    fun setMorningCall(time: LocalTime? = null, enabled: Boolean? = null) = update {
        it.copy(morningCallTime = time ?: it.morningCallTime, morningCallEnabled = enabled ?: it.morningCallEnabled)
    }

    fun setVoice(name: String) {
        update { it.copy(voiceName = name) }
        viewModelScope.launch {
            c.voiceOutput.speak("Good evening. This is how I sound now.", name)
        }
    }

    fun saveClaudeKey(key: String) {
        viewModelScope.launch {
            c.secrets.setClaudeKey(key)
            _ui.update { it.copy(claudeStatus = "Key saved securely. Tap TEST to check it.") }
            refresh()
        }
    }

    fun clearClaudeKey() {
        viewModelScope.launch {
            c.secrets.setClaudeKey(null)
            _ui.update { it.copy(claudeStatus = "Key removed. Jarvis will use the offline brain.") }
            refresh()
        }
    }

    /** Sends one tiny request to Claude Haiku to prove the key works (costs a fraction of a cent). */
    fun testClaude() {
        viewModelScope.launch {
            _ui.update { it.copy(testingClaude = true, claudeStatus = "Contacting Claude…") }
            val now = SystemClock.now()
            val request = BrainRequest(
                userText = "This is a connection test. Reply with one short, witty sentence confirming you are online.",
                history = emptyList(),
                context = BrainContext(now, c.repository.day(LogicalDay.of(now)), emptyList(), c.repository.settings()),
                depth = Depth.QUICK,
                occasion = Occasion.CHAT,
            )
            val status = try {
                "✓ Claude says: " + c.claudeBrain.reply(request).speech
            } catch (e: Exception) {
                "✗ ${e.message}"
            }
            _ui.update { it.copy(testingClaude = false, claudeStatus = status) }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(importProgress = 0f, modelStatus = "Copying model…") }
            val status = try {
                c.onDeviceLlm.install(uri) { p -> _ui.update { it.copy(importProgress = p) } }
                "✓ Model installed. Offline questions now use on-device AI."
            } catch (e: Exception) {
                "✗ Import failed: ${e.message}"
            }
            _ui.update { it.copy(importProgress = null, modelStatus = status) }
            refresh()
        }
    }

    fun removeModel() {
        viewModelScope.launch {
            c.onDeviceLlm.remove()
            _ui.update { it.copy(modelStatus = "Model removed.") }
            refresh()
        }
    }

    fun testCall() {
        c.assistant.scheduleTestCall(secondsFromNow = 10)
        _ui.update { it.copy(toast = "Test call in 10 seconds. Lock your phone to see the full call screen!") }
    }

    fun toastShown() = _ui.update { it.copy(toast = null) }

    private fun update(change: (JarvisSettings) -> JarvisSettings) {
        viewModelScope.launch {
            c.repository.updateSettings(change)
            c.assistant.syncDailyCalls()
            refresh()
        }
    }
}
