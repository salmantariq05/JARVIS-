package com.jarvis.app.calls

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jarvis.app.container
import com.jarvis.app.ui.components.ArcReactor
import com.jarvis.app.ui.components.VoiceWave
import com.jarvis.app.ui.components.hudBackground
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.app.voice.ConversationRunner
import com.jarvis.app.voice.Phase
import com.jarvis.app.voice.TalkState
import com.jarvis.shared.brain.Occasion
import com.jarvis.shared.model.CallKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The full-screen "Incoming call: J.A.R.V.I.S" screen, shown even on the lock screen. */
class IncomingCallActivity : ComponentActivity() {
    private val viewModel: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            JarvisTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val talk by viewModel.runner.state.collectAsStateWithLifecycle()
                val level by viewModel.runner.level.collectAsStateWithLifecycle()
                val partial by viewModel.runner.partial.collectAsStateWithLifecycle()
                LaunchedEffect(ui.finished) { if (ui.finished) finish() }
                CallScreen(
                    ui = ui,
                    talk = talk,
                    level = level,
                    partial = partial,
                    onAnswer = viewModel::answer,
                    onDecline = viewModel::decline,
                    onHangUp = viewModel::hangUp,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        viewModel.start(CallArgs.from(intent))
        if (intent.getBooleanExtra(EXTRA_ANSWERED, false)) viewModel.answer()
    }

    companion object {
        private const val EXTRA_ANSWERED = "answered"

        fun intent(context: Context, args: CallArgs, answered: Boolean): Intent =
            args.putInto(Intent(context, IncomingCallActivity::class.java))
                .putExtra(EXTRA_ANSWERED, answered)
    }
}

enum class CallStage { RINGING, CONNECTED, ENDED }

data class CallUi(
    val args: CallArgs? = null,
    val stage: CallStage = CallStage.RINGING,
    val seconds: Int = 0,
    val finished: Boolean = false,
)

class CallViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    val runner = ConversationRunner(container.assistant, container.speechInput, container.voiceOutput, container.repository)

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var ringTimeout: Job? = null
    private var callJob: Job? = null
    private var timerJob: Job? = null

    fun start(args: CallArgs) {
        if (_ui.value.args != null) return // already ringing (screen rotated or re-opened)
        _ui.value = CallUi(args)
        ringTimeout = viewModelScope.launch {
            delay(CallNotifier.RING_TIMEOUT_MS)
            if (_ui.value.stage == CallStage.RINGING) {
                CallNotifier.stopRinging(getApplication())
                CallNotifier.missed(getApplication(), args)
                _ui.update { it.copy(finished = true) }
            }
        }
    }

    fun answer() {
        val args = _ui.value.args ?: return
        if (_ui.value.stage != CallStage.RINGING) return
        ringTimeout?.cancel()
        CallNotifier.stopRinging(getApplication())
        _ui.update { it.copy(stage = CallStage.CONNECTED) }
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1_000)
                _ui.update { it.copy(seconds = it.seconds + 1) }
            }
        }
        callJob = viewModelScope.launch {
            runner.call(occasionFor(args.kind), args.label)
            endCall()
        }
    }

    fun decline() {
        ringTimeout?.cancel()
        CallNotifier.stopRinging(getApplication())
        _ui.update { it.copy(finished = true) }
    }

    fun hangUp() {
        callJob?.cancel()
        runner.stop()
        endCall()
    }

    private fun endCall() {
        timerJob?.cancel()
        _ui.update { it.copy(stage = CallStage.ENDED) }
        viewModelScope.launch {
            delay(1_200)
            _ui.update { it.copy(finished = true) }
        }
    }

    override fun onCleared() {
        runner.stop()
    }

    private fun occasionFor(kind: CallKind) = when (kind) {
        CallKind.ALARM -> Occasion.ALARM_CALL
        CallKind.MORNING_PLAN -> Occasion.MORNING_PLAN
        CallKind.NIGHT_DEBRIEF -> Occasion.NIGHT_DEBRIEF
    }
}

@Composable
private fun CallScreen(
    ui: CallUi,
    talk: TalkState,
    level: Float,
    partial: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
) {
    val args = ui.args
    Column(
        Modifier
            .fillMaxSize()
            .hudBackground()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (ui.stage) {
                CallStage.RINGING -> "INCOMING CALL"
                CallStage.CONNECTED -> "CONNECTED · %02d:%02d".format(ui.seconds / 60, ui.seconds % 60)
                CallStage.ENDED -> "CALL ENDED"
            },
            color = JarvisColors.TextDim,
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = args?.title() ?: "J.A.R.V.I.S",
            color = JarvisColors.Cyan,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = args?.subtitle().orEmpty(),
            color = JarvisColors.Text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        val phase = if (ui.stage == CallStage.CONNECTED) talk.phase else Phase.IDLE
        ArcReactor(phase = phase, level = level, modifier = Modifier.fillMaxWidth(0.72f))
        VoiceWave(phase = phase, level = level, modifier = Modifier.fillMaxWidth().height(48.dp))

        if (ui.stage == CallStage.CONNECTED) {
            talk.brain?.let { Text(it.label, color = JarvisColors.TextDim, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                val you = if (talk.phase == Phase.LISTENING) partial else talk.heard
                if (you.isNotBlank()) Text("YOU › $you", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodyMedium)
                if (talk.reply.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("JARVIS › ${talk.reply}", color = JarvisColors.Text, style = MaterialTheme.typography.bodyLarge)
                }
                talk.error?.let { Text("⚠ $it", color = JarvisColors.Amber, style = MaterialTheme.typography.bodySmall) }
            }
            RoundButton("END", JarvisColors.Red, onHangUp)
        } else {
            Spacer(Modifier.weight(1f))
            if (ui.stage == CallStage.RINGING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton("DECLINE", JarvisColors.Red, onDecline)
                    RoundButton("ANSWER", JarvisColors.Green, onAnswer)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RoundButton(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .background(color.copy(alpha = 0.18f), CircleShape)
                .border(2.dp, color, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (color == JarvisColors.Green) "✆" else "✕", color = color, fontSize = 30.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
    }
}
