package org.aethra.core.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException
import org.aethra.core.task.PersistenceException

/** Complete durable snapshot for bounded review/repair loop state and evidence. */
internal class ReviewRepairLoopPersistence(private val storage: Storage) {
    private val key = "aethra.review-repair-loops.v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun saveAll(loops: List<ReviewRepairLoop>) {
        val snapshot = PersistedLoopRegistry(
            schemaVersion = SCHEMA_VERSION,
            loops = loops.map { it.toPersisted() }
        )
        try {
            storage.setString(key, json.encodeToString(snapshot))
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Review/repair loop write failed: ${e.message}", e)
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Review/repair loop storage is corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Review/repair loop storage is incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Review/repair loop storage is partial: ${e.message}", e)
        }
    }

    suspend fun loadAll(): List<ReviewRepairLoop> {
        val raw = try {
            storage.getString(key)
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Review/repair loop read failed: ${e.message}", e)
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Review/repair loop storage is corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Review/repair loop storage is incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Review/repair loop storage is partial: ${e.message}", e)
        }
        if (raw == null || raw.isBlank()) return emptyList()

        try {
            val registry = json.decodeFromString<PersistedLoopRegistry>(raw)
            if (registry.schemaVersion != SCHEMA_VERSION) {
                throw PersistenceException.Incompatible(
                    "Review/repair loop schema ${registry.schemaVersion} is unsupported"
                )
            }
            val loops = registry.loops.map { it.toLoop().requireConsistentHistory() }
            if (loops.map { it.id }.toSet().size != loops.size) {
                throw PersistenceException.Corrupt("Review/repair loop journal contains duplicate IDs")
            }
            return loops
        } catch (e: PersistenceException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            throw PersistenceException.Corrupt("Review/repair loop journal could not be decoded", e)
        } catch (e: Exception) {
            throw PersistenceException.Corrupt("Review/repair loop journal is invalid", e)
        }
    }

    private fun ReviewRepairLoop.toPersisted() = PersistedLoop(
        id = id,
        workflowId = workflowId,
        agentId = agentId,
        maxRepairCycles = maxRepairCycles,
        repairCyclesUsed = repairCyclesUsed,
        state = state.name,
        evidence = evidence.map {
            PersistedEvidence(
                loopId = it.loopId,
                cycleNumber = it.cycleNumber,
                stage = it.stage.name,
                outcome = it.outcome.name,
                summary = it.summary,
                taskId = it.taskId,
                agentId = it.agentId,
                recordedAt = it.recordedAt
            )
        },
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )

    private fun PersistedLoop.toLoop() = ReviewRepairLoop(
        id = id,
        workflowId = workflowId,
        agentId = agentId,
        maxRepairCycles = maxRepairCycles,
        repairCyclesUsed = repairCyclesUsed,
        state = ReviewRepairLoopState.valueOf(state),
        evidence = evidence.map {
            ReviewRepairEvidence(
                loopId = it.loopId,
                cycleNumber = it.cycleNumber,
                stage = ReviewRepairStage.valueOf(it.stage),
                outcome = ReviewRepairOutcome.valueOf(it.outcome),
                summary = it.summary,
                taskId = it.taskId,
                agentId = it.agentId,
                recordedAt = it.recordedAt
            )
        },
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt
    )

    @Serializable
    private data class PersistedLoopRegistry(
        val schemaVersion: Int,
        val loops: List<PersistedLoop>
    )

    @Serializable
    private data class PersistedLoop(
        val id: String,
        val workflowId: String? = null,
        val agentId: String? = null,
        val maxRepairCycles: Int,
        val repairCyclesUsed: Int,
        val state: String,
        val evidence: List<PersistedEvidence>,
        val createdAt: Long,
        val updatedAt: Long,
        val completedAt: Long? = null
    )

    @Serializable
    private data class PersistedEvidence(
        val loopId: String,
        val cycleNumber: Int,
        val stage: String,
        val outcome: String,
        val summary: String,
        val taskId: String? = null,
        val agentId: String? = null,
        val recordedAt: Long
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
