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
import org.aethra.assistant.aethra.tools.ClipboardReadTool
import org.aethra.assistant.aethra.tools.DeviceInfoTool
import org.aethra.assistant.aethra.tools.FileReadTool
import org.aethra.core.agents.AgentEngine
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
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
import org.aethra.core.skills.Skill
import org.aethra.core.skills.SkillState
import org.aethra.core.storage.Storage
import org.aethra.core.task.Task
import org.aethra.core.task.TaskEngine
import org.aethra.core.task.TaskRunner
import org.aethra.core.task.ProviderTaskRunner

import org.aethra.core.tools.Tool
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

    val policyEngine = PolicyEngine()

    val memoryAndroid = MemoryAndroid(memoryEngine)
    val skillRegistryAndroid = SkillRegistryAndroid(skillRegistry)

    /** Android-implemented native tools: declared in core NativeTools, executed here. */
    private val androidToolInstances = listOf(
        DeviceInfoTool(context),
        FileReadTool(context),
        ClipboardReadTool(context)
    )

    fun registerAndroidTools() {
        for (instance in androidToolInstances) {
            toolRegistry.register(instance.tool)
        }
        registerBuiltinSkills()
    }

    /**
     * Register real built-in skills backed by native Android tools through the
     * existing SkillRegistry architecture (INSTALLED state, persisted). A
     * skill's capabilities map to ToolRegistry tools, so execution stays on
     * the normal Skill -> ToolRegistry -> policy -> AuditLog path. No fake
     * skill list is exposed to the UI: these skills are executable via the
     * normal task path and can be enabled/disabled through the registry.
     */
    private fun registerBuiltinSkills() {
        for (instance in androidToolInstances) {
            val tool = instance.tool
            skillRegistryAndroid.register(
                Skill(
                    id = "builtin." + tool.id,
                    name = "Android " + tool.category + " " + tool.id,
                    description = tool.description,
                    entrypoints = listOf(tool.id),
                    capabilities = tool.requiredCapabilities,
                    platforms = listOf("android"),
                    riskClass = tool.riskClass,
                    state = SkillState.INSTALLED,
                    enabled = true
                )
            )
        }
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
            val goal = task.goal.trim()
            val tool = resolveTool(goal)
            if (tool != null) return dispatchTool(tool, task, goal)
            return providerRunner.run(task)
        }

        /** Exact tool id first, then a registered skill mapped via its capabilities. */
        private fun resolveTool(goal: String): Tool? {
            adapters.toolRegistry.get(goal)?.let { return it }
            val skill = adapters.skillRegistryAndroid.find(goal)
                ?: adapters.skillRegistryAndroid.get(goal)
            val mapped = skill?.let { adapters.toolRegistry.toolsForSkill(it).firstOrNull() }
            return mapped?.let { adapters.toolRegistry.get(it) }
        }

        /**
         * Dispatch a native tool through the existing policy path:
         * PolicyEngine -> audit -> execution. No privileged action runs without
         * its declared risk being checked. Because there is no approval UI on
         * this branch yet, approval-required tools fail truthfully instead of
         * bypassing the approval gate.
         */
        private suspend fun dispatchTool(tool: Tool, task: Task, goal: String): Result<Map<String, Any>> {
            if (!adapters.policyEngine.allows(tool.riskClass, tool)) {
                adapters.auditLog.record(
                    entry("tool.policy.reject", task.id, tool.id, tool.riskClass, "DENIED", "policy rejected")
                )
                return Result.failure(Exception("policy.reject: " + tool.id))
            }
            if (tool.approvalRequired || tool.riskClass.level >= RiskClass.SENSITIVE.level) {
                adapters.auditLog.record(
                    entry("tool.permission.request", task.id, tool.id, tool.riskClass, "PENDING", "approval required")
                )
                return Result.failure(Exception("permission_required: " + tool.id))
            }
            adapters.auditLog.record(
                entry("tool.execution.grant", task.id, tool.id, tool.riskClass, "GRANTED", "approved")
            )
            val result = when (tool.id) {
                "device.info" -> DeviceInfoTool(context).execute()
                "file.read" -> FileReadTool(context).execute(toolArgs(tool, goal)["path"] ?: "")
                "clipboard.read" -> ClipboardReadTool(context).execute()
                else -> return Result.failure(Exception(
                    "tool " + tool.id + " is declared but has no Android implementation yet"))
            }
            if (result.isSuccess) {
                adapters.auditLog.record(
                    entry("tool.execution.success", task.id, tool.id, tool.riskClass, "GRANTED", "success")
                )
            } else {
                adapters.auditLog.record(
                    entry(
                        "tool.execution.failure", task.id, tool.id, tool.riskClass, "DENIED",
                        result.exceptionOrNull()?.message ?: "execution error"
                    )
                )
            }
            return result
        }

        /** Trailing text after the tool id in the goal is passed as tool args. */
        private fun toolArgs(tool: Tool, goal: String): Map<String, String> {
            val rest = goal.removePrefix(tool.id).trim()
            return if (rest.isEmpty()) emptyMap() else mapOf("path" to rest)
        }

        private fun entry(
            operation: String,
            target: String,
            toolId: String,
            risk: RiskClass,
            decision: String,
            outcome: String
        ): AuditEntry = AuditEntry(
            id = java.util.UUID.randomUUID().toString(),
            actor = "aethra.runtime",
            operation = operation,
            target = target,
            riskClass = risk,
            decision = decision,
            outcome = outcome
        )
    }
}
