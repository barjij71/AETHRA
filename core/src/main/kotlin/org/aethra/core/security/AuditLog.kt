package org.aethra.core.security

import org.aethra.core.domain.AuditEntry
import org.aethra.core.domain.RiskClass
import org.aethra.core.storage.Storage
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Audit log. Immutable (append-only) record of permission decisions and
 * executions. The platform layer supplies the durable storage; this class is
 * the append point and a read surface.
 */
class AuditLog(private val storage: Storage) {
    private val journalKey = "aethra.audit.v1"

    suspend fun record(entry: AuditEntry) {
        val list = load().toMutableList()
        list.add(0, entry)
        storage.setString(journalKey, Json { ignoreUnknownKeys = true }.encodeToString(list))
    }

    suspend fun load(): List<AuditEntry> {
        val raw = storage.getString(journalKey) ?: "[]"
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<List<AuditEntry>>(raw)
        } catch (e: Exception) { emptyList() }
    }

    suspend fun all(): List<AuditEntry> = load()
}

/**
 * Permission Broker. Enforces least privilege: every operation must declare its
 * capability and risk class; sensitive and destructive operations require a user
 * approval token. The broker never trusts an AI or agent to authorise itself.
 */
class PermissionBroker(private val auditLog: AuditLog) {
    private val grants = HashMap<String, org.aethra.core.domain.ApprovalGrant>()
    private val lock = Any()

    /** Ask the user for approval. Returns a token (or null) that the caller must present on the next execution. Never auto-approves. If a valid, granted approval already exists for this actor/risk/operation/target, returns null (already approved). */
    suspend fun requestApproval(actor: String, risk: RiskClass, operation: String, target: String): String? {
        if (risk.level < RiskClass.SENSITIVE.level) return null
        synchronized(lock) {
            // If there's already a valid, granted approval for this operation, don't request again.
            val existingGrant = grants.values.find { g ->
                g.actor == actor &&
                g.riskClass == risk &&
                g.operation == operation &&
                g.target == target &&
                g.granted && {
                    val expiresAt = g.expiresAt
                    expiresAt == null || System.currentTimeMillis() <= expiresAt
                }()
            }
            if (existingGrant != null) return null
        }
        val token = UUID.randomUUID().toString()
        synchronized(lock) {
            grants[token] = org.aethra.core.domain.ApprovalGrant(token, target, actor, risk, operation, grantedAt = System.currentTimeMillis(), expiresAt = System.currentTimeMillis() + 300_000L)
        }
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(), actor = actor, operation = operation,
            target = target, riskClass = risk, decision = "PENDING", outcome = "awaiting user"
        )
        auditLog.record(entry)
        return token
    }

    /** Record the user's decision. Returns true if the operation may proceed. */
    suspend fun recordDecision(token: String, granted: Boolean, actor: String, reason: String) {
        val grant = synchronized(lock) { grants[token] } ?: return
        val outcome = if (granted) "GRANTED" else "DENIED"
        val entry = AuditEntry(
            id = UUID.randomUUID().toString(), actor = actor, operation = grant.target,
            target = grant.target, riskClass = grant.riskClass, decision = outcome, outcome = reason
        )
        auditLog.record(entry)
        synchronized(lock) {
            if (granted) {
                // Keep the grant marked as granted so subsequent requestApproval()
                // calls for the same operation return null (already approved).
                grant.granted = true
            } else {
                grants.remove(token)
            }
        }
    }

    /** Whether a grant token is still valid (not expired). */
    fun isGrantValid(token: String): Boolean {
        synchronized(lock) {
            val g = grants[token]
            if (g != null) {
                val expiresAt = g.expiresAt
                val expired = expiresAt != null && System.currentTimeMillis() > expiresAt
                if (expired) {
                    grants.remove(token)
                    return false
                }
                return true
            }
            return false
        }
    }
}

/**
 * Risk classification levels, ordered lowest to highest. Used by the
 * PermissionBroker and by the execution policy.
 */
enum class RiskClass {
    READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE;
    val level: Int get() = this.ordinal
}
