package org.aethra.core.task

import kotlinx.serialization.Serializable

/**
 * The Core-owned verdict for one execution attempt of a task.
 *
 * This is produced by [TaskVerifier] — never by a Provider, Runner, Agent, or
 * Model — and is the only input that may transition a task to
 * [org.aethra.core.domain.TaskState.COMPLETED].
 *
 * @property outcome the Core-owned verification verdict.
 * @property executionAttemptId the execution attempt this verdict belongs to.
 * @property evidence the observations the verdict was derived from.
 * @property postconditionResults per-postcondition evaluation detail.
 * @property details additional Core-owned diagnostic detail.
 */
@Serializable
data class TaskVerification(
    val outcome: VerificationOutcome,
    val executionAttemptId: String,
    val evidence: List<EvidenceItem> = emptyList(),
    val postconditionResults: List<PostconditionResult> = emptyList(),
    val details: Map<String, String> = emptyMap()
) {
    /**
     * True only when Core confirmed the objective was achieved.
     */
    val isVerified: Boolean get() = outcome == VerificationOutcome.VERIFIED

    /**
     * The evidence items that were missing for at least one postcondition.
     */
    val missingEvidenceTypes: List<String>
        get() = postconditionResults.filter { it.evidenceMissing }.map { it.postcondition.evidenceType }
}
