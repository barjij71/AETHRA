package org.aethra.core.skills

import kotlinx.serialization.Serializable

/**
 * Integrity metadata attached to a skill manifest.
 */
@Serializable
data class IntegrityInfo(
    val algorithm: String = "sha256",
    val digest: String = ""
)
