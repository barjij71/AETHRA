package org.aethra.core.task

import kotlinx.serialization.Serializable

/**
 * A Core-owned postcondition: an expectation about the real-world objective
 * that must hold for the task to count as verified.
 *
 * Postconditions are declared by Core (the task creator / verifier), never by
 * the Provider, Runner, Agent, or Model. The runner may only contribute
 * [EvidenceItem] observations that the Core verifier evaluates against these
 * expectations.
 *
 * @property name stable identifier of the postcondition.
 * @property description human-readable statement of what must hold.
 * @property evidenceType the evidence category this postcondition is evaluated from.
 * @property expectedValue the value the evidence must carry; null means "any non-blank value".
 */
@Serializable
data class Postcondition(
    val name: String,
    val description: String,
    val evidenceType: String,
    val expectedValue: String? = null
) {
    init {
        require(name.isNotBlank()) { "postcondition name must not be blank" }
        require(evidenceType.isNotBlank()) { "postcondition evidenceType must not be blank" }
    }

    /**
     * Evaluates this postcondition against the evidence observed for one
     * execution attempt. Returns null when no evidence of the required type
     * was observed at all, which the caller must treat as UNKNOWN rather than
     * as a pass or a failure.
     */
    fun evaluate(evidence: List<EvidenceItem>): Boolean? {
        val matching = evidence.filter { it.type == evidenceType }
        if (matching.isEmpty()) return null
        val expected = expectedValue
        return if (expected == null) {
            matching.any { it.value.isNotBlank() }
        } else {
            matching.any { it.value == expected }
        }
    }
}

/**
 * The result of evaluating a single [Postcondition] against the evidence of
 * one execution attempt.
 *
 * @property postcondition the evaluated expectation.
 * @property satisfied true when the evidence satisfied the expectation.
 * @property evidenceMissing true when no evidence of the required type was observed.
 */
@Serializable
data class PostconditionResult(
    val postcondition: Postcondition,
    val satisfied: Boolean,
    val evidenceMissing: Boolean
)
