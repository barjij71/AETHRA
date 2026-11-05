package org.aethra.assistant.aethra.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.aethra.core.runtime.CoreConfig
import org.aethra.core.runtime.CoreState
import org.aethra.core.runtime.Health
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.assistant.aethra.adapters.PlatformAdapters
import org.aethra.assistant.aethra.adapters.AndroidStorage
import org.aethra.assistant.aethra.memory.MemoryAndroid
import org.aethra.assistant.aethra.security.ExecutionPolicy
import org.aethra.assistant.aethra.skills.SkillRegistryAndroid

/**
 * AETHRA Android Runtime.
 *
 * Wires the CoreRuntime (platform-agnostic) with the Android platform layer.
 * This class is the single entry point for the runtime: it supplies the Android
 * implementations of the platform abstractions (Storage, TaskRunner, ...) and
 * wires them into the CoreRuntime registry. It never contains core logic; the
 * engines live in the core module and this class only adapts their boundaries.
 *
 * Lifecycle:
 *   create(context) -> start() -> [RUNNING] -> shutdown()
 */
class Runtime private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var state: CoreState = CoreState.CREATED
        private set
    var isRunning: Boolean get() = state == CoreState.RUNNING
    var health = Health(running = false, tasksActive = 0, tasksQueued = 0, errors = 0)
        private set

    private var eventBus: EventBus? = null
    private var storage: AndroidStorage? = null
    private var registry: org.aethra.core.runtime.CoreRegistry? = null
    private lateinit var platformAdapters: PlatformAdapters

    /** Build a runtime bound to the given Android [Context]. */
    fun build(): Runtime = this

    /**
     * Start the runtime: initialise the Android storage, construct the CoreRuntime
     * with the Android adapters, register the Android-native tools, and start the
     * lifecycle. Thread-safe; idempotent (returns SUCCESS immediately if running).
     */
    fun start(): Result<Unit> = runBlocking(scope.coroutineContext) {
        if (isRunning) return@runBlocking Result.success(Unit)
        try {
            // 1. Android persistence implementation for the core (KVStore, MemoryEngine, AuditLog, TaskPersistence).
            storage = AndroidStorage(context)

            // 2. Platform adapters (DeviceInfoTool, permission bridge, execution policy, native tools).
            platformAdapters = PlatformAdapters(storage!!)
            val adapters = platformAdapters

            // 3. Compose the core engines. The TaskEngine is wired with the Android
            //    TaskRunner (the real Android tool dispatcher); everything else is
            //    platform-agnostic and uses the Android storage.
            val taskEngine = adapters.taskEngine
            val memoryEngine = adapters.memoryEngine
            val toolRegistry = adapters.toolRegistry
            val providerSystem = adapters.providerSystem
            val skillRegistry = adapters.skillRegistry
            val agentEngine = adapters.agentEngine
            val auditLog = adapters.auditLog
            val permissionBroker = adapters.permissionBroker
            val eventBus = adapters.eventBus

            // 4. Lifecycle (delegates to CoreRegistry.lifecycle; created with the Android storage).
            val lifecycle = org.aethra.core.runtime.LifecycleImpl(
                storage = storage!!,
                config = CoreConfig(),
                taskEngine = taskEngine,
                memoryEngine = memoryEngine,
                toolRegistry = toolRegistry,
                providerSystem = providerSystem,
                skillRegistry = skillRegistry,
                agentEngine = agentEngine,
                eventBus = eventBus,
                auditLog = auditLog,
                permissionBroker = permissionBroker
            )

            registry = org.aethra.core.runtime.CoreRegistry(
                config = CoreConfig(),
                storage = storage,
                taskEngine = taskEngine,
                memoryEngine = memoryEngine,
                toolRegistry = toolRegistry,
                providerSystem = providerSystem,
                skillRegistry = skillRegistry,
                agentEngine = agentEngine,
                eventBus = eventBus,
                lifecycle = lifecycle,
                auditLog = auditLog,
                permissionBroker = permissionBroker
            )

            // 5. Publish lifecycle and system-readiness events.
            eventBus!!.publish(org.aethra.core.domain.DomainEvent {
                source = "aethra.runtime"
                timestamp = System.currentTimeMillis()
            })
            this@Runtime.eventBus = eventBus

            // 6. Register the Android-native tools (DeviceInfoTool, ...).
            adapters.registerAndroidTools()

            // 7. Start the core lifecycle.
            val startResult = lifecycle.start()
            if (startResult.isSuccess) {
                state = CoreState.RUNNING
                health = Health(running = true, tasksActive = taskEngine.activeCount, tasksQueued = taskEngine.queuedCount, errors = 0)
            }
            startResult
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shut down the runtime: stop the lifecycle, detach the adapters, and release
     * the storage. Thread-safe; idempotent.
     */
    fun shutdown(): Result<Unit> = runBlocking(scope.coroutineContext) {
        if (!isRunning) return@runBlocking Result.success(Unit)
        try {
            val lifecycle = registry?.lifecycle ?: return@runBlocking Result.success(Unit)
            val result = lifecycle.stop()
            state = CoreState.STOPPED
            eventBus = null
            registry = null
            storage = null
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Current CoreRegistry (read-only view). Null until start(). */
    val registryOrNull: org.aethra.core.runtime.CoreRegistry?
        get() = registry

    /** Read-only access to the core engines. */
    val config: CoreConfig?
        get() = registry?.config
    val storage: AndroidStorage?
        get() = storage
    val taskEngine: TaskEngine?
        get() = registry?.taskEngine
    val memoryEngine: org.aethra.core.memory.MemoryEngine?
        get() = registry?.memoryEngine
    val toolRegistry: org.aethra.core.tools.ToolRegistry?
        get() = registry?.toolRegistry
    val skillRegistry: org.aethra.core.skills.SkillRegistry?
        get() = registry?.skillRegistry
    val agentEngine: org.aethra.core.agents.AgentEngine?
        get() = registry?.agentEngine
    val eventBus: EventBus?
        get() = eventBus
    val auditLog: AuditLog?
        get() = registry?.auditLog
    val permissionBroker: PermissionBroker?
        get() = registry?.permissionBroker
    val lifecycle: org.aethra.core.runtime.Lifecycle?
        get() = registry?.lifecycle

    /** Create a task with arguments, enqueue it, and return it (caller runs it via taskEngine.run(task.id)). */
    fun createTask(goal: String, role: String = "assistant", riskClass: RiskClass = RiskClass.READ_ONLY, args: Map<String, String> = emptyMap()): Task? {
        val taskEngine = this.taskEngine ?: return null
        val task = taskEngine.create(goal, role, riskClass)
        task.detail = args
        return task
    }
}

/** Factory for [Runtime]. */
fun createRuntime(context: Context): Runtime = Runtime(context)
