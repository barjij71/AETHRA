package org.aethra.assistant.aethra.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.aethra.assistant.aethra.adapters.PlatformAdapters
import org.aethra.assistant.aethra.memory.MemoryAndroid
import org.aethra.assistant.aethra.skills.SkillRegistryAndroid
import org.aethra.core.agents.AgentEngine
import org.aethra.core.domain.RiskClass
import org.aethra.core.runtime.CoreConfig
import org.aethra.core.runtime.CoreRegistry
import org.aethra.core.runtime.CoreState
import org.aethra.core.runtime.Health
import org.aethra.core.runtime.Lifecycle
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.skills.SkillRegistry

class Runtime(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var state: CoreState = CoreState.CREATED
        private set

    val isRunning: Boolean
        get() = state == CoreState.RUNNING

    var health = Health(false, 0, 0, 0)
        private set

    private var registry: CoreRegistry? = null
    private var adapters: PlatformAdapters? = null

    fun build(): Runtime = this

    fun start(): Result<Unit> = runBlocking(scope.coroutineContext) {
        if (isRunning) return@runBlocking Result.success(Unit)

        try {
            state = CoreState.STARTING
            val platform = PlatformAdapters(context)

            val core = CoreRegistry(
                config = platform.config,
                storage = platform.storage,
                memoryEngine = platform.memoryEngine,
                toolRegistry = platform.toolRegistry,
                providerSystem = platform.providerSystem,
                skillRegistry = platform.skillRegistry,
                agentEngine = platform.agentEngine,
                eventBus = platform.eventBus,
                lifecycle = platform.lifecycle,
                auditLog = platform.auditLog,
                permissionBroker = platform.permissionBroker,
                providerRegistry = platform.providerRegistry,
                modelRegistry = platform.modelRegistry,
                providerModelRouter = platform.providerModelRouter,
                taskRunner = platform.taskRunner
            )

            platform.registerAndroidTools()
            platform.bindCoreTaskEngine(core.taskEngine)
            registry = core
            adapters = platform

            platform.memoryAndroid.initialize()
            platform.registerBuiltInSkills()

            val result = platform.lifecycle.start()
            if (result.isSuccess) {
                state = CoreState.RUNNING
                health = Health(
                    running = true,
                    tasksActive = core.taskEngine.activeCount,
                    tasksQueued = core.taskEngine.queuedCount,
                    errors = 0
                )
            } else {
                state = CoreState.STOPPED
            }
            result
        } catch (e: Exception) {
            state = CoreState.STOPPED
            Result.failure(e)
        }
    }

    fun shutdown(): Result<Unit> = runBlocking(scope.coroutineContext) {
        if (state == CoreState.STOPPED || state == CoreState.CREATED) {
            return@runBlocking Result.success(Unit)
        }

        try {
            val result = registry?.lifecycle?.stop() ?: Result.success(Unit)
            registry?.taskEngine?.close()
            registry?.memoryEngine?.close()
            registry = null
            adapters = null
            state = CoreState.STOPPED
            health = Health(false, 0, 0, 0)
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    val registryOrNull: CoreRegistry?
        get() = registry
    val config: CoreConfig?
        get() = registry?.config
    val storage: Storage?
        get() = registry?.storage
    val taskEngine: TaskEngine?
        get() = registry?.taskEngine
    val memoryEngine
        get() = registry?.memoryEngine
    val toolRegistry: ToolRegistry?
        get() = registry?.toolRegistry
    val skillRegistry: SkillRegistry?
        get() = registry?.skillRegistry
    val agentEngine: AgentEngine?
        get() = registry?.agentEngine
    val eventBus: EventBus?
        get() = registry?.eventBus
    val auditLog: AuditLog?
        get() = registry?.auditLog
    val permissionBroker: PermissionBroker?
        get() = registry?.permissionBroker
    val lifecycle: Lifecycle?
        get() = registry?.lifecycle
    val memoryAndroid: MemoryAndroid?
        get() = adapters?.memoryAndroid
    val skillRegistryAndroid: SkillRegistryAndroid?
        get() = adapters?.skillRegistryAndroid

    fun createTask(
        goal: String,
        role: String = "assistant",
        riskClass: RiskClass = RiskClass.READ_ONLY,
        args: Map<String, String> = emptyMap()
    ): Task? {
        val engine = taskEngine ?: return null
        return engine.create(goal, role, riskClass)
    }
}

fun createRuntime(context: Context): Runtime = Runtime(context)
