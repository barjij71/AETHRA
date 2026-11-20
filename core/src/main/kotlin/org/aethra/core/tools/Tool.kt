package org.aethra.core.tools

import org.aethra.core.domain.RiskClass

/**
 * A declared capability. A tool declares what it does, what it can read/write,
 * how risky it is, and the approval path the caller must follow. The registry
 * and broker consult this rather than guessing.
 */

data class Tool(
    val id: String,
    val description: String,
    val category: String,
    val requiredCapabilities: List<String> = emptyList(),
    val inputSchema: Map<String, String> = emptyMap(),
    val outputSchema: Map<String, String> = emptyMap(),
    val riskClass: RiskClass,
    val approvalRequired: Boolean = false,
    val reversible: Boolean = false,
    val timeoutSeconds: Int = 30,
    val native: Boolean = false,
    val aliases: List<String> = emptyList()
)

/**
 * Runtime stats for the registry (useful for the activity/audit view).
 */

data class ToolStats(
    val total: Int,
    val byRisk: Map<RiskClass, Int>
)
