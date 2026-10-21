package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.app.ui.hud.HudScreen
import com.jarvis.app.ui.hud.HudViewModel
import com.jarvis.app.ui.settings.SettingsScreen
import com.jarvis.app.ui.settings.SettingsViewModel
import com.jarvis.app.ui.theme.JarvisTheme

private enum class Screen { HUD, SETTINGS }

class MainActivity : ComponentActivity() {
    private val hud: HudViewModel by viewModels()
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMissingPermissions()
        if (savedInstanceState == null) handleAction(intent)

        setContent {
            JarvisTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HUD) }
                BackHandler(enabled = screen != Screen.HUD) { screen = Screen.HUD }
                when (screen) {
                    Screen.HUD -> HudScreen(hud, onOpenSettings = { screen = Screen.SETTINGS })
                    Screen.SETTINGS -> SettingsScreen(viewModel<SettingsViewModel>(), onBack = { screen = Screen.HUD })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** "Missed call from Jarvis" -> tap -> hear the debrief right away. */
    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_DEBRIEF) hud.onDebrief()
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) askPermissions.launch(missing.toTypedArray())
    }

    companion object {
        const val ACTION_DEBRIEF = "com.jarvis.app.DEBRIEF"
    }
}

object BuildConfigInfo {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
}
