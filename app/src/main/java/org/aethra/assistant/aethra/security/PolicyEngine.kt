package org.aethra.assistant.aethra.security

import org.aethra.core.domain.RiskClass
import org.aethra.core.security.AuditEntry
import org.aethra.core.security.PermissionBroker
import org.aethra.core.tools.Tool
import org.aethra.core.task.Task
import java.util.UUID

/**
 * Execution policy: the first gate in the security pipeline.
 *
 * Policy → Permission → Audit → Execution.
 *
 * The policy denies, by design and from the very start of the project:
 *   - unrestricted shell
 *   - wildcard / arbitrary path access
 *   - code download / arbitrary code execution from a Skill package
 *   - CORS-style unrestricted access
 *   - any sensitive or destructive action without approval
 *
 * It is a pure decision engine; it never executes. It is fully unit-testable
 * without any Android dependency.
 */
class PolicyEngine {
    companion object {
        // Acceptable tool identifier shapes. Anything else is rejected by default.
        private val ACCEPTED_SCHEME = Regex("^[a-z0-9][a-z0-9.+-]*$")
        private val DISALLOWED_PATTERNS = listOf(
            Regex("""\s"""),                 // whitespace (often used to smuggle commands)
            Regex("""/\*|&&|\|\||;``|`"""), // shell chaining / substitution
            Regex("""(\.\./|\.\.\\\\).*""") // path traversal
        )
    }

    /**
     * Decide whether a tool may run under the policy.
     *
     * @param risk the tool's declared RiskClass
     * @param tool the tool declaration
     * @return true if the tool may run
     */
    fun allows(risk: RiskClass, tool: Tool): Boolean {
        val id = tool.id

        // 1. Identifier policy: only recognised, safe-shaped tool ids pass.
        if (!ACCEPTED_SCHEME.matches(id)) return false

        // 2. Shell/unrestricted access is always denied.
        if (id.contains("shell", ignoreCase = true)
            || id.contains("exec", ignoreCase = true)
            || id.contains("run", ignoreCase = true)
        ) return false

        // 3. Path traversal is always denied.
        for (p in DISALLOWED_PATTERNS) {
            if (p.containsMatchIn(id)) return false
        }

        // 4. Risk-based gate (least privilege).
        return when (risk) {
            RiskClass.READ_ONLY -> true
            RiskClass.REVERSIBLE -> true
            RiskClass.SENSITIVE -> true   // approval required by the broker
            RiskClass.DESTRUCTIVE -> false
        }
    }

    /** Helper: build an audit entry for the security pipeline. */
    fun auditEntry(actor: String, operation: String, target: String, risk: RiskClass, decision: String, outcome: String): AuditEntry {
        return AuditEntry(
            id = UUID.randomUUID().toString(),
            actor = actor,
            operation = operation,
            target = target,
            riskClass = risk,
            decision = decision,
            outcome = outcome
        )
    }
}
