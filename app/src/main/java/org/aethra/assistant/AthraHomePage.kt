package org.aethra.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.aethra.core.domain.DomainEvent
import org.aethra.core.domain.MemoryEntry
import org.aethra.core.skills.Skill
import org.aethra.core.task.Task
import org.aethra.assistant.aethra.runtime.Runtime

@Composable
fun AthraHomePage(
    runtime: Runtime,
    onNavigateToSettings: () -> Unit = {}
) {
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
                text = stringResource(id = R.string.home_title),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "  •  " +
                    stringResource(id = R.string.active_tasks, runtime.taskEngine?.activeCount ?: 0) +
                    "  •  " +
                    stringResource(id = R.string.queued_tasks, runtime.taskEngine?.queuedCount ?: 0)
            )
            Button(onClick = onNavigateToSettings) {
                Text(text = stringResource(id = R.string.settings_button))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(id = R.string.command_placeholder)) }
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
            }) { Text(text = stringResource(id = R.string.run_button)) }
        }

        Text(text = stringResource(id = R.string.tasks_section), style = MaterialTheme.typography.labelMedium)
        if (tasks.isEmpty()) {
            Text(text = stringResource(id = R.string.no_tasks), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(tasks) { t ->
                    Text(t.id + "  [" + t.state + "]  " + t.goal)
                    if (t.note.isNotBlank()) {
                        Text(
                            t.note,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(text = stringResource(id = R.string.events_section), style = MaterialTheme.typography.labelMedium)
        if (events.isEmpty()) {
            Text(text = stringResource(id = R.string.no_events), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(events) { e ->
                    Text(e::class.simpleName + "  [" + e.source + "]  " + e.timestamp)
                }
            }
        }

        Text(text = stringResource(id = R.string.skills_section), style = MaterialTheme.typography.labelMedium)
        if (skills.isEmpty()) {
            Text(text = stringResource(id = R.string.no_skills), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(skills) { s ->
                    Text(s.name + " v" + s.version + "  [enabled=" + s.enabled + "]")
                }
            }
        }

        Text(text = stringResource(id = R.string.memory_section), style = MaterialTheme.typography.labelMedium)
        if (memories.isEmpty()) {
            Text(text = stringResource(id = R.string.no_memory), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn {
                items(memories) { m -> Text(m.category.toString() + "  " + m.text) }
            }
        }
    }
}

@Composable
private fun stringResource(id: Int): String = androidx.compose.ui.res.stringResource(id = id)
