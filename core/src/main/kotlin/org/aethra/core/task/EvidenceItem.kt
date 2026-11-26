package org.aethra.core.task

import kotlinx.serialization.Serializable

/**
 * A single immutable piece of evidence observed during one execution attempt.
 *
 * Evidence is an *observation* of the world, not a verdict. Only the Core
 * verifier may turn evidence into a [VerificationOutcome]. Evidence items are
 * append-only within an attempt and are durably persisted with the task.
 *
 * @property type the evidence category, e.g. "provider_response" or "capability_evidence".
 * @property value the observed value.
 * @property source the subsystem that produced the observation, e.g. "runner" or "capability".
 * @property recordedAt wall-clock time the observation was recorded.
 */
@Serializable
data class EvidenceItem(
    val type: String,
    val value: String,
    val source: String = "runner",
    val recordedAt: Long = 0L
) {
    init {
        require(type.isNotBlank()) { "evidence type must not be blank" }
    }

    /**
     * Compact, stable representation used inside the durable task journal.
     * The recordedAt timestamp is intentionally excluded so that the journal
     * stays diff-friendly and the value remains readable.
     */
    fun encode(): String = "$type=$value@$source"

    companion object {
        /**
         * Decode a journal-encoded evidence item. Malformed entries decode to
         * a raw evidence item rather than throwing, so one bad record cannot
         * make the whole task journal unreadable.
         */
        fun decode(encoded: String): EvidenceItem {
            val separator = encoded.indexOf('=')
            if (separator <= 0) return EvidenceItem(type = "raw", value = encoded)
            val type = encoded.substring(0, separator)
            val remainder = encoded.substring(separator + 1)
            val sourceSeparator = remainder.lastIndexOf('@')
            return if (sourceSeparator > 0) {
                EvidenceItem(
                    type = type,
                    value = remainder.substring(0, sourceSeparator),
                    source = remainder.substring(sourceSeparator + 1)
                )
            } else {
                EvidenceItem(type = type, value = remainder)
            }
        }
    }
}
