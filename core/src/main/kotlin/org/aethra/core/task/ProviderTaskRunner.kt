package org.aethra.core.task

import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.RoutingDecision
import org.aethra.core.providers.FallbackDecision
import org.aethra.core.providers.LanguageModelProvider
import org.aethra.core.security.AuditLog
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.AuditEntry
import org.aethra.core.task.Task
import org.aethra.core.providers.ProviderRegistry

class TaskExecutionException(message: String) : RuntimeException(message)

class ProviderTaskRunner(
    private val router: ProviderModelRouter,
    private val providerRegistry: ProviderRegistry,
    private val auditLog: AuditLog,
    private val actor: String = "core"
) : TaskRunner {

    override suspend fun run(task: Task): Result<Map<String, Any>> {
        val primaryDecision = router.resolve(
            org.aethra.core.providers.ConversationProviderConfig(
                conversationId = "default",
                preferredProviderId = null,
                preferredModelId = null,
                hasCustomSelection = false
            ),
            task
        )

        if (primaryDecision is RoutingDecision.UnableToRoute) {
            recordAudit(
                operation = "provider_execute",
                target = "unknown",
                decision = "DENIED",
                outcome = "FAILED_BEFORE_EXECUTION",
                taskId = task.id,
                taskGoal = task.goal,
                riskClass = task.riskClass
            )
            return Result.failure(TaskExecutionException("UnableToRoute: ${primaryDecision.reason}"))
        }

        if (primaryDecision is RoutingDecision.Rejected) {
            recordAudit(
                operation = "provider_execute",
                target = "unknown",
                decision = "DENIED",
                outcome = "POLICY_REJECTED",
                taskId = task.id,
                taskGoal = task.goal,
                riskClass = task.riskClass
            )
            return Result.failure(TaskExecutionException("Rejected: ${primaryDecision.reason}"))
        }

        if (primaryDecision is RoutingDecision.Success) {
            val primaryProvider = primaryDecision.provider
                ?: return Result.failure(TaskExecutionException("Routing returned success without a provider implementation"))
            return executeProvider(primaryProvider, task, primaryDecision.modelId, false, "primary")
        }

        val reason = "Unhandled routing decision: ${primaryDecision::class.simpleName}"
        recordAudit(
            operation = "provider_execute",
            target = "unknown",
            decision = "DENIED",
            outcome = "UNKNOWN_OUTCOME",
            taskId = task.id,
            taskGoal = task.goal,
            riskClass = task.riskClass
        )
        return Result.failure(TaskExecutionException(reason))
    }

    private suspend fun executeProvider(
        provider: LanguageModelProvider,
        task: Task,
        primaryModel: String,
        usedFallback: Boolean,
        attempt: String
    ): Result<Map<String, Any>> {
        val messages = listOf(mapOf("role" to "user", "content" to task.goal))

        val raw = try {
            provider.chat(messages)
        } catch (e: Throwable) {
            return Result.failure(TaskExecutionException("provider.chat() threw: ${e.message ?: "unknown error"}"))
        }

        return if (raw.isSuccess) {
            val reply = raw.getOrDefault(mapOf())
            Result.success(buildResult(provider, task, reply, primaryModel, attempt, usedFallback))
        } else {
            val exception = raw.exceptionOrNull() ?: RuntimeException("provider execution failed")
            checkAndRunFallback(provider, task, exception, attempt, primaryModel, usedFallback)
        }
    }

    private suspend fun checkAndRunFallback(
        primaryProvider: LanguageModelProvider,
        task: Task,
        primaryError: Throwable,
        attempt: String,
        primaryModel: String,
        usedFallback: Boolean
    ): Result<Map<String, Any>> {
        val fallbackCandidate = router.getFallbackCandidate(primaryProvider.id, task)

        return when (fallbackCandidate) {
            is FallbackDecision.Denied -> {
                recordAudit(
                    operation = "provider_fallback",
                    target = "${primaryProvider.id}/${fallbackModelId(primaryProvider)}",
                    decision = "DENIED",
                    outcome = "FAILED_DURING_EXECUTION",
                    taskId = task.id,
                    taskGoal = task.goal,
                    riskClass = task.riskClass
                )
                Result.failure(TaskExecutionException(fallbackCandidate.reason))
            }
            is FallbackDecision.Candidate -> {
                val fallbackProvider = providerRegistry.resolveProvider(fallbackCandidate.providerDef)
                if (fallbackProvider == null) {
                    recordAudit(
                        operation = "provider_fallback",
                        target = "${primaryProvider.id}/${fallbackModelId(primaryProvider)}",
                        decision = "DENIED",
                        outcome = "FAILED_DURING_EXECUTION",
                        taskId = task.id,
                        taskGoal = task.goal,
                        riskClass = task.riskClass
                    )
                    return Result.failure(TaskExecutionException("Fallback provider not registered"))
                }
                // Record fallback success audit first (audit log prepends entries, so newest at index 0)
                recordAudit(
                    operation = "provider_fallback",
                    target = "${fallbackCandidate.providerDef.providerId}/${fallbackCandidate.modelId}",
                    decision = "GRANTED",
                    outcome = "SUCCESS",
                    taskId = task.id,
                    taskGoal = task.goal,
                    riskClass = task.riskClass
                )

                val fallbackResult = executeProvider(fallbackProvider, task, fallbackCandidate.modelId, true, "fallback")
                return fallbackResult.map { reply ->
                    // Record primary failure audit last (so it lands at index 0 in the append-only audit log)
                    recordAudit(
                        operation = "provider_execute",
                        target = "${primaryProvider.id}/${fallbackModelId(primaryProvider)}",
                        decision = "GRANTED",
                        outcome = "FAILED_DURING_EXECUTION",
                        taskId = task.id,
                        taskGoal = task.goal,
                        riskClass = task.riskClass
                    )
                    buildCombinedResult(fallbackCandidate, primaryProvider, task, reply, attempt, "fallback", usedFallback)
                }
            }
        }
    }

    private fun fallbackModelId(provider: LanguageModelProvider): String {
        return provider.models.firstOrNull() ?: "unknown"
    }

    private fun buildResult(
        provider: LanguageModelProvider,
        task: Task,
        reply: Map<String, Any>,
        primaryModel: String,
        attempt: String,
        usedFallback: Boolean
    ): Map<String, Any> {
        return reply + mutableMapOf(
            "taskId" to task.id,
            "taskGoal" to task.goal,
            "riskClass" to task.riskClass.name,
            "providerId" to provider.id,
            "modelId" to primaryModel,
            "usedFallback" to usedFallback,
            "attempt" to attempt,
            "attempts" to listOf(attempt)
        )
    }

    private fun buildCombinedResult(
        fallbackCandidate: FallbackDecision.Candidate,
        primaryProvider: LanguageModelProvider,
        task: Task,
        reply: Map<String, Any>,
        attempt1: String,
        attempt2: String,
        usedFallback: Boolean
    ): Map<String, Any> {
        return reply + mutableMapOf(
            "taskId" to task.id,
            "taskGoal" to task.goal,
            "riskClass" to task.riskClass.name,
            "providerId" to fallbackCandidate.providerDef.providerId,
            "modelId" to fallbackCandidate.modelId,
            "usedFallback" to true,
            "fallbackProviderId" to fallbackCandidate.providerDef.providerId,
            "fallbackModelId" to fallbackCandidate.modelId,
            "attempt1" to attempt1,
            "attempt2" to attempt2,
            "attempts" to listOf(attempt1, attempt2)
        )
    }

    private suspend fun recordAudit(
        operation: String,
        target: String,
        decision: String,
        outcome: String,
        taskId: String,
        taskGoal: String,
        riskClass: RiskClass
    ) {
        val entry = AuditEntry(
            id = java.util.UUID.randomUUID().toString(),
            actor = actor,
            operation = operation,
            target = target,
            riskClass = riskClass,
            decision = decision,
            outcome = outcome,
            timestamp = System.currentTimeMillis()
        )
        auditLog.record(entry)
    }
}
