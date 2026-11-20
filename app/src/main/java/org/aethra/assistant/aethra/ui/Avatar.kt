package org.aethra.assistant.aethra.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun AethraAvatar(
    visualMode: VisualMode,
    modifier: Modifier = Modifier
) {
    val animationsEnabled = visualMode == VisualMode.BALANCED

    var animationTick by remember { mutableIntStateOf(0) }

    if (animationsEnabled) {
        LaunchedEffect(visualMode) {
            while (true) {
                delay(800)
                animationTick = (animationTick + 1) % 100
            }
        }
    }

    Box(
        modifier = modifier.size(84.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sweep = if (animationsEnabled) animationTick * 3.6f else 0f

            drawArc(
                color = Color(0xFF7C4DFF),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = 4f)
            )

            drawCircle(
                color = Color(0xFFE1BEE7)
            )

            val pulseAlpha = if (animationsEnabled) {
                (0.5f + 0.5f * kotlin.math.sin(animationTick * 0.2f)).coerceIn(0f, 1f)
            } else {
                1.0f
            }
            drawCircle(
                color = Color(0xFF7C4DFF).copy(alpha = pulseAlpha),
                radius = 16f
            )
        }
    }
}

@Composable
fun AethraAvatarPreview(
    powerSaving: Boolean = false,
    modifier: Modifier = Modifier
) {
    val mode = if (powerSaving) VisualMode.POWER_SAVING else VisualMode.BALANCED
    AethraAvatar(visualMode = mode, modifier = modifier)
}
