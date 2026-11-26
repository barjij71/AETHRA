package org.aethra.core.task

import kotlinx.serialization.Serializable

/**
 * The outcome of a Core-owned verification pass over one execution attempt.
 *
 * This vocabulary is deliberately distinct from the runner's own execution
 * result. A runner may report "the call returned without throwing"; it can
 * never assert "the real-world objective is verified". Only Core produces a
 * [VerificationOutcome], by evaluating Core-owned policies against collected
 * evidence.
 */
@Serializable
enum class VerificationOutcome {
    /**
     * Core confirmed every declared postcondition against the collected
     * evidence. The objective was actually achieved.
     */
    VERIFIED,

    /**
     * Core found a contradiction: the execution failed, a declared
     * postcondition was not satisfied, or the runner reported a failure.
     */
    FAILED,

    /**
     * Core could not decide. The evidence needed to evaluate a declared
     * postcondition was not available.
     */
    UNKNOWN,

    /**
     * No Core verification policy exists for the task's verification profile.
     * The task is never reported as verified in this state.
     */
    UNVERIFIABLE;

    /**
     * Stable serialized form used by the durable task journal.
     */
    val serializedName: String
        get() = name

    companion object {
        /**
         * Parse a persisted outcome name. Unknown values degrade to [UNKNOWN]
         * so a corrupted or newer journal can never be read as verified.
         */
        fun fromSerialized(name: String?): VerificationOutcome =
            entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
