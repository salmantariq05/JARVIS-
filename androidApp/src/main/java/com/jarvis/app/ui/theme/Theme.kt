package com.jarvis.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** Iron Man HUD palette: deep space background, holographic cyan, warning amber. */
object JarvisColors {
    val Background = Color(0xFF02070D)
    val Panel = Color(0xCC061423)
    val PanelBorder = Color(0x5522E6FF)
    val Cyan = Color(0xFF22E6FF)
    val CyanSoft = Color(0xFF7FF3FF)
    val Amber = Color(0xFFFFB648)
    val Red = Color(0xFFFF4D6D)
    val Green = Color(0xFF3DFFA8)
    val Text = Color(0xFFD8F6FF)
    val TextDim = Color(0xFF6FA7B5)
}

private val Mono = FontFamily.Monospace

private fun TextStyle.mono() = copy(fontFamily = Mono)

private val JarvisTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.mono(),
        headlineSmall = t.headlineSmall.mono(),
        titleLarge = t.titleLarge.mono(),
        titleMedium = t.titleMedium.mono(),
        titleSmall = t.titleSmall.mono(),
        bodyLarge = t.bodyLarge.mono(),
        bodyMedium = t.bodyMedium.mono(),
        bodySmall = t.bodySmall.mono(),
        labelLarge = t.labelLarge.mono(),
        labelMedium = t.labelMedium.mono(),
        labelSmall = t.labelSmall.mono(),
    )
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JarvisColors.Cyan,
            onPrimary = JarvisColors.Background,
            secondary = JarvisColors.Amber,
            onSecondary = JarvisColors.Background,
            background = JarvisColors.Background,
            onBackground = JarvisColors.Text,
            surface = JarvisColors.Background,
            onSurface = JarvisColors.Text,
            surfaceVariant = Color(0xFF0A1E30),
            onSurfaceVariant = JarvisColors.TextDim,
            outline = JarvisColors.PanelBorder,
            error = JarvisColors.Red,
        ),
        typography = JarvisTypography,
        content = content,
    )
}
