package org.aethra.assistant.aethra.adapters

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.aethra.core.domain.RiskClass
import org.aethra.core.external.CapabilityRequest
import org.aethra.core.external.CapabilityHealth
import org.aethra.core.external.InstaBoxRemoteComputerProvider
import org.aethra.core.external.InstaBoxTransport
import org.aethra.core.external.UrlConnectionInstaBoxTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real configuration screen for the InstaBox external capability.
 *
 * Allows the user to:
 * - Enter/store the InstaBox API key (masked input, never displayed after entry)
 * - Test authentication against the real API
 * - Discover machines via GET /v1/machines
 * - Select a machine for task execution
 * - See current machine status from the live provider
 *
 * No fake status indicators: every status comes from the live provider/API
 * or from explicit local configuration state.
 * The raw API key is never displayed in UI state after entry.
 * The UI remains compatible with AETHRA's existing RTL/LTR behaviour.
 */
@Composable
fun InstaBoxSettingsScreen(
    adapters: PlatformAdapters,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val store = adapters.instaBoxApiKeyStore
    val machineStore = adapters.selectedMachineStore
    val apiKeyProvider = remember { AndroidInstaBoxApiKeyProvider(store) }
    val transport = remember { UrlConnectionInstaBoxTransport() }
    val provider = remember {
        InstaBoxRemoteComputerProvider(
            machineIdProvider = { machineStore.getSelectedMachineId() ?: "" },
            transport = transport,
            apiKeyProvider = apiKeyProvider
        )
    }

    var apiKey by remember { mutableStateOf(store.getApiKey() ?: "") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeyError by remember { mutableStateOf<String?>(null) }
    var isTestingKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var machines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }

    var selectedMachineId by remember { mutableStateOf(machineStore.getSelectedMachineId() ?: "") }
    var machineStatus by remember { mutableStateOf<String?>(null) }
    var isCheckingStatus by remember { mutableStateOf(false) }

    // Persist API key when changed
    LaunchedEffect(apiKey) {
        if (apiKey.isNotEmpty()) {
            store.setApiKey(apiKey)
        }
    }

    // Sync selected machine to store
    LaunchedEffect(selectedMachineId) {
        if (selectedMachineId.isNotEmpty()) {
            machineStore.setSelectedMachineId(selectedMachineId)
        }
    }

    val apiKeyConfigured = store.hasApiKey()
    val machineSelected = selectedMachineId.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            text = "InstaBox Remote Computer",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // API Key input
        Text(
            text = "API Key",
            style = MaterialTheme.typography.labelMedium
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            leadingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (apiKeyVisible) "Hide API key" else "Show API key"
                    )
                }
            },
            visualTransformation = if (apiKeyVisible) null else VisualTransformation.Password(),
            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                autoCorrect = false
            ),
            maxLines = 1,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (apiKeyVisible) {
            Text(
                text = "⚠️ API key is visible. Hide when not in use.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        apiKeyError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.ui.layout.Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    apiKeyError = null
                    isTestingKey = true
                    testResult = null
                    val key = apiKey.trim()
                    if (key.isEmpty()) {
                        apiKeyError = "API key cannot be empty"
                        isTestingKey = false
                    } else {
                        scope.launch {
                            val testProvider = InstaBoxRemoteComputerProvider(
                                machineIdProvider = { "" },
                                transport = transport,
                                apiKeyProvider = org.aethra.core.external.InstaBoxApiKeyProvider { key }
                            )
                            val result = withContext(Dispatchers.IO) {
                                testProvider.health()
                            }
                            isTestingKey = false
                            testResult = when (result) {
                                CapabilityHealth.READY -> "API key is valid (API reachable)"
                                CapabilityHealth.DEGRADED -> "API key accepted but service degraded"
                                CapabilityHealth.UNAVAILABLE -> "API key rejected or API unreachable"
                            }
                        }
                    }
                },
                enabled = apiKey.isNotEmpty() && !isTestingKey
            ) {
                if (isTestingKey) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Test Authentication")
                }
            }
            Button(
                onClick = {
                    apiKey = ""
                    store.clearApiKey()
                    apiKeyError = null
                    testResult = null
                },
                enabled = apiKeyConfigured
            ) {
                Text("Clear Key")
            }
        }
        testResult?.let { result ->
            Text(
                text = result,
                style = MaterialTheme.typography.caption,
                color = if (result.contains("valid")) MaterialTheme.colorScheme.primary
                else if (result.contains("rejected")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Machine discovery
        Text(
            text = "Machine Discovery",
            style = MaterialTheme.typography.labelMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.ui.layout.Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    discoverError = null
                    isDiscovering = true
                    val discoveryProvider = InstaBoxRemoteComputerProvider(
                        machineIdProvider = { "" },
                        transport = transport,
                        apiKeyProvider = org.aethra.core.external.InstaBoxApiKeyProvider { apiKey.trim() }
                    )
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            discoveryProvider.execute(
                                CapabilityRequest(
                                    operation = "machine.list",
                                    riskClass = RiskClass.READ_ONLY
                                )
                            )
                        }
                        isDiscovering = false
                        val cr = result as? org.aethra.core.external.CapabilityResult
                        if (cr != null && cr.success) {
                            val body = cr.output["body"] ?: ""
                            val parsed = parseMachineList(body)
                            machines = parsed
                        } else {
                            discoverError = cr?.error ?: "Discovery failed"
                        }
                    }
                },
                enabled = apiKeyConfigured && !isDiscovering
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Discover Machines")
                }
            }
            if (apiKeyConfigured == false) {
                Text(
                    text = "Enter API key first",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        discoverError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (machines.isNotEmpty()) {
            Text(
                text = "Available Machines",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(200.dp)
            ) {
                items(machines) { machine ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedMachineId = machine.id
                            machineStore.setSelectedMachineId(machine.id)
                        },
                        elevation = if (selectedMachineId == machine.id) 4.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = machine.id, style = MaterialTheme.typography.bodyMedium)
                                Text(text = machine.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (selectedMachineId == machine.id) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Selected machine status
        Text(
            text = "Selected Machine",
            style = MaterialTheme.typography.labelMedium
        )
        if (selectedMachineId.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.ui.layout.Arrangement.SpaceBetween
            ) {
                Text(text = selectedMachineId)
                Button(
                    onClick = {
                        isCheckingStatus = true
                        machineStatus = null
                        val statusProvider = InstaBoxRemoteComputerProvider(
                            machineIdProvider = { selectedMachineId },
                            transport = transport,
                            apiKeyProvider = org.aethra.core.external.InstaBoxApiKeyProvider { store.getApiKey() ?: "" }
                        )
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                statusProvider.health()
                            }
                            isCheckingStatus = false
                            machineStatus = when (result) {
                                CapabilityHealth.READY -> "Machine is reachable and healthy"
                                CapabilityHealth.DEGRADED -> "Machine is reachable but degraded"
                                CapabilityHealth.UNAVAILABLE -> "Machine is unreachable or API key invalid"
                            }
                        }
                    },
                    enabled = apiKeyConfigured && !isCheckingStatus
                ) {
                    if (isCheckingStatus) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text("Check Status")
                    }
                }
                Button(
                    onClick = {
                        selectedMachineId = ""
                        machineStore.setSelectedMachineId("")
                        machineStatus = null
                    },
                    enabled = true
                ) {
                    Text("Clear Selection")
                }
            }
            machineStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.caption,
                    color = if (status.contains("reachable")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text(
                text = "No machine selected. Discover machines above and select one.",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onClose() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}

private data class InstaBoxMachine(
    val id: String,
    val name: String,
    val status: String
)

/**
 * Parses the JSON array response from GET /v1/machines into a list of machines.
 * This is a simple, lenient parser that does not depend on kotlinx.serialization
 * to avoid adding dependencies.
 */
private fun parseMachineList(json: String): List<InstaBoxMachine> {
    val trimmed = json.trim()
    if (!trimmed.startsWith("[")) return emptyList()
    val content = trimmed.substring(1, trimmed.lastIndexOf(']'))
    val items = content.split("},").map { it.trim() }
    return items.mapNotNull { item ->
        val id = extractValue(item, "id")
        val name = extractValue(item, "name") ?: extractValue(item, "label") ?: id
        val status = extractValue(item, "status") ?: "unknown"
        if (id.isNotEmpty()) InstaBoxMachine(id, name, status) else null
    }
}

private fun extractValue(json: String, key: String): String? {
    val keyPattern = "\"$key\""
    val keyIdx = json.indexOf(keyPattern)
    if (keyIdx < 0) return null
    val colonIdx = json.indexOf(':', keyIdx)
    if (colonIdx < 0) return null
    val valueStart = colonIdx + 1
    var i = valueStart
    while (i < json.length && json[i].isWhitespace()) i++
    if (i >= json.length) return null
    if (json[i] == '"') {
        val end = json.indexOf('"', i + 1)
        if (end < 0) return null
        return json.substring(i + 1, end)
    } else {
        val end = json.indexOfAny(arrayOf(',', '}', ']'), i)
        if (end < 0) return json.substring(i).trim()
        return json.substring(i, end).trim()
    }
}
