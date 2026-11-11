package org.aethra.assistant.aethra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import org.aethra.assistant.R

@Composable
fun SettingsScreen(
    visualModeState: VisualModeState,
    onNavigateHome: () -> Unit
) {
    val languageSelected = remember { mutableStateOf<String>("en") }
    val themeSelected = remember { mutableStateOf<String>("system") }
    val visualEffectsEnabled = remember { mutableStateOf<Boolean>(true) }
    val voiceUiEnabled = remember { mutableStateOf<Boolean>(false) }

    val settingEnabled = stringResource(id = R.string.setting_enabled)
    val settingDisabled = stringResource(id = R.string.setting_disabled)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(id = R.string.settings_title),
            style = MaterialTheme.typography.titleLarge
        )

        RowSetting(
            title = stringResource(id = R.string.settings_language),
            value = languageSelected.value,
            onValueChange = { languageSelected.value = it },
            options = listOf("en" to "English", "ar" to "\u0627\u0644\u0639\u0631\u0628\u064a\u0629")
        )

        RowSetting(
            title = stringResource(id = R.string.settings_theme),
            value = themeSelected.value,
            onValueChange = { themeSelected.value = it },
            options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        )

        RowSetting(
            title = stringResource(id = R.string.settings_visual_effects),
            value = if (visualEffectsEnabled.value) settingEnabled else settingDisabled,
            onValueChange = {
                visualEffectsEnabled.value = it == settingEnabled
                visualModeState.setMode(
                    if (visualEffectsEnabled.value) VisualMode.BALANCED else VisualMode.POWER_SAVING
                )
            },
            options = listOf(settingEnabled to settingEnabled, settingDisabled to settingDisabled)
        )

        RowSetting(
            title = stringResource(id = R.string.settings_power_saving),
            value = if (visualModeState.isPowerSaving()) settingEnabled else settingDisabled,
            onValueChange = {
                val powerSaving = it == settingEnabled
                visualModeState.setMode(
                    if (powerSaving) VisualMode.POWER_SAVING else VisualMode.BALANCED
                )
                visualEffectsEnabled.value = !powerSaving
            },
            options = listOf(settingEnabled to settingEnabled, settingDisabled to settingDisabled)
        )

        RowSetting(
            title = stringResource(id = R.string.settings_voice_ui),
            value = if (voiceUiEnabled.value) settingEnabled else settingDisabled,
            onValueChange = { voiceUiEnabled.value = it == settingEnabled },
            options = listOf(settingEnabled to settingEnabled, settingDisabled to settingDisabled)
        )

        Text(
            text = "Voice UI preference is stored locally. STT/TTS/wake-word behavior is not yet implemented.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomEnd),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onNavigateHome
            ) {
                Text(text = stringResource(id = R.string.nav_home))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
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
