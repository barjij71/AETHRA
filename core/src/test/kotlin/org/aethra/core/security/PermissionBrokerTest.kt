package org.aethra.core.security

import kotlinx.coroutines.runBlocking
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.AuditEntry
import org.aethra.core.storage.Storage
import org.junit.Assert.*
import org.junit.Test

class AuditLogTest {
    @Test
    fun recordAndLoadRoundTrip() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val entry = AuditEntry(
            id = "e1", actor = "test", operation = "op", target = "t1",
            riskClass = RiskClass.SENSITIVE,
            decision = "GRANTED", outcome = "ok"
        )
        log.record(entry)
        val all = log.all()
        assertEquals(1, all.size)
        assertEquals("GRANTED", all.first().decision)
    }
}

/** In-memory stand-in for [Storage]. */
class TestStorage : Storage {
    private val data = mutableMapOf<String, String>()
    override suspend fun getString(key: String, defaultValue: String?): String? = data[key]
    override suspend fun setString(key: String, value: String) { data[key] = value }
    override suspend fun getLong(key: String, defaultValue: Long?): Long? = data[key]?.toLongOrNull()
    override suspend fun setLong(key: String, value: Long) { data[key] = value.toString() }
    override suspend fun getBoolean(key: String, defaultValue: Boolean?): Boolean? = data[key]?.toBooleanStrictOrNull()
    override suspend fun setBoolean(key: String, value: Boolean) { data[key] = value.toString() }
    override suspend fun getAll(): Map<String, String> = data.toMap()
    override suspend fun remove(key: String) { data.remove(key) }
    override suspend fun clear() { data.clear() }
}

class PermissionBrokerTest {
    @Test
    fun narrowActionDoesNotRequireApproval() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val broker = PermissionBroker(log)
        val token = broker.requestApproval("agent", RiskClass.READ_ONLY, "query", "x")
        assertNull(token)
    }

    @Test
    fun sensitiveActionRequiresApproval() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val broker = PermissionBroker(log)
        val token = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "x")
        assertNotNull(token)
        // A freshly issued grant is valid (expires after 5 minutes)
        assertTrue(broker.isGrantValid(token as String))
    }

    @Test
    fun approvalDoesNotLoopAfterValidApproval() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val broker = PermissionBroker(log)

        // First request issues a token (approval required)
        val token1 = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "x")
        assertNotNull(token1)

        // User grants approval
        broker.recordDecision(token1 as String, granted = true, actor = "agent", reason = "user granted")

        // Subsequent request for the same actor/risk/operation/target returns null
        // (already approved) — no infinite approval loop.
        val token2 = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "x")
        assertNull(token2)
    }

    @Test
    fun deniedApprovalDoesNotLoop() = runBlocking {
        val store = TestStorage()
        val log = AuditLog(store)
        val broker = PermissionBroker(log)

        // First request issues a token
        val token1 = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "x")
        assertNotNull(token1)

        // User denies approval
        broker.recordDecision(token1 as String, granted = false, actor = "agent", reason = "user denied")

        // Subsequent request issues a new token (denial does not grant approval)
        val token2 = broker.requestApproval("agent", RiskClass.SENSITIVE, "send", "x")
        assertNotNull(token2)
        assertTrue(token1 as String != token2)
    }
}
