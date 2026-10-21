package com.jarvis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.ui.theme.JarvisColors

private val PanelShape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp)

/** A holographic panel with a cut-corner frame and a small caps title. */
@Composable
fun HudPanel(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = JarvisColors.Cyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(JarvisColors.Panel, PanelShape)
            .border(1.dp, accent.copy(alpha = 0.45f), PanelShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "◢ $title",
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** A HUD-style button: outlined, glowing when pressed-capable, monospace caps. */
@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = JarvisColors.Cyan,
    enabled: Boolean = true,
) {
    val color = if (enabled) accent else JarvisColors.TextDim
    Box(
        modifier
            .background(color.copy(alpha = 0.08f), PanelShape)
            .border(1.dp, color.copy(alpha = 0.7f), PanelShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    }
}

/** Faint holographic grid + vignette behind every screen. */
fun Modifier.hudBackground(): Modifier = this
    .background(JarvisColors.Background)
    .drawBehind {
        val step = 28.dp.toPx()
        val line = JarvisColors.Cyan.copy(alpha = 0.05f)
        var x = 0f
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, JarvisColors.Background.copy(alpha = 0.85f)),
                center = Offset(size.width / 2, size.height * 0.3f),
                radius = size.maxDimension * 0.8f,
            ),
        )
    }
