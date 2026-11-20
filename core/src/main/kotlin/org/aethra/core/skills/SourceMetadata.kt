package org.aethra.core.skills

import kotlinx.serialization.Serializable

/**
 * Source of a skill. Not a URL parser; authority is supplied by the discovery layer.
 */
@Serializable
data class SourceMetadata(
    val type: String = "local",
    val location: String = "",
    val verified: Boolean = false,
    val license: String = "unknown",
    val local: Boolean = false
)
