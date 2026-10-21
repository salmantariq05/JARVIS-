package com.jarvis.app.ui.settings

import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.BuildConfigInfo
import com.jarvis.app.system.Reliability
import com.jarvis.app.ui.components.HudButton
import com.jarvis.app.ui.components.HudPanel
import com.jarvis.app.ui.components.hudBackground
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.shared.model.BrainMode
import com.jarvis.shared.time.SpokenTime
import kotlinx.datetime.LocalTime

private val SPEECH_LANGUAGES = listOf(
    "en-IN" to "English (India) · best for Roman Urdu/Pashto mixed in",
    "en-GB" to "English (UK)",
    "en-US" to "English (US)",
    "ur-PK" to "Urdu (Pakistan) · when you mostly speak Urdu",
    "ps-AF" to "Pashto · limited support on most phones",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(ui.toast) {
        ui.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.toastShown()
        }
    }
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }
    fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(LocalTime(h, m)) }, current.hour, current.minute, false).show()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .hudBackground()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudButton("‹ BACK", onBack)
                Spacer(Modifier.width(12.dp))
                Text("SYSTEM SETUP", color = JarvisColors.Cyan, style = MaterialTheme.typography.titleLarge, letterSpacing = 3.sp)
            }
        }

        item {
            HudPanel("BRAIN", Modifier.fillMaxWidth()) {
                Text("Which brain answers you?", color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrainMode.entries.forEach { mode ->
                        Choice(mode.name, ui.settings.brainMode == mode, Modifier.weight(1f)) { vm.setBrainMode(mode) }
                    }
                }
                Hint(
                    "AUTO: Claude when online with a key, offline otherwise (recommended).\n" +
                        "CLAUDE: always try Claude first. OFFLINE: never use the internet.",
                )
                Spacer(Modifier.height(12.dp))
                ClaudeKeyEditor(ui, vm)
            }
        }

        item {
            HudPanel("OFFLINE AI MODEL", Modifier.fillMaxWidth(), accent = JarvisColors.Green) {
                Text(
                    if (ui.modelInstalled) "Installed · ${ui.modelSizeMb} MB" else "Not installed (commands still work offline)",
                    color = if (ui.modelInstalled) JarvisColors.Green else JarvisColors.TextDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ui.importProgress?.let { Text("Importing ${(it * 100).toInt()}%", color = JarvisColors.Amber, style = MaterialTheme.typography.bodySmall) }
                ui.modelStatus?.let { Text(it, color = JarvisColors.Text, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudButton("IMPORT .litertlm", { pickModel.launch(arrayOf("*/*")) }, accent = JarvisColors.Green)
                    if (ui.modelInstalled) HudButton("REMOVE", vm::removeModel, accent = JarvisColors.Red)
                }
                Hint(
                    "Download a LiteRT-LM model on your phone first, for example Gemma 3 1B (about 0.5 GB) " +
                        "from huggingface.co/litert-community, then import it here. See the README for the exact link.",
                )
            }
        }

        item {
            HudPanel("DAILY CALLS", Modifier.fillMaxWidth(), accent = JarvisColors.Amber) {
                CallRow(
                    title = "Night debrief",
                    time = ui.settings.nightDebriefTime,
                    enabled = ui.settings.nightDebriefEnabled,
                    onTime = { pickTime(ui.settings.nightDebriefTime) { t -> vm.setNightCall(time = t) } },
                    onToggle = { vm.setNightCall(enabled = it) },
                )
                CallRow(
                    title = "Morning plan",
                    time = ui.settings.morningCallTime,
                    enabled = ui.settings.morningCallEnabled,
                    onTime = { pickTime(ui.settings.morningCallTime) { t -> vm.setMorningCall(time = t) } },
                    onToggle = { vm.setMorningCall(enabled = it) },
                )
                Spacer(Modifier.height(8.dp))
                HudButton("RING ME IN 10 SECONDS (TEST)", vm::testCall, Modifier.fillMaxWidth(), accent = JarvisColors.Amber)
            }
        }

        item {
            HudPanel("VOICE & LANGUAGE", Modifier.fillMaxWidth()) {
                var name by rememberSaveable(ui.settings.addressAs) { mutableStateOf(ui.settings.addressAs) }
                Field("Jarvis calls you", name, { name = it })
                HudButton("SAVE NAME", { vm.setAddressAs(name) })
                Spacer(Modifier.height(12.dp))
                Text("Speech recognition language", color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
                SPEECH_LANGUAGES.forEach { (tag, label) ->
                    Choice(label, ui.settings.speechLanguage == tag, Modifier.fillMaxWidth().padding(top = 6.dp)) { vm.setSpeechLanguage(tag) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Jarvis's voice (tap to hear it)", color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
                if (ui.voices.isEmpty()) Hint("No offline English voices found. Install them in Settings › Text-to-speech.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.voices.take(16).forEach { v ->
                        Choice(v, ui.settings.voiceName == v) { vm.setVoice(v) }
                    }
                }
            }
        }

        item {
            HudPanel("RELIABILITY · SAMSUNG", Modifier.fillMaxWidth(), accent = JarvisColors.Red) {
                ui.checks.forEach { check ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(if (check.ok) "✓" else "✗", color = if (check.ok) JarvisColors.Green else JarvisColors.Red, fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(check.title, color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
                            Text(check.detail, color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!check.ok) {
                            HudButton("FIX", { runCatching { context.startActivity(check.fix) } }, accent = JarvisColors.Amber)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Also do these once on your Samsung:", color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
                Reliability.SAMSUNG_STEPS.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        item {
            Text(
                "Jarvis ${BuildConfigInfo.versionName(context)} · Phase 1",
                color = JarvisColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ClaudeKeyEditor(ui: SettingsUi, vm: SettingsViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    Text(
        if (ui.hasClaudeKey) "Claude API key: saved (encrypted on this phone)" else "Claude API key: not set",
        color = if (ui.hasClaudeKey) JarvisColors.Green else JarvisColors.Amber,
        style = MaterialTheme.typography.bodyMedium,
    )
    Field("Paste a new key (sk-ant-…)", key, { key = it }, secret = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HudButton("SAVE", {
            vm.saveClaudeKey(key)
            key = ""
        }, enabled = key.isNotBlank())
        HudButton(if (ui.testingClaude) "TESTING…" else "TEST", vm::testClaude, enabled = ui.hasClaudeKey && !ui.testingClaude, accent = JarvisColors.Green)
        if (ui.hasClaudeKey) HudButton("REMOVE", vm::clearClaudeKey, accent = JarvisColors.Red)
    }
    ui.claudeStatus?.let { Text(it, color = JarvisColors.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    Hint(
        "Get a key at console.anthropic.com › API keys. The API is billed separately from a Claude.ai subscription; " +
            "add a small prepaid credit and set a monthly spend limit there.",
    )
}

@Composable
private fun CallRow(title: String, time: LocalTime, enabled: Boolean, onTime: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
            Text(
                SpokenTime.time(time) + " · tap to change",
                color = JarvisColors.Cyan,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable(onClick = onTime),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = JarvisColors.Cyan, checkedThumbColor = JarvisColors.Background),
        )
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    HudButton(
        text = (if (selected) "● " else "○ ") + text,
        onClick = onClick,
        modifier = modifier,
        accent = if (selected) JarvisColors.Cyan else JarvisColors.TextDim,
    )
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisColors.Cyan,
            unfocusedBorderColor = JarvisColors.PanelBorder,
            focusedTextColor = JarvisColors.Text,
            unfocusedTextColor = JarvisColors.Text,
            focusedLabelColor = JarvisColors.Cyan,
            unfocusedLabelColor = JarvisColors.TextDim,
            cursorColor = JarvisColors.Cyan,
        ),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Normal, modifier = Modifier.padding(top = 6.dp))
}
