package org.aethra.core.skills

import java.util.UUID

/** Skills are optional capabilities that extend AETHRA without being bundled into
 *  the core. Each skill declares its identity, version, source, the permissions it
 *  requests, and its dependencies. None execute automatically. */
data class Skill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String = "1.0.0",
    val source: String = "local",
    val enabled: Boolean = true,
    val requestedPermissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

/** Validated source report used before a skill is activated. */
data class SourceReport(
    val source: String,
    val verified: Boolean,
    val license: String = "unknown",
    val dependencies: List<String> = emptyList(),
    val permissionsDeclared: List<String> = emptyList()
)
