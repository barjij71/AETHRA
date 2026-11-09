package org.aethra.core.runtime

import org.aethra.core.storage.Storage
import org.aethra.core.task.TaskEngine
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.providers.ProviderSystem
import org.aethra.core.skills.SkillRegistry
import org.aethra.core.agents.AgentEngine
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker

interface Lifecycle {
    val state: CoreState
    val isRunning: Boolean
    var health: Health
    fun start(): Result<Unit>
    fun stop(): Result<Unit>
}

class LifecycleImpl(
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
    override var health: Health = Health(false, 0, 0, 0)

    override fun start(): Result<Unit> = try {
        _state = CoreState.RUNNING
        health = Health(true, taskEngine.activeCount, taskEngine.queuedCount, 0)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override fun stop(): Result<Unit> = try {
        taskEngine.close()
        _state = CoreState.STOPPED
        health = Health(false, 0, 0, 0)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
