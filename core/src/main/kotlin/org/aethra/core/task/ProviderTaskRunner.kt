package org.aethra.core.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.providers.FallbackDecision
import org.aethra.core.providers.LanguageModelProvider
import org.aethra.core.providers.ProviderFailure
import org.aethra.core.providers.ProviderFailureKind
import org.aethra.core.providers.ProviderInvocationException
import org.aethra.core.providers.ProviderModelRouter
import org.aethra.core.providers.ProviderRegistry
import org.aethra.core.providers.ProviderRequest
import org.aethra.core.providers.ProviderResponse
import org.aethra.core.providers.ProviderSelectionContext
import org.aethra.core.providers.ProviderStreamEvent
import org.aethra.core.providers.RoutingDecision
import org.aethra.core.security.AuditLog
import java.util.UUID

class TaskExecutionException(
    message: String,
    val providerFailureKind: ProviderFailureKind? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class ProviderTaskRunner(
    private val router: ProviderModelRouter,
    private val providerRegistry: ProviderRegistry,
    private val auditLog: AuditLog,
    private val actor: String = "core",
    private val selectionContext: ProviderSelectionContext = ProviderSelectionContext()
) : TaskRunner {

    override suspend fun run(task: Task): Result<Map<String, Any>> {
        val primaryDecision = router.resolve(
            org.aethra.core.providers.ConversationProviderConfig(
                conversationId = "default",
                preferredProviderId = null,
                preferredModelId = null,
                hasCustomSelection = false,
                selectionContext = selectionContext
            ),
            task
        )

        if (primaryDecision is RoutingDecision.UnableToRoute) {
            val failure = TaskExecutionException(
                "UnableToRoute: ${primaryDecision.reason}",
                ProviderFailureKind.PROVIDER_UNAVAILABLE
            )
            recordAudit("provider_select", "unknown", "DENIED", "PROVIDER_UNAVAILABLE", task)
            recordFinalOutcome(task, "unknown", "FAILED_PROVIDER_UNAVAILABLE")
            return Result.failure(failure)
        }
        if (primaryDecision is RoutingDecision.Rejected) {
            val failure = TaskExecutionException("Rejected: ${primaryDecision.reason}")
            recordAudit("provider_select", "unknown", "DENIED", "POLICY_REJECTED", task)
            recordFinalOutcome(task, "unknown", "REJECTED")
            return Result.failure(failure)
        }
        if (primaryDecision !is RoutingDecision.Success) {
            val failure = TaskExecutionException("Unhandled routing decision: ${primaryDecision::class.simpleName}")
            recordAudit("provider_select", "unknown", "DENIED", "UNKNOWN_OUTCOME", task)
            recordFinalOutcome(task, "unknown", "FAILED")
            return Result.failure(failure)
        }

        val primaryProvider = primaryDecision.provider
        if (primaryProvider == null) {
            val failure = TaskExecutionException(
                "Routing returned success without a provider implementation",
                ProviderFailureKind.PROVIDER_UNAVAILABLE
            )
            recordAudit("provider_select", "${primaryDecision.providerId}/${primaryDecision.modelId}", "DENIED", "PROVIDER_UNAVAILABLE", task)
            recordFinalOutcome(task, "unknown", "FAILED_PROVIDER_UNAVAILABLE")
            return Result.failure(failure)
        }

        val primaryTarget = "${primaryDecision.providerId}/${primaryDecision.modelId}"
        recordAudit("provider_select", primaryTarget, "GRANTED", "PRIMARY_SELECTED", task)
        val primaryResult = try {
            executeProvider(primaryProvider, task, primaryDecision.modelId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recordFinalOutcome(task, primaryTarget, "CANCELLED") }
            throw cancelled
        }
        if (primaryResult.isSuccess) {
            val result = buildResult(
                primaryProvider,
                task,
                primaryResult.getOrThrow(),
                primaryDecision.modelId,
                usedFallback = false,
                primaryProviderId = primaryDecision.providerId,
                primaryModelId = primaryDecision.modelId
            )
            recordFinalOutcome(task, primaryTarget, "SUCCESS_PRIMARY")
            return Result.success(result)
        }

        val primaryError = primaryResult.exceptionOrNull() ?: IllegalStateException("Provider invocation failed")
        if (primaryError is CancellationException) throw primaryError
        val fallbackResult = try {
            checkAndRunFallback(
                primaryProvider,
                task,
                primaryError,
                primaryDecision.modelId,
                selectionContext
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { recordFinalOutcome(task, primaryTarget, "CANCELLED") }
            throw cancelled
        }
        return fallbackResult.fold(
            onSuccess = { result ->
                recordFinalOutcome(task, "${result["providerId"]}/${result["modelId"]}", "SUCCESS_FALLBACK")
                Result.success(result)
            },
            onFailure = { error ->
                recordFinalOutcome(task, primaryTarget, "FAILED")
                Result.failure(error)
            }
        )
    }

    private suspend fun executeProvider(
        provider: LanguageModelProvider,
        task: Task,
        modelId: String
    ): Result<ProviderResponse> {
        val target = "${provider.id}/$modelId"
        val request = ProviderRequest(messages = listOf(mapOf("role" to "user", "content" to task.goal)))
        val response = try {
            if (provider.supportsStreaming) collectStream(provider, request)
            else invoke(provider, request)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                recordAudit("provider_execute", target, "GRANTED", "CANCELLED", task)
            }
            throw cancelled
        }
        recordAudit(
            operation = "provider_execute",
            target = target,
            decision = "GRANTED",
            outcome = if (response.isSuccess) "SUCCESS" else "FAILED_DURING_EXECUTION",
            task = task
        )
        return response
    }

    private suspend fun invoke(
        provider: LanguageModelProvider,
        request: ProviderRequest
    ): Result<ProviderResponse> = try {
        provider.invoke(request).mapFailureAs(ProviderFailureKind.INVOCATION)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(asProviderFailure(error, ProviderFailureKind.INVOCATION))
    }

    private suspend fun collectStream(
        provider: LanguageModelProvider,
        request: ProviderRequest
    ): Result<ProviderResponse> {
        val content = StringBuilder()
        val toolCalls = mutableListOf<org.aethra.core.providers.ProviderToolCall>()
        var completed: ProviderResponse? = null
        var providerFailure: ProviderFailure? = null
        var cancelled = false
        try {
            provider.stream(request).collect { event ->
                when (event) {
                    is ProviderStreamEvent.TextChunk -> content.append(event.text)
                    is ProviderStreamEvent.ToolCallRequested -> toolCalls.add(event.call)
                    is ProviderStreamEvent.Completed -> {
                        if (completed != null) {
                            providerFailure = ProviderFailure(
                                ProviderFailureKind.STREAMING,
                                "Provider stream emitted more than one completion"
                            )
                        } else completed = event.response
                    }
                    is ProviderStreamEvent.Failed -> providerFailure = event.failure
                    ProviderStreamEvent.Cancelled -> cancelled = true
                }
            }
        } catch (cancelledException: CancellationException) {
            throw cancelledException
        } catch (error: Throwable) {
            return Result.failure(asProviderFailure(error, ProviderFailureKind.STREAMING))
        }
        if (cancelled) throw CancellationException("Provider stream cancelled")
        providerFailure?.let {
            return Result.failure(ProviderInvocationException(it.kind, it.message, it.cause))
        }
        val response = completed ?: return Result.failure(
            ProviderInvocationException(ProviderFailureKind.STREAMING, "Provider stream ended without completion")
        )
        return Result.success(
            response.copy(
                content = response.content ?: content.takeIf { it.isNotEmpty() }?.toString(),
                toolCalls = response.toolCalls.ifEmpty { toolCalls }
            )
        )
    }

    private fun Result<ProviderResponse>.mapFailureAs(kind: ProviderFailureKind): Result<ProviderResponse> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is CancellationException) throw error
                Result.failure(
                    if (error is ProviderInvocationException) error
                    else asProviderFailure(error, kind)
                )
            }
        )

    private fun asProviderFailure(
        error: Throwable,
        kind: ProviderFailureKind,
        message: String = error.message ?: "Provider failed"
    ): ProviderInvocationException = if (error is ProviderInvocationException) error
    else ProviderInvocationException(kind, message, error)

    private suspend fun checkAndRunFallback(
        primaryProvider: LanguageModelProvider,
        task: Task,
        primaryError: Throwable,
        primaryModel: String,
        selectionContext: ProviderSelectionContext
    ): Result<Map<String, Any>> {
        val failureKind = (primaryError as? ProviderInvocationException)?.kind ?: ProviderFailureKind.INVOCATION
        if (failureKind !in setOf(ProviderFailureKind.INVOCATION, ProviderFailureKind.STREAMING)) {
            recordAudit(
                "provider_fallback", "${primaryProvider.id}/$primaryModel", "DENIED",
                "NON_FALLBACK_FAILURE_${failureKind.name}", task
            )
            return Result.failure(TaskExecutionException(
                "Fallback is not allowed for provider failure category ${failureKind.name}",
                failureKind,
                primaryError
            ))
        }

        return when (
            val decision = router.getFallbackCandidate(
                primaryProvider.id,
                task,
                selectionContext
            )
        ) {
            is FallbackDecision.Denied -> {
                recordAudit(
                    "provider_fallback", "${primaryProvider.id}/$primaryModel", "DENIED",
                    "POLICY_REJECTED:${decision.blockedBy}", task
                )
                Result.failure(TaskExecutionException(decision.reason, failureKind, primaryError))
            }
            is FallbackDecision.Candidate -> {
                val fallbackTarget = "${decision.providerDef.providerId}/${decision.modelId}"
                val fallbackProvider = try {
                    providerRegistry.resolveProvider(decision.providerDef)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    null
                }
                if (fallbackProvider == null) {
                    recordAudit(
                        "provider_fallback", fallbackTarget, "DENIED", "PROVIDER_UNAVAILABLE", task
                    )
                    return Result.failure(TaskExecutionException(
                        "Fallback provider has no runtime implementation",
                        ProviderFailureKind.PROVIDER_UNAVAILABLE,
                        primaryError
                    ))
                }

                recordAudit("provider_fallback", fallbackTarget, "GRANTED", "POLICY_APPROVED", task)
                val fallbackResult = executeProvider(fallbackProvider, task, decision.modelId)
                fallbackResult.fold(
                    onSuccess = { response ->
                        Result.success(
                            buildResult(
                                fallbackProvider,
                                task,
                                response,
                                decision.modelId,
                                usedFallback = true,
                                primaryProviderId = primaryProvider.id,
                                primaryModelId = primaryModel,
                                fallbackProviderId = decision.providerDef.providerId,
                                fallbackModelId = decision.modelId
                            )
                        )
                    },
                    onFailure = { error ->
                        Result.failure(TaskExecutionException(
                            "Primary and fallback provider executions failed",
                            (error as? ProviderInvocationException)?.kind ?: ProviderFailureKind.INVOCATION,
                            error
                        ))
                    }
                )
            }
        }
    }

    private fun buildResult(
        provider: LanguageModelProvider,
        task: Task,
        response: ProviderResponse,
        modelId: String,
        usedFallback: Boolean,
        primaryProviderId: String,
        primaryModelId: String,
        fallbackProviderId: String? = null,
        fallbackModelId: String? = null
    ): Map<String, Any> {
        val raw = response.metadata.toMutableMap()
        response.content?.let { raw["content"] = it }
        if (response.toolCalls.isNotEmpty()) raw["toolCalls"] = response.toolCalls
        val result = raw.toMutableMap()
        result.putAll(mapOf(
            "taskId" to task.id,
            "taskGoal" to task.goal,
            "riskClass" to task.riskClass.name,
            "providerId" to provider.id,
            "modelId" to modelId,
            "primaryProviderId" to primaryProviderId,
            "primaryModelId" to primaryModelId,
            "usedFallback" to usedFallback,
            "attempt" to if (usedFallback) "fallback" else "primary",
            "attempts" to if (usedFallback) listOf("primary", "fallback") else listOf("primary")
        ))
        if (fallbackProviderId != null) result["fallbackProviderId"] = fallbackProviderId
        if (fallbackModelId != null) result["fallbackModelId"] = fallbackModelId
        if (usedFallback) {
            result["attempt1"] = "primary"
            result["attempt2"] = "fallback"
        }
        return result
    }

    private suspend fun recordFinalOutcome(task: Task, target: String, outcome: String) {
        recordAudit("provider_result", target, "GRANTED", outcome, task)
    }

    private suspend fun recordAudit(
        operation: String,
        target: String,
        decision: String,
        outcome: String,
        task: Task
    ) {
        auditLog.record(
            AuditEntry(
                id = UUID.randomUUID().toString(),
                actor = actor,
                operation = operation,
                target = target,
                riskClass = task.riskClass,
                decision = decision,
                outcome = outcome,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
