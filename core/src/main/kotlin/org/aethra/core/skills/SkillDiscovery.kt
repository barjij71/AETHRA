package org.aethra.core.skills

import org.aethra.core.domain.RiskClass

/**
 * Abstraction for skill discovery. The discovery layer queries sources
 * (repository, local directory, bundle) and produces [SourceReport].
 *
 * Non-responsibilities of this class:
 * - No network download
 * - No web scraping
 * - No OSINT integration
 * - No skill execution
 */
interface SkillDiscovery {
    /**
     * Scan sources and return a report for each discovered skill.
     * Returns empty list if no sources are configured.
     */
    suspend fun discover(): List<SourceInfo>
}

/** A discovered skill plus its source metadata. */
data class SourceInfo(
    val id: String,
    val name: String,
    val version: String,
    val source: SourceMetadata,
    val description: String = "",
    val entrypoints: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val requestedPermissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val integrity: IntegrityInfo? = null,
    val discoveredAt: Long = System.currentTimeMillis()
)

/**
 * In-memory discovery implementation. Used in tests and for local development.
 * Does not connect to the network.
 */
class InMemorySkillDiscovery : SkillDiscovery {
    private val skills = mutableListOf<SourceInfo>()

    fun add(info: SourceInfo) {
        skills.add(info)
    }

    override suspend fun discover(): List<SourceInfo> = skills.toList()
}
