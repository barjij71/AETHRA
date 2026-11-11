package org.aethra.assistant.aethra.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Voice screen shell.
 *
 * This screen is a UI placeholder only. It does not implement STT, TTS, or
 * wake phrase behavior. Those features require Core contracts (OpenCode) and
 * Android dangerous permissions (RECORD_AUDIO) that are intentionally not
 * added in Phase 1.
 *
 * The screen exists so the navigation graph has a defined destination for
 * future voice integration.
 */
@Composable
fun VoiceScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Text(
            text = stringResource(id = androidx.compose.ui.res.R.string.voice_not_implemented),
            style = MaterialTheme.typography.bodyLarge
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = androidx.compose.ui.res.R.string.voice_coming_soon),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun stringResource(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
