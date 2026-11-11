package org.aethra.assistant.aethra.adapters

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.aethra.assistant.R

@Composable
fun InstaBoxSettingsScreen(
    adapters: PlatformAdapters,
    onClose: () -> Unit
) {
    val store = adapters.instaBoxApiKeyStore
    var apiKey by remember { mutableStateOf(store.getApiKey() ?: "") }
    var apiKeyError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(apiKey) {
        if (apiKey.isNotEmpty()) {
            store.setApiKey(apiKey)
        }
    }

    val apiKeyConfigured = store.hasApiKey()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            text = "InstaBox Remote Computer",
            style = MaterialTheme.typography.headlineMedium
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))

        Text(text = "API Key", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth()
        )
        apiKeyError?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    apiKeyError = null
                    val key = apiKey.trim()
                    if (key.isEmpty()) {
                        apiKeyError = "API key cannot be empty"
                    } else {
                        store.setApiKey(key)
                    }
                },
                enabled = apiKey.isNotEmpty()
            ) {
                Text("Save Key")
            }
            Button(
                onClick = {
                    apiKey = ""
                    store.clearApiKey()
                    apiKeyError = null
                },
                enabled = apiKeyConfigured
            ) {
                Text("Clear Key")
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { onClose() }, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
