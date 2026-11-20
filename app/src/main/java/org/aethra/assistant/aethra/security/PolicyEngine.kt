package org.aethra.assistant.aethra.security

import org.aethra.core.domain.RiskClass
import org.aethra.core.tools.Tool

class PolicyEngine {
    companion object {
        private val ACCEPTED_SCHEME = Regex("^[a-z0-9][a-z0-9.+-]*$")
        private val DISALLOWED_PATTERNS = listOf(
            Regex("""\s"""),
            Regex("""/\*|&&|\|\||;"""),
            Regex("""(\.\./|\.\.\\).*""")
        )
    }

    fun allows(risk: RiskClass, tool: Tool): Boolean =
        allows(risk, tool.id) && risk != RiskClass.DESTRUCTIVE

    fun allows(risk: RiskClass, permissionId: String): Boolean {
        if (!ACCEPTED_SCHEME.matches(permissionId)) return false
        if (permissionId.contains("shell", ignoreCase = true) ||
            permissionId.contains("exec", ignoreCase = true) ||
            permissionId.contains("run", ignoreCase = true)
        ) return false
        if (DISALLOWED_PATTERNS.any { it.containsMatchIn(permissionId) }) return false
        return risk != RiskClass.DESTRUCTIVE
    }
}
