package org.aethra.core.external

import org.aethra.core.domain.RiskClass

/** Platform-independent contract for externally hosted capabilities. */
interface ExternalCapability {
    val id: String
    val type: ExternalCapabilityType
    val displayName: String
    val capabilities: Set<String>
    suspend fun health(): CapabilityHealth
    suspend fun execute(request: CapabilityRequest): Result<CapabilityResult>
}

enum class ExternalCapabilityType { GITHUB, MCP_SERVER, REMOTE_COMPUTER }
enum class CapabilityHealth { UNKNOWN, READY, DEGRADED, UNAVAILABLE }

data class CapabilityRequest(
    val operation: String,
    val arguments: Map<String, String> = emptyMap(),
    val riskClass: RiskClass = RiskClass.READ_ONLY,
    val requiresApproval: Boolean = false
)

object ExternalCapabilityPolicy {
    private val deniedOperations = setOf(
        "mining", "crypto_mining", "proof_of_work",
        "port_scan", "vulnerability_scan", "dos", "ddos",
        "phishing", "credential_stuffing", "stolen_credentials",
        "bulk_spam", "bulk_account_creation"
    )

    fun allows(request: CapabilityRequest): Boolean {
        val operation = request.operation.trim().lowercase()
        return operation !in deniedOperations &&
            request.arguments.values.none { value ->
                deniedOperations.any { blocked -> value.lowercase().contains(blocked) }
            }
    }
}

data class CapabilityResult(
    val capabilityId: String,
    val operation: String,
    val success: Boolean,
    val output: Map<String, String> = emptyMap(),
    val evidence: List<String> = emptyList(),
    val error: String? = null
)