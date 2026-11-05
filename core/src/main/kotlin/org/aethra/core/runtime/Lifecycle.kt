package org.aethra.core.runtime

import org.aethra.core.storage.Storage
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskScheduler
import org.aethra.core.task.TaskPersistence
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.providers.ProviderSystem
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.agents.AgentEngine
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker

/** Core lifecycle interface. The platform layer creates the concrete implementation; the core keeps a platform-agnostic lifecycle contract. */
interface Lifecycle {
    val state: CoreState
    val isRunning: Boolean
    var health: Health
    fun start(): Result<Unit>
    fun stop(): Result<Unit>
}

/** Internal implementation detail: the real lifecycle object created by [CoreRuntime.start]. Delegation keeps the constructor free of platform imports. */
internal class LifecycleImpl(
    private val storage: Storage,
    private val config: CoreConfig,
    private val taskEngine: TaskEngine,
    private val memoryEngine: MemoryEngine,
    private val toolRegistry: ToolRegistry,
    private val providerSystem: ProviderSystem,
    private val skillRegistry: SkillRegistry,
    private val agentEngine: AgentEngine,
    private val eventBus: EventBus,
    private val auditLog: AuditLog,
    private val permissionBroker: PermissionBroker
) : Lifecycle {
    private var _state = CoreState.CREATED
    override val state: CoreState get() = _state
    override val isRunning: Boolean get() = _state == CoreState.RUNNING
    override var health: Health = Health(running = false, tasksActive = 0, tasksQueued = 0, errors = 0)

    override fun start(): Result<Unit> {
        return try {
            _state = CoreState.RUNNING
            health = Health(running = true, tasksActive = taskEngine.activeCount, tasksQueued = taskEngine.queuedCount, errors = 0)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun stop(): Result<Unit> {
        return try {
            _state = CoreState.STOPPED
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
