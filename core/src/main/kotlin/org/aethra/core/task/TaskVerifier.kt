package org.aethra.core.task

/**
 * The Core-owned verification authority.
 *
 * [TaskVerifier] is the *only* component in AETHRA that may turn an execution
 * result into a [VerificationOutcome]. It is invoked by [TaskEngine] between
 * `runner.run(task)` and the terminal transition, so no Provider, Runner,
 * Agent, or Model can assert that a real-world objective was achieved.
 *
 * ### Trust boundary
 * - The runner returns *observations* (the result map). It never returns a verdict.
 * - [EvidenceCollector] (Core) turns those observations into [EvidenceItem]s.
 * - A Core-owned [VerificationPolicy] evaluates the evidence against Core-declared
 *   [Postcondition]s and produces the verdict.
 * - [TaskEngine] applies the verdict; only `VERIFIED` may complete a task.
 *
 * ### Default semantics
 * When a task declares no postconditions, verification falls back to the
 * execution-level baseline: a successful runner result with no reported
 * failure is `VERIFIED`, while an explicitly reported failure (`success` =
 * false, or a non-blank `error`) is `FAILED`. A runner that reports nothing
 * about failure is trusted only to the extent that the execution returned
 * normally — it cannot manufacture evidence it did not produce.
 */
class TaskVerifier(
    private val evidenceCollector: EvidenceCollector = EvidenceCollector(),
    private val policy: VerificationPolicy = ExecutionOutcomePolicy()
) {
    /**
     * Verifies one execution attempt of [task].
     *
     * @param task the task whose attempt is being verified.
     * @param result the runner's raw result. This is *input evidence*, never a verdict.
     * @param executionAttemptId the attempt identity this verdict belongs to.
     */
    fun verify(
        task: Task,
        result: Result<Map<String, Any>>,
        executionAttemptId: String
    ): TaskVerification {
        val evidence = evidenceCollector.collect(result, executionAttemptId)
        val verdict = policy.evaluate(task, evidence)
        return TaskVerification(
            outcome = verdict.outcome,
            executionAttemptId = executionAttemptId,
            evidence = evidence,
            postconditionResults = verdict.postconditionResults,
            details = verdict.details
        )
    }
}

/**
 * Collects raw runner observations into immutable [EvidenceItem]s.
 *
 * This lives in Core and defines the only evidence vocabulary the verifier
 * understands. It never interprets the runner's report as a verdict; it only
 * records what was observed.
 */
class EvidenceCollector {
    /**
     * Collects evidence from a runner result.
     *
     * A failed result contributes a single `execution_failure` item; a
     * successful result contributes the reported outcome plus every declared
     * evidence entry.
     */
    fun collect(result: Result<Map<String, Any>>, executionAttemptId: String): List<EvidenceItem> {
        val failure = result.exceptionOrNull()
        if (failure != null) {
            return listOf(
                EvidenceItem(
                    type = "execution_failure",
                    value = failure.message ?: failure::class.simpleName ?: "unknown failure",
                    source = "runner",
                    recordedAt = System.currentTimeMillis(),
                    executionAttemptId = executionAttemptId
                )
            )
        }

        val payload = result.getOrNull() ?: emptyMap()
        val now = System.currentTimeMillis()
        val evidence = mutableListOf<EvidenceItem>()

        // The runner's own reported outcome is recorded as an observation.
        // It is never used as the verdict: the policy decides.
        payload["success"]?.let { reported ->
            evidence += EvidenceItem(
                type = "reported_success",
                value = reported.toString(),
                source = "runner",
                recordedAt = now,
                executionAttemptId = executionAttemptId
            )
        }
        payload["error"]?.let { reported ->
            val text = reported.toString()
            if (text.isNotBlank()) {
                evidence += EvidenceItem(
                    type = "reported_error",
                    value = text,
                    source = "runner",
                    recordedAt = now,
                    executionAttemptId = executionAttemptId
                )
            }
        }

        // Explicit evidence entries (e.g. capability evidence such as "http:200").
        // Entries are parsed into typed observations so that a Core-declared
        // Postcondition can name the evidence type it requires. The runner
        // chooses the observation, never the verdict: the policy still decides.
        @Suppress("UNCHECKED_CAST")
        (payload["evidence"] as? List<Any>)?.forEach { entry ->
            val raw = entry.toString()
            val separator = raw.indexOfFirstSeparator()
            if (separator > 0) {
                evidence += EvidenceItem(
                    type = raw.substring(0, separator),
                    value = raw.substring(separator + 1),
                    source = "runner",
                    recordedAt = now,
                    executionAttemptId = executionAttemptId
                )
            } else {
                evidence += EvidenceItem(
                    type = "runner_evidence",
                    value = raw,
                    source = "runner",
                    recordedAt = now,
                    executionAttemptId = executionAttemptId
                )
            }
        }

        // The objective-relevant output, when the runner produced one.
        payload["output"]?.let { output ->
            evidence += EvidenceItem(
                type = "output",
                value = output.toString(),
                source = "runner",
                recordedAt = now,
                executionAttemptId = executionAttemptId
            )
        }

        return evidence
    }

    /**
     * Finds the index of the first "type" separator in a raw evidence entry.
     * Both "type:value" and "type=value" conventions are accepted; the
     * earliest separator wins so that values may contain the other character.
     */
    private fun String.indexOfFirstSeparator(): Int {
        val colon = indexOf(':')
        val equals = indexOf('=')
        return when {
            colon < 0 -> equals
            equals < 0 -> colon
            else -> minOf(colon, equals)
        }
    }
}

/**
 * The default Core-owned policy.
 *
 * It evaluates the task's Core-declared [Postcondition]s against the collected
 * evidence. When a task declares no postconditions, it applies the
 * execution-level baseline described on [TaskVerifier].
 */
class ExecutionOutcomePolicy : VerificationPolicy {
    override val id: String = "core.execution-outcome.v1"

    override fun evaluate(task: Task, evidence: List<EvidenceItem>): PolicyVerdict {
        val failure = evidence.firstOrNull { it.type == "execution_failure" }
        if (failure != null) {
            return PolicyVerdict(
                outcome = VerificationOutcome.FAILED,
                details = mapOf("reason" to failure.value)
            )
        }

        val declared = task.postconditions
        if (declared.isEmpty()) {
            return evaluateBaseline(evidence)
        }

        val results = declared.map { postcondition ->
            val satisfied = postcondition.evaluate(evidence)
            PostconditionResult(
                postcondition = postcondition,
                satisfied = satisfied == true,
                evidenceMissing = satisfied == null
            )
        }

        val unmet = results.filter { !it.satisfied && !it.evidenceMissing }
        val missing = results.filter { it.evidenceMissing }

        val outcome = when {
            unmet.isNotEmpty() -> VerificationOutcome.FAILED
            missing.isNotEmpty() -> VerificationOutcome.UNKNOWN
            else -> VerificationOutcome.VERIFIED
        }

        val details = buildMap {
            unmet.firstOrNull()?.let {
                put("reason", "postcondition not satisfied: ${it.postcondition.name}")
            }
            missing.firstOrNull()?.let {
                put("reason", "missing evidence: ${it.postcondition.evidenceType}")
            }
        }

        return PolicyVerdict(
            outcome = outcome,
            postconditionResults = results,
            details = details
        )
    }

    /**
     * Execution-level baseline for tasks that declare no postconditions.
     *
     * The runner cannot assert success on its own: only the absence of a
     * reported failure, combined with a normally-returning execution, counts.
     */
    private fun evaluateBaseline(evidence: List<EvidenceItem>): PolicyVerdict {
        val reportedError = evidence.firstOrNull { it.type == "reported_error" }
        if (reportedError != null) {
            return PolicyVerdict(
                outcome = VerificationOutcome.FAILED,
                details = mapOf("reason" to reportedError.value)
            )
        }

        val reportedSuccess = evidence.firstOrNull { it.type == "reported_success" }
        if (reportedSuccess != null && reportedSuccess.value.equals("false", ignoreCase = true)) {
            return PolicyVerdict(
                outcome = VerificationOutcome.FAILED,
                details = mapOf("reason" to "runner reported success=false")
            )
        }

        return PolicyVerdict(
            outcome = VerificationOutcome.VERIFIED,
            details = mapOf("reason" to "execution completed without reported failure")
        )
    }
}
