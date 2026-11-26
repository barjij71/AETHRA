package org.aethra.core.task

/**
 * A Core-owned rule that decides whether an execution attempt achieved its
 * real-world objective.
 *
 * Implementations live in Core. A Provider, Runner, Agent, or Model can never
 * supply or replace a policy: it may only contribute [EvidenceItem]
 * observations, which the policy evaluates.
 */
interface VerificationPolicy {
    /** Stable identifier of this policy. */
    val id: String

    /**
     * Evaluates the collected evidence for one execution attempt.
     *
     * @param task the task being verified.
     * @param evidence the observations collected for this attempt.
     */
    fun evaluate(task: Task, evidence: List<EvidenceItem>): PolicyVerdict
}

/**
 * The decision produced by a [VerificationPolicy].
 *
 * @property outcome the Core-owned verdict.
 * @property postconditionResults per-postcondition evaluation detail.
 * @property details additional Core-owned diagnostic detail.
 */
data class PolicyVerdict(
    val outcome: VerificationOutcome,
    val postconditionResults: List<PostconditionResult> = emptyList(),
    val details: Map<String, String> = emptyMap()
)
