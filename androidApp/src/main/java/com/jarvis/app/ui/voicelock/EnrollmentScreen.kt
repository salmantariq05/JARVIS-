package com.jarvis.app.ui.voicelock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.ui.components.ArcReactor
import com.jarvis.app.ui.components.HudButton
import com.jarvis.app.ui.components.HudPanel
import com.jarvis.app.ui.components.VoiceWave
import com.jarvis.app.ui.components.hudBackground
import com.jarvis.app.ui.theme.JarvisColors
import com.jarvis.app.voice.Phase
import com.jarvis.shared.voice.VoiceProfile

@Composable
fun EnrollmentScreen(vm: EnrollmentViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy = ui.stage == EnrollStage.RECORDING || ui.stage == EnrollStage.TESTING
    Column(
        Modifier
            .fillMaxSize()
            .hudBackground()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HudButton("‹ BACK", {
                vm.cancel()
                onBack()
            })
            Spacer(Modifier.width(12.dp))
            Text("VOICE LOCK", color = JarvisColors.Cyan, style = MaterialTheme.typography.titleLarge, letterSpacing = 3.sp)
        }

        ArcReactor(
            phase = if (busy) Phase.LISTENING else if (ui.stage == EnrollStage.DONE) Phase.SPEAKING else Phase.IDLE,
            level = ui.level,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
        VoiceWave(if (busy) Phase.LISTENING else Phase.IDLE, ui.level, Modifier.fillMaxWidth().height(36.dp))

        if (ui.stage == EnrollStage.RECORDING || ui.stage == EnrollStage.DONE && ui.testScore == null) {
            SampleDots(ui.samples)
        }
        Text(ui.message, color = JarvisColors.Text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        ui.testScore?.let { score ->
            Text(
                "Match score %.2f".format(score),
                color = if (ui.testPassed == true) JarvisColors.Green else JarvisColors.Amber,
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        when (ui.stage) {
            EnrollStage.NEED_MODEL, EnrollStage.ERROR -> HudButton("DOWNLOAD VOICE MODEL (40 MB)", vm::downloadModel, Modifier.fillMaxWidth())
            EnrollStage.DOWNLOADING -> Text("${(ui.downloadProgress * 100).toInt()}%", color = JarvisColors.Amber, style = MaterialTheme.typography.headlineSmall)
            EnrollStage.READY -> HudButton("START RECORDING", vm::startEnrollment, Modifier.fillMaxWidth(), accent = JarvisColors.Green)
            EnrollStage.RECORDING, EnrollStage.TESTING -> HudButton("CANCEL", vm::cancel, Modifier.fillMaxWidth(), accent = JarvisColors.Red)
            EnrollStage.DONE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton("TEST MY VOICE", vm::testVoice, Modifier.weight(1f), accent = JarvisColors.Green)
                HudButton("RE-RECORD", vm::startEnrollment, Modifier.weight(1f), accent = JarvisColors.Amber)
            }
        }

        HudPanel("HOW IT WORKS", Modifier.fillMaxWidth(), accent = JarvisColors.TextDim) {
            listOf(
                "Say \"Hey Jarvis\" ${VoiceProfile.SAMPLES_NEEDED} times, in your normal voice, about 30 cm from the phone.",
                "Each sample becomes a 192-number voiceprint. Jarvis averages them into your profile.",
                "Later, every \"Hey Jarvis\" is compared with your profile. Other voices are ignored.",
                "Your voiceprint stays on this phone. It is never uploaded.",
                "Voice lock is a convenience, not a password: a recording of you could fool it.",
            ).forEach { Text("• $it", color = JarvisColors.TextDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

@Composable
private fun SampleDots(done: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(VoiceProfile.SAMPLES_NEEDED) { i ->
            Text(if (i < done) "●" else "○", color = if (i < done) JarvisColors.Green else JarvisColors.TextDim, fontSize = 22.sp)
        }
    }
}
