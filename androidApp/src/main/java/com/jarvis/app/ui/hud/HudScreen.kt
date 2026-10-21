package com.jarvis.app.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.ui.components.ArcReactor
import com.jarvis.app.ui.components.HudButton
import com.jarvis.app.ui.components.HudPanel
import com.jarvis.app.ui.components.VoiceWave
import com.jarvis.app.ui.components.hudBackground
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.voice.Phase
import com.jarvis.app.voice.TalkState
import com.jarvis.shared.model.CallKind
import com.jarvis.shared.model.LogCategory
import com.jarvis.shared.model.PlanStatus
import com.jarvis.shared.time.SpokenTime
import com.jarvis.shared.time.SystemClock
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun HudScreen(vm: HudViewModel, onOpenSettings: () -> Unit) {
    val talk by vm.runner.state.collectAsStateWithLifecycle()
    val level by vm.runner.level.collectAsStateWithLifecycle()
    val partial by vm.runner.partial.collectAsStateWithLifecycle()
    val dash by vm.dashboard.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .hudBackground()
            .safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(talk, dash) }
        item { ReactorBlock(talk, level, onTap = vm::onCoreTapped) }
        item { TranscriptPanel(talk, partial) }
        item { CommandInput(onSend = vm::onTyped) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton(if (talk.phase == Phase.IDLE) "TALK" else "STOP", vm::onCoreTapped, Modifier.weight(1f))
                HudButton("DEBRIEF", vm::onDebrief, Modifier.weight(1f), accent = JarvisColors.Amber)
                HudButton("SETUP", onOpenSettings, Modifier.weight(1f), accent = JarvisColors.TextDim)
            }
        }
        item { SectionTitle("COMMAND CENTER") }
        item { StatRow(dash) }
        item { PlanPanel(dash, vm::togglePlanItem) }
        item { LogPanel(dash) }
        item { CallsPanel(dash, vm::cancelCall) }
        item { RoadmapPanel() }
    }
}

@Composable
private fun Header(talk: TalkState, dash: Dashboard) {
    val clock by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("J.A.R.V.I.S", color = JarvisColors.Cyan, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("JUST A RATHER VERY INTELLIGENT SYSTEM", color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clock.format(DateTimeFormatter.ofPattern("HH:mm:ss")), color = JarvisColors.Text, style = MaterialTheme.typography.titleMedium)
            val brain = talk.brain?.label ?: when {
                dash.settings.brainMode.name == "OFFLINE" -> "OFFLINE MODE"
                dash.claudeConnected -> "CLAUDE ONLINE"
                else -> "OFFLINE · ADD KEY IN SETUP"
            }
            Text(brain, color = if (brain.startsWith("CLAUDE")) JarvisColors.Green else JarvisColors.Amber, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ReactorBlock(talk: TalkState, level: Float, onTap: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth(0.78f)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        ) {
            ArcReactor(talk.phase, level, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (talk.phase) {
                Phase.IDLE -> "TAP THE CORE TO SPEAK"
                Phase.LISTENING -> "LISTENING…"
                Phase.THINKING -> "PROCESSING…"
                Phase.SPEAKING -> "SPEAKING · TAP TO INTERRUPT"
            },
            color = JarvisColors.TextDim,
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 3.sp,
        )
        VoiceWave(talk.phase, level, Modifier.fillMaxWidth().height(40.dp))
    }
}

@Composable
private fun TranscriptPanel(talk: TalkState, partial: String) {
    HudPanel("TRANSCRIPT", Modifier.fillMaxWidth()) {
        val you = if (talk.phase == Phase.LISTENING) partial else talk.heard
        if (you.isBlank() && talk.reply.isBlank()) {
            Text(
                "Try saying:\n• \"My plan for today is DSA, OS assignment and gym\"\n• \"Call me at 7 AM tomorrow\"\n" +
                    "• \"I studied recursion for 2 hours\"\n• \"Kal subah saat baje utha dena\"\n• \"Explain Big-O like I'm five\"",
                color = JarvisColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (you.isNotBlank()) Text("YOU › $you", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodyMedium)
        if (talk.reply.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("JARVIS › ${talk.reply}", color = JarvisColors.Text, style = MaterialTheme.typography.bodyMedium)
        }
        talk.performed.forEach { Text("✓ $it", color = JarvisColors.Green, style = MaterialTheme.typography.labelMedium) }
        talk.error?.let { Text("⚠ $it", color = JarvisColors.Amber, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CommandInput(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command…", color = JarvisColors.TextDim) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JarvisColors.Cyan,
                unfocusedBorderColor = JarvisColors.PanelBorder,
                focusedTextColor = JarvisColors.Text,
                unfocusedTextColor = JarvisColors.Text,
                cursorColor = JarvisColors.Cyan,
            ),
        )
        Spacer(Modifier.width(8.dp))
        HudButton("SEND", { send() })
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(18.dp).height(1.dp).background(JarvisColors.Cyan))
        Text("  $title  ", color = JarvisColors.Cyan, style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
        Box(Modifier.weight(1f).height(1.dp).background(JarvisColors.PanelBorder))
    }
}

@Composable
private fun StatRow(dash: Dashboard) {
    val day = dash.day
    val planned = day?.plan?.size ?: 0
    val done = day?.doneCount ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HudPanel("TODAY", Modifier.weight(1f)) {
            Text(if (planned == 0) "NO PLAN" else "$done / $planned", color = JarvisColors.Text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            ProgressBar(if (planned == 0) 0f else done.toFloat() / planned)
        }
        HudPanel("STUDY", Modifier.weight(1f), accent = JarvisColors.Green) {
            val minutes = day?.studyMinutes ?: 0
            Text(if (minutes == 0) "0 MIN" else SpokenTime.duration(minutes).uppercase(), color = JarvisColors.Text, style = MaterialTheme.typography.titleMedium)
            Text("${day?.entries?.size ?: 0} log entries", color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(JarvisColors.PanelBorder),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(JarvisColors.Cyan),
        )
    }
}

@Composable
private fun PlanPanel(dash: Dashboard, onToggle: (Int) -> Unit) {
    HudPanel("TODAY'S PLAN · TAP TO TICK", Modifier.fillMaxWidth()) {
        val plan = dash.day?.plan.orEmpty()
        if (plan.isEmpty()) {
            Text("No plan yet. Say \"my plan for today is…\" or wait for the morning call.", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        plan.forEachIndexed { i, item ->
            val (mark, color) = when (item.status) {
                PlanStatus.DONE -> "■" to JarvisColors.Green
                PlanStatus.PARTIAL -> "◧" to JarvisColors.Amber
                PlanStatus.SKIPPED -> "✕" to JarvisColors.TextDim
                PlanStatus.PENDING -> "□" to JarvisColors.Cyan
            }
            Text(
                "$mark  ${item.title}",
                color = color,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(i) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun LogPanel(dash: Dashboard) {
    HudPanel("TODAY'S LOG", Modifier.fillMaxWidth(), accent = JarvisColors.Green) {
        val entries = dash.day?.entries.orEmpty().asReversed()
        if (entries.isEmpty()) {
            Text("Nothing logged yet. Tell Jarvis what you studied or finished.", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        entries.take(12).forEach { e ->
            val color = when (e.category) {
                LogCategory.PROBLEM -> JarvisColors.Amber
                LogCategory.WIN -> JarvisColors.Green
                else -> JarvisColors.Text
            }
            val extra = listOfNotNull(e.subject, e.minutes?.let { "${it}m" }).joinToString(" · ")
            Text(
                "${e.time}  ${e.category.name.take(5).padEnd(5)}  ${e.text}" + (if (extra.isNotEmpty()) "  [$extra]" else ""),
                color = color,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun CallsPanel(dash: Dashboard, onCancel: (Int) -> Unit) {
    HudPanel("SCHEDULED CALLS", Modifier.fillMaxWidth(), accent = JarvisColors.Amber) {
        if (dash.calls.isEmpty()) {
            Text("No calls scheduled. Say \"call me at 7 AM\".", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        val now = SystemClock.now()
        dash.calls.take(10).forEach { call ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                val what = when (call.kind) {
                    CallKind.NIGHT_DEBRIEF -> "Night debrief"
                    CallKind.MORNING_PLAN -> "Morning plan"
                    CallKind.ALARM -> call.label.ifBlank { "Call" }
                }
                Text(
                    "${SpokenTime.dateTime(call.at, now)} · $what",
                    color = JarvisColors.Text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (call.kind == CallKind.ALARM) {
                    Text(
                        "✕",
                        color = JarvisColors.Red,
                        modifier = Modifier
                            .clickable { onCancel(call.id) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoadmapPanel() {
    HudPanel("COMING ONLINE", Modifier.fillMaxWidth(), accent = JarvisColors.TextDim) {
        listOf(
            "PHASE 2" to "\"Hey Jarvis\" wake word + voice lock (only you)",
            "PHASE 3" to "WhatsApp · Instagram · Snapchat · Telegram · Gmail · Outlook",
            "PHASE 4" to "Study cards, quizzes, focus mode, Classroom deadlines",
            "PHASE 5" to "Morning briefing, phone control, developer mode, iPhone",
        ).forEach { (phase, text) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("🔒 $phase ", color = JarvisColors.Amber, style = MaterialTheme.typography.labelSmall)
                Text(text, color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Start)
            }
        }
    }
}
