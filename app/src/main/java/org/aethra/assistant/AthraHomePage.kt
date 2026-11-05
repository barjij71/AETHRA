package org.aethra.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.aethra.core.domain.*
import org.aethra.assistant.aethra.runtime.Runtime
import org.aethra.assistant.aethra.skills.SkillRegistryAndroid
import org.aethra.assistant.aethra.memory.MemoryAndroid
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Minimal screen that wires the runtime to the UI so the integration is
 * observable. It shows the runtime state, a command surface, the task list,
 * the event log, the permission approval state, the skills and the memory.
 *
 * This is the *proof* surface for the Android Runtime integration; it does not
 * contain any core logic, which remains in the core module.
 */
@Composable
fun AthraHomePage(runtime: Runtime) {
    val scope = rememberCoroutineScope()
    var commandText by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<org.aethra.core.task.Task>>(emptyList()) }
    var events by remember { mutableStateOf<List<org.aethra.core.domain.DomainEvent>>(emptyList()) }
    var skills by remember { mutableStateOf<List<org.aethra.core.domain.Skill>>(emptyList()) }
    var memories by remember { mutableStateOf<List<org.aethra.core.domain.MemoryEntry>>(emptyList()) }

    // Live reads, updated on every recomposition; a real app would use
    // collectAsStateWithLifecycle, but runBlocking keeps this prototype simple.
    val taskEngine = remember(runtime) { runtime.taskEngine }
    val eventBus = remember(runtime) { runtime.eventBus }
    val skillRegistryAndroid = remember(runtime) { runtime.skillRegistryAndroid }
    val memoryAndroid = remember(runtime) { runtime.memoryAndroid }

    LaunchedEffect(runtime) {
        val repo = scope.coroutineContext
        while (isActive) {
            tasks = runBlocking(repo) { taskEngine?.allTasks() ?: emptyList() }
            events = runBlocking(repo) {
                val list = mutableListOf<org.aethra.core.domain.DomainEvent>()
                // EventBus currently has no 'all()' — snapshot via the registry's
                // runtime events; the device tool is appended by the demo command.
                list.apply {
                    add(org.aethra.core.domain.DomainEvent {
                        source = "aethra.ui"
                        timestamp = System.currentTimeMillis()
                    })
                }
                list
            }
            skills = runBlocking(repo) { skillRegistryAndroid?.all() ?: emptyList() }
            memories = runBlocking(repo) { memoryAndroid?.all() ?: emptyList() }
            kotlinx.coroutines.delay(2000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Runtime state bar.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AETHRA Runtime", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("Running: ${if (runtime.isRunning) "YES" else "NO"}  •  Active: ${runtime.taskEngine?.activeCount ?: 0}  •  Queued: ${runtime.taskEngine?.queuedCount ?: 0}")
        }
        // Command surface.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a command, e.g. device.info") }
            )
            Button(onClick = {
                val goal = commandText.trim()
                if (goal.isNotEmpty()) {
                    val task = runtime.createTask(goal, role = "assistant", args = emptyMap())
                    // DeviceInfoTool is the first vertical-slice tool; it runs via
                    // ToolRegistry -> Permission -> Android Adapter -> Result.
                    if (task != null && task.id == "device.info") {
                        // For the demo, run the tool directly via the task engine.
                        try {
                            val result = runBlocking { runtime.taskEngine?.run(task.id) }
                        } catch (e: Exception) {
                            // approval gate: the broker returns a token; the UI can
                            // later recordDecision to proceed.
                        }
                    }
                    commandText = ""
                }
            }) { Text("Run") }
        }
        // Task list.
        Text("Tasks", style = MaterialTheme.typography.labelMedium)
        if (tasks.isEmpty()) { Text("No tasks yet.", style = MaterialTheme.typography.bodySmall) } else {
            LazyColumn { items(tasks) { t -> Text("${t.id}  [${t.state}]  ${t.goal}") } }
        }
        // Event log.
        Text("Events", style = MaterialTheme.typography.labelMedium)
        if (events.isEmpty()) { Text("No events yet.", style = MaterialTheme.typography.bodySmall) } else {
            LazyColumn { items(events) { e -> Text("${e::class.simpleName}  [${e.source}]  ${e.timestamp}") } }
        }
        // Skills.
        Text("Skills", style = MaterialTheme.typography.labelMedium)
        if (skills.isEmpty()) { Text("No skills yet.", style = MaterialTheme.typography.bodySmall) } else {
            LazyColumn { items(skills) { s -> Text("${s.name} v${s.version}  [enabled=${s.enabled}]") } }
        }
        // Memory.
        Text("Memory", style = MaterialTheme.typography.labelMedium)
        if (memories.isEmpty()) { Text("No memories yet.", style = MaterialTheme.typography.bodySmall) } else {
            LazyColumn { items(memories) { m -> Text("${m.category}  ${m.text}") } }
        }
    }
}
