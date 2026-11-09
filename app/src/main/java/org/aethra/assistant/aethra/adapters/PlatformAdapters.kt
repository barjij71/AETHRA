package org.aethra.assistant.aethra.adapters

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aethra.assistant.aethra.memory.MemoryAndroid
import org.aethra.assistant.aethra.security.ExecutionPolicy
import org.aethra.assistant.aethra.security.PolicyEngine
import org.aethra.assistant.aethra.skills.SkillRegistryAndroid
import org.aethra.assistant.aethra.tools.DeviceInfoTool
import org.aethra.core.agents.AgentEngine
import org.aethra.core.memory.MemoryEngine
import org.aethra.core.providers.ModelRegistry
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.ProviderRegistry
import org.aethra.core.providers.ProviderSystem
import org.aethra.core.runtime.CoreConfig
import org.aethra.core.runtime.Lifecycle
import org.aethra.core.runtime.LifecycleImpl
import org.aethra.core.runtime.events.EventBus
import org.aethra.core.security.AuditLog
import org.aethra.core.security.PermissionBroker
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.aethra.core.task.ProviderTaskRunner

import org.aethra.core.tools.ToolRegistry
import org.aethra.core.skills.SkillRegistry

class AndroidStorage(private val context: Context) : Storage {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aethra.core.v1", Context.MODE_PRIVATE)
    private val lock = Mutex()

    override suspend fun getString(key: String, defaultValue: String?): String? =
        withContext(Dispatchers.IO) { lock.withLock { prefs.getString(key, defaultValue) } }

    override suspend fun setString(key: String, value: String) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putString(key, value).apply() } }
    }

    override suspend fun getLong(key: String, defaultValue: Long?): Long? =
        withContext(Dispatchers.IO) { lock.withLock { prefs.getLong(key, defaultValue ?: 0L) } }

    override suspend fun setLong(key: String, value: Long) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putLong(key, value).apply() } }
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? =
        withContext(Dispatchers.IO) { lock.withLock { prefs.getBoolean(key, defaultValue ?: false) } }

    override suspend fun setBoolean(key: String, value: Boolean) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().putBoolean(key, value).apply() } }
    }

    override suspend fun getAll(): Map<String, String> =
        withContext(Dispatchers.IO) {
            lock.withLock { prefs.all.mapValues { it.value?.toString() ?: "" } }
        }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().remove(key).apply() } }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { lock.withLock { prefs.edit().clear().apply() } }
    }
}

class PlatformAdapters(private val context: Context) {
    val storage = AndroidStorage(context)
    val config = CoreConfig()
    val eventBus = EventBus()
    val memoryEngine = MemoryEngine(storage)
    val toolRegistry = ToolRegistry()
    val providerSystem = ProviderSystem()
    val skillRegistry = SkillRegistry(storage)
    val agentEngine = AgentEngine()
    val auditLog = AuditLog(storage)
    val permissionBroker = PermissionBroker(auditLog)
    val providerRegistry = ProviderRegistry(storage, auditLog)
    val modelRegistry = ModelRegistry(storage, auditLog)
    val providerModelRouter = ProviderModelRouter(providerRegistry, modelRegistry)

    val taskRunner: TaskRunner = AndroidTaskDispatcher(this)
    private lateinit var coreTaskEngine: TaskEngine

    fun bindCoreTaskEngine(taskEngine: TaskEngine) {
        coreTaskEngine = taskEngine
    }

    val lifecycle: Lifecycle = LifecycleImpl(
        storage = storage,
        config = config,
        taskEngineProvider = {
            check(::coreTaskEngine.isInitialized) { "Core task engine must be bound before lifecycle start" }
            coreTaskEngine
        },
        memoryEngine = memoryEngine,
        toolRegistry = toolRegistry,
        providerSystem = providerSystem,
        skillRegistry = skillRegistry,
        agentEngine = agentEngine,
        eventBus = eventBus,
        auditLog = auditLog,
        permissionBroker = permissionBroker
    )

    val executionPolicy = ExecutionPolicy(
        PolicyEngine(),
        permissionBroker,
        auditLog,
        taskRunner
    )

    val memoryAndroid = MemoryAndroid(memoryEngine)
    val skillRegistryAndroid = SkillRegistryAndroid(skillRegistry)

    fun registerAndroidTools() {
        toolRegistry.register(DeviceInfoTool(context).tool)
    }

    inner class AndroidTaskDispatcher(
        private val adapters: PlatformAdapters
    ) : TaskRunner {
        private val providerRunner = ProviderTaskRunner(
            router = adapters.providerModelRouter,
            providerRegistry = adapters.providerRegistry,
            auditLog = adapters.auditLog
        )

        override suspend fun run(task: Task): Result<Map<String, Any>> {
            val toolId = task.goal.trim()
            val tool = adapters.toolRegistry.get(toolId)
            return when (tool?.id) {
                "device.info" -> DeviceInfoTool(adapters.context).execute()
                null -> providerRunner.run(task)
                else -> Result.failure(Exception("unsupported local tool id: " + tool.id))
            }
        }
    }
}
