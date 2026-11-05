package org.aethra.assistant.aethra.adapters

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.runtime.CoreConfig
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.aethra.core.tools.NativeTools
import org.aethra.core.tools.Tool
import org.aethra.core.tools.ToolRegistry
import org.aethra.core.agents.AgentEngine
import org.aethra.core.providers.ProviderSystem
import org.aethra.core.skills.SkillRegistry
import org.aethra.assistant.aethra.security.ExecutionPolicy
import org.aethra.assistant.aethra.tools.DeviceInfoTool

/**
 * Android implementation of the [Storage] abstraction.
 *
 * Persists per-application using Android's private SharedPreferences (no
 * external-readable/external-writable XML files). This is the platform
 * implementation the core runtime uses; the core itself stays storage-free.
 */
class AndroidStorage(private val context: Context) : Storage {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aethra.core.v1", Context.MODE_PRIVATE)
    private val lock = Mutex()

    override suspend fun getString(key: String, defaultValue: String?): String? {
        return withContext(Dispatchers.IO) { lock.withLock { prefs.getString(key, defaultValue) } }
    }

    override suspend fun setString(key: String, value: String) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putString(key, value).apply() } }
    }

    override suspend fun getLong(key: String, defaultValue: Long): Long? {
        return withContext(Dispatchers.IO) { lock.withLock { prefs.getLong(key, defaultValue) } }
    }

    override suspend fun setLong(key: String, value: Long) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putLong(key, value).apply() } }
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean? {
        return withContext(Dispatchers.IO) { lock.withLock { prefs.getBoolean(key, defaultValue) } }
    }

    override suspend fun setBoolean(key: String, value: Boolean) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putBoolean(key, value).apply() } }
    }

    override suspend fun getAll(): Map<String, String> {
        return withContext(Dispatchers.IO) { lock.withLock { prefs.all.mapKeys { it.key } } }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().remove(key).apply() } }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().clear().apply() } }
    }
}

/**
 * Platform adapters that bind the Android layer to the CoreRuntime engines.
 *
 * This class owns the creation and bookkeeping of the engine instances the
 * runtime uses. It wires:
 *   - Storage               -> AndroidStorage
 *   - TaskRunner            -> Android task dispatcher (DeviceInfoTool, ...)
 *   - ToolRegistry          -> + Android-native tools
 *   - PermissionBroker      -> PermissionBridge delegate
 *   - MemoryEngine          -> AndroidStorage-backed
 *   - AuditLog              -> AndroidStorage-backed
 *   - EventBus              -> in-process bus
 *   - Lifecycle             -> created with Android storage
 *   - ExecutionPolicy       -> policy engine (Policy -> Permission -> Audit -> Execution)
 *   - ProviderSystem        -> lightweight router wiring (external providers later)
 */
class PlatformAdapters(private val context: Context) {
    val storage: AndroidStorage = AndroidStorage(context)
    val config = CoreConfig()

    // ---- Built engines ----
    val policyEngine: PolicyEngine = PolicyEngine()
    val taskRunner: AndroidTaskDispatcher = AndroidTaskDispatcher(this)
    val taskEngine: TaskEngine = TaskEngine(storage, eventBus, taskRunner)
    val memoryEngine: MemoryEngine = MemoryEngine(storage)
    val toolRegistry: ToolRegistry = ToolRegistry()
    val providerSystem: ProviderSystem = ProviderSystem()
    val skillRegistry: SkillRegistry = SkillRegistry()
    val agentEngine: AgentEngine = AgentEngine()
    val auditLog: AuditLog = AuditLog(storage)
    val permissionBroker: PermissionBroker = PermissionBroker(auditLog)
    val executionPolicy: ExecutionPolicy = ExecutionPolicy(policyEngine, permissionBroker, auditLog, taskRunner)
    val eventBus: EventBus = EventBus()
    val lifecycle: org.aethra.core.runtime.Lifecycle = org.aethra.core.runtime.LifecycleImpl(
        storage = storage,
        config = config,
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
    val executionPolicyRef: ExecutionPolicy get() = executionPolicy
    val memoryAndroid: MemoryAndroid = MemoryAndroid(memoryEngine)
    val skillRegistryAndroid: SkillRegistryAndroid = SkillRegistryAndroid(skillRegistry)

    /** Register the Android-native tools into the registry. */
    fun registerAndroidTools() {
        // DeviceInfoTool is declared via NativeTools (core); register the
        // Android implementation wrapper so the dispatcher knows the Android
        // execution body. NativeTools declares the capability; this adapter
        // binds it with the real Android execution body.
        val deviceInfo = DeviceInfoTool(context)
        toolRegistry.register(deviceInfo.tool)
    }

    /** Android context (public accessor for the task dispatcher / tools). */
    val context: Context = context

    /** TaskRunner that dispatches tools on Android to the platform adapters. */
    inner class AndroidTaskDispatcher(private val adapters: PlatformAdapters) : TaskRunner {
        override suspend fun run(task: Task): Result<Map<String, Any>> {
            // Resolve the tool by id, then execute it with the arguments.
            // Arguments are carried on the Task's detail map (set by the runtime).
            val tool = adapters.toolRegistry.get(task.id)
                ?: return Result.failure(Exception("unknown tool: ${task.id}"))
            val args = task.detail
            return executeTool(tool, args)
        }

        private suspend fun executeTool(tool: Tool, args: Map<String, Any>): Result<Map<String, Any>> {
            return when (tool.id) {
                "device.info" -> {
                    val deviceInfo = DeviceInfoTool(adapters.context).execute()
                    deviceInfo
                }
                else -> Result.failure(Exception("unsupported tool id: ${tool.id}"))
            }
        }
    }
}
