package org.aethra.assistant.aethra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Android Settings screen.
 *
 * Contains only settings that can be represented safely in Phase 1:
 *   - language (UI configuration only)
 *   - theme (UI configuration only)
 *   - visual effects (animation on/off)
 *   - power-saving / degraded visual mode
 *   - voice UI preferences (UI configuration only; no STT/TTS/wake-word behavior)
 *
 * No fake STT/TTS/wake-word behavior is implemented. Voice preferences are
 * stored as UI-only flags for future voice integration.
 *
 * @param visualModeState shared visual mode state, consumed by the avatar
 * @param onNavigateHome callback to return to the home screen
 */
@Composable
fun SettingsScreen(
    visualModeState: VisualModeState,
    onNavigateHome: () -> Unit
) {
    var languageSelected by remember { mutableStateOf("en") }
    var themeSelected by remember { mutableStateOf("system") }
    var visualEffectsEnabled by remember { mutableStateOf(true) }
    var voiceUiEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(id = androidx.compose.ui.res.R.string.settings_title),
            style = MaterialTheme.typography.titleLarge
        )

        // Language (UI configuration only)
        RowSetting(
            title = stringResource(id = androidx.compose.ui.res.R.string.settings_language),
            value = languageSelected,
            onValueChange = { languageSelected = it },
            options = listOf("en" to "English", "ar" to "العربية")
        )

        // Theme (UI configuration only)
        RowSetting(
            title = stringResource(id = androidx.compose.ui.res.R.string.settings_theme),
            value = themeSelected,
            onValueChange = { themeSelected = it },
            options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        )

        // Visual effects (toggles animation; read by avatar)
        RowSetting(
            title = stringResource(id = androidx.compose.ui.res.R.string.settings_visual_effects),
            value = if (visualEffectsEnabled) settingEnabled else settingDisabled,
            onValueChange = {
                visualEffectsEnabled = it == settingEnabled
                visualModeState.setMode(
                    if (visualEffectsEnabled) VisualMode.BALANCED else VisualMode.POWER_SAVING
                )
            },
            options = listOf("enabled" to settingEnabled, "disabled" to settingDisabled)
        )

        // Power-saving / degraded visual mode
        RowSetting(
            title = stringResource(id = androidx.compose.ui.res.R.string.settings_power_saving),
            value = if (visualModeState.isPowerSaving()) settingEnabled else settingDisabled,
            onValueChange = {
                val powerSaving = it == settingEnabled
                visualModeState.setMode(
                    if (powerSaving) VisualMode.POWER_SAVING else VisualMode.BALANCED
                )
                visualEffectsEnabled = !powerSaving
            },
            options = listOf("enabled" to settingEnabled, "disabled" to settingDisabled)
        )

        // Voice UI preferences (UI configuration only)
        RowSetting(
            title = stringResource(id = androidx.compose.ui.res.R.string.settings_voice_ui),
            value = if (voiceUiEnabled) settingEnabled else settingDisabled,
            onValueChange = { voiceUiEnabled = it == settingEnabled },
            options = listOf("enabled" to settingEnabled, "disabled" to settingDisabled)
        )

        Text(
            text = "Voice UI preference is stored locally. STT/TTS/wake-word behavior is not yet implemented.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomEnd),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
        ) {
            androidx.compose.material3.Button(
                onClick = onNavigateHome
            ) {
                Text(text = stringResource(id = androidx.compose.ui.res.R.string.nav_home))
            }
        }
    }
}

@Composable
private fun RowSetting(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    options: List<Pair<String, String>>
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.OutlinedButton(
            onClick = {
                val currentIndex = options.indexOfFirst { it.second == value }
                val nextIndex = (currentIndex + 1) % options.size
                onValueChange(options[nextIndex].second)
            }
        ) {
            Text(text = options.first { it.second == value }.first)
        }
    }
}

private fun stringResource(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
