package org.aethra.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.aethra.core.domain.DomainEvent
import org.aethra.core.domain.MemoryEntry
import org.aethra.core.skills.Skill
import org.aethra.core.task.Task
import org.aethra.assistant.aethra.runtime.Runtime

@Composable
fun AthraHomePage(runtime: Runtime) {
    var commandText by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var events by remember { mutableStateOf<List<DomainEvent>>(emptyList()) }
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var memories by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }

    val taskEngine = remember(runtime) { runtime.taskEngine }
    val skillRegistryAndroid = remember(runtime) { runtime.skillRegistryAndroid }
    val memoryAndroid = remember(runtime) { runtime.memoryAndroid }

    LaunchedEffect(runtime) {
        while (isActive) {
            tasks = taskEngine?.allTasks() ?: emptyList()
            events = runtime.eventBus?.recent(50) ?: emptyList()
            skills = skillRegistryAndroid?.all() ?: emptyList()
            memories = memoryAndroid?.all() ?: emptyList()
            delay(2000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AETHRA Runtime",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Running: " + if (runtime.isRunning) "YES" else "NO" +
                    "  •  Active: " + (runtime.taskEngine?.activeCount ?: 0) +
                    "  •  Queued: " + (runtime.taskEngine?.queuedCount ?: 0)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    if (task != null) {
                        runtime.taskEngine?.run(task.id)
                    }
                    commandText = ""
                }
            }) { Text("Run") }
        }

        Text("Tasks", style = MaterialTheme.typography.labelMedium)
        if (tasks.isEmpty()) {
            Text("No tasks yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(tasks) { t -> Text(t.id + "  [" + t.state + "]  " + t.goal) }
            }
        }

        Text("Events", style = MaterialTheme.typography.labelMedium)
        if (events.isEmpty()) {
            Text("No events yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(events) { e ->
                    Text(e::class.simpleName + "  [" + e.source + "]  " + e.timestamp)
                }
            }
        }

        Text("Skills", style = MaterialTheme.typography.labelMedium)
        if (skills.isEmpty()) {
            Text("No skills yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(skills) { s ->
                    Text(s.name + " v" + s.version + "  [enabled=" + s.enabled + "]")
                }
            }
        }

        Text("Memory", style = MaterialTheme.typography.labelMedium)
        if (memories.isEmpty()) {
            Text("No memories yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(memories) { m -> Text(m.category.toString() + "  " + m.text) }
            }
        }
    }
}
