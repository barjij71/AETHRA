package org.aethra.assistant.aethra.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawArc
import androidx.compose.ui.graphics.drawscope.drawCircle
import androidx.compose.ui.graphics.drawscope.drawOval
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Mutex

/**
 * Reusable Compose avatar component for AETHRA.
 *
 * Designed as a female AETHRA visual identity with balanced visual effects
 * by default. Animation can be enabled or disabled. Power-saving mode reduces
 * or disables expensive effects without blocking UI responsiveness.
 *
 * This component has no Core dependencies. It is purely an Android UI layer.
 *
 * @param visualMode current visual mode (balanced or power-saving)
 * @param modifier Compose modifier
 */
@Composable
fun AethraAvatar(
    visualMode: VisualMode,
    modifier: Modifier = Modifier
) {
    val size = 84.dp
    val animationsEnabled = visualMode == VisualMode.BALANCED

    // Use a remembered animation tick that only advances when animations
    // are enabled. In power-saving mode the tick stays static, which
    // freezes any animated effects without recomposition churn.
    var animationTick by remember { mutableIntStateOf(0) }

    if (animationsEnabled) {
        // Simple rotating pulse: advances periodically, then resets.
        // LaunchedEffect is scoped to this composition; it does not block
        // the UI thread because it uses delay() inside a coroutine.
        androidx.compose.runtime.LaunchedEffect(visualMode) {
            while (true) {
                delay(800)
                animationTick = (animationTick + 1) % 100
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .align(Alignment.Center)
    ) {
        // Outer subtle ring: animates only in balanced mode.
        val ringSweep = if (animationsEnabled) (animationTick * 3.6f) else 0f
        drawArc(
            color = Color(0xFF7C4DFF),
            startAngle = -90f,
            sweepAngle = ringSweep,
            useCenter = false,
            topLeft = Offset(4.dp, 4.dp),
            size = size - 8.dp,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp)
        )

        // Inner circle: solid fill, female AETHRA identity color.
        drawCircle(
            color = SolidColor(Color(0xFFE1BEE7)),
            size = size - 16.dp,
            topLeft = Offset(8.dp, 8.dp)
        )

        // Inner accent dot that pulses only in balanced mode.
        val pulseAlpha = if (animationsEnabled) {
            (0.5f + 0.5f * kotlin.math.sin(animationTick * 0.2f)).coerceIn(0f, 1f)
        } else {
            1.0f
        }
        drawCircle(
            color = Color(0xFF7C4DFF).copy(alpha = pulseAlpha),
            size = size - 56.dp,
            topLeft = Offset(28.dp, 28.dp)
        )
    }
}

/**
 * Stateless preview-friendly wrapper that accepts an explicit mode.
 * Use [AethraAvatar] with [VisualModeState] for stateful use.
 */
@Composable
fun AethraAvatarPreview(
    powerSaving: Boolean = false,
    modifier: Modifier = Modifier
) {
    val mode = if (powerSaving) VisualMode.POWER_SAVING else VisualMode.BALANCED
    AethraAvatar(visualMode = mode, modifier = modifier)
}
