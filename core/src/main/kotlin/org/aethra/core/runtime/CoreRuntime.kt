package org.aethra.core.runtime

import org.aethra.core.agents.AgentEngine
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.runtime.events.EventBus as CoreEventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.aethra.core.task.ProviderTaskRunner
import org.aethra.core.task.TaskScheduler
import org.aethra.core.task.TaskPersistence
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.providers.ProviderSystem
import org.aethra.core.providers.ProviderRegistry
import org.aethra.core.providers.ModelRegistry
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.skills.SkillRegistry

/** Runtime configuration for the AETHRA core. */
data class CoreConfig(
    val storagePath: String = "aethra-core.json",
    val maxConcurrentTasks: Int = 4,
    val autoSaveIntervalMs: Long = 30_000,
    val auditEnabled: Boolean = true,
    val telemetryEnabled: Boolean = false
)

/** Lifecycle state of the AETHRA core. */
enum class CoreState {
    CREATED, STARTING, RUNNING, PAUSED, STOPPING, STOPPED
}

/** Health report surfaced by the core. Designed to grow into platform metering
 *  (CPU, temperature, battery) without changing its stable JSON shape. */
data class Health(
    val running: Boolean,
    val tasksActive: Int,
    val tasksQueued: Int,
    val errors: Int
) {
    val isHealthy: Boolean get() = running && errors == 0
}

/** Full core runtime registry. All subsystems are reachable here by name.
 *  Platform-agnostic: it borders storage/IO (supplied by the platform layer)
 *  and the presentation layer (app module), keeping Compose and Android out of the core. */
class CoreRegistry(
    val config: CoreConfig,
    val storage: Storage,
    val memoryEngine: MemoryEngine,
    val toolRegistry: ToolRegistry,
    val providerSystem: ProviderSystem,
    val skillRegistry: SkillRegistry,
    val agentEngine: AgentEngine,
    val eventBus: CoreEventBus,
    val lifecycle: Lifecycle,
    val auditLog: AuditLog,
    val permissionBroker: PermissionBroker,
    val providerRegistry: ProviderRegistry,
    val modelRegistry: ModelRegistry,
    val providerModelRouter: ProviderModelRouter,
    val taskRunner: TaskRunner? = null
) {
    /** Core-owned task execution. A platform may supply a dispatcher that handles
     * local tools first and delegates provider-backed work when appropriate. */
    val taskEngine = TaskEngine(
        storage = storage,
        eventBus = eventBus,
        runner = taskRunner ?: ProviderTaskRunner(
            router = providerModelRouter,
            providerRegistry = providerRegistry,
            auditLog = auditLog
        ),
        maxConcurrent = config.maxConcurrentTasks
    )

    fun get(name: String): Any? = when (name) {
        "config" -> config
        "storage" -> storage
        "taskEngine" -> taskEngine
        "memoryEngine" -> memoryEngine
        "toolRegistry" -> toolRegistry
        "providerSystem" -> providerSystem
        "skillRegistry" -> skillRegistry
        "agentEngine" -> agentEngine
        "eventBus" -> eventBus
        "lifecycle" -> lifecycle
        "auditLog" -> auditLog
        "permissionBroker" -> permissionBroker
        "providerRegistry" -> providerRegistry
        "modelRegistry" -> modelRegistry
        "providerModelRouter" -> providerModelRouter
        else -> null
    }
}
