package org.aethra.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aethra.core.domain.RiskClass
import org.aethra.core.domain.TaskState
import org.aethra.core.storage.Storage
import org.aethra.core.storage.StorageException

/**
 * Durable task journal backed by the platform Storage abstraction.
 *
 * The task engine owns the in-memory index; this class owns only the
 * serialization boundary so persistence remains platform-independent.
 */
class TaskPersistence(private val storage: Storage) {
    private val journalKey = "aethra.tasks.v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun saveAll(tasks: List<Task>) {
        val snapshot = tasks.map { task ->
            PersistedTask(
                id = task.id,
                goal = task.goal,
                role = task.role,
                riskClass = task.riskClass.name,
                dependsOn = task.dependsOn,
                createdAt = task.createdAt,
                updatedAt = task.updatedAt,
                state = task.state.name,
                detail = task.detail,
                note = task.note,
                retryBudget = task.retryBudget,
                timeoutMs = task.timeoutMs,
                backoffMs = task.backoffMs,
                isExecuted = task.isExecuted,
                executionAttemptId = task.executionAttemptId,
                attemptCounter = task.attemptCounter,
                retriesUsed = task.retriesUsed,
                // Phase 6: Verification state
                verificationOutcome = task.verificationOutcome.serializedName,
                verificationEvidence = task.verificationEvidence.map { it.encode() },
                verificationPostconditions = task.verificationPostconditions.map {
                    PersistedPostconditionResult(
                        name = it.postcondition.name,
                        description = it.postcondition.description,
                        evidenceType = it.postcondition.evidenceType,
                        expectedValue = it.postcondition.expectedValue,
                        satisfied = it.satisfied,
                        evidenceMissing = it.evidenceMissing
                    )
                },
                // isVerified is now derived from verificationOutcome during restore (tamper-proofing)
                isVerified = task.verificationOutcome == VerificationOutcome.VERIFIED,
                postconditions = task.postconditions.map {
                    PersistedPostcondition(
                        name = it.name,
                        description = it.description,
                        evidenceType = it.evidenceType,
                        expectedValue = it.expectedValue
                    )
                },
                // Phase 9: preserve Skill provenance across the durable journal
                createdAtBy = task.createdAtBy
            )
        }
        storage.setString(journalKey, json.encodeToString(snapshot))
    }

    /**
     * Load all persisted tasks from storage.
     * Throws PersistenceException on corrupt/incompatible data (fail closed).
     * Returns empty list only for legitimately empty storage (EMPTY).
     */
    suspend fun loadAll(): List<Task> {
        return try {
            val encoded = storage.getString(journalKey) ?: return emptyList()
            if (encoded.isBlank()) return emptyList()

            json.decodeFromString<List<PersistedTask>>(encoded).map { snapshot ->
                val task = Task(
                    goal = snapshot.goal,
                    role = snapshot.role,
                    riskClass = RiskClass.valueOf(snapshot.riskClass),
                    dependsOn = snapshot.dependsOn,
                    // Phase 9: restore provenance exactly as persisted
                    createdAtBy = snapshot.createdAtBy
                )
                task.restore(
                    persistedId = snapshot.id,
                    persistedCreatedAt = snapshot.createdAt,
                    persistedUpdatedAt = snapshot.updatedAt,
                    persistedState = TaskState.valueOf(snapshot.state),
                    persistedDetail = snapshot.detail,
                    persistedNote = snapshot.note,
                    persistedRetryBudget = snapshot.retryBudget,
                    persistedTimeoutMs = snapshot.timeoutMs,
                    persistedBackoffMs = snapshot.backoffMs,
                    persistedExecuted = snapshot.isExecuted,
                    persistedExecutionAttemptId = snapshot.executionAttemptId,
                    persistedAttemptCounter = snapshot.attemptCounter,
                    persistedRetriesUsed = snapshot.retriesUsed,
                    // Phase 6: Verification state
                    persistedVerificationOutcome = VerificationOutcome.fromSerialized(snapshot.verificationOutcome),
                    persistedVerificationEvidence = snapshot.verificationEvidence,
                    persistedVerificationPostconditions = snapshot.verificationPostconditions,
                    persistedIsVerified = snapshot.isVerified,
                    persistedPostconditions = snapshot.postconditions.map {
                        Postcondition(
                            name = it.name,
                            description = it.description,
                            evidenceType = it.evidenceType,
                            expectedValue = it.expectedValue
                        )
                    },
                    // Phase 9: provenance survives restore/recovery
                    persistedCreatedAtBy = snapshot.createdAtBy
                )
                task
            }
        } catch (e: StorageException.Corrupt) {
            throw PersistenceException.Corrupt("Task journal corrupt: ${e.message}", e)
        } catch (e: StorageException.Incompatible) {
            throw PersistenceException.Incompatible("Task journal incompatible: ${e.message}", e)
        } catch (e: StorageException.Partial) {
            throw PersistenceException.Partial("Task journal partial: ${e.message}", e)
        } catch (e: StorageException.IoFailure) {
            throw PersistenceException.IoFailure("Task journal I/O failure: ${e.message}", e)
        } catch (e: Exception) {
            // Phase 3: Fail closed on CORRUPT/PARTIAL/INCOMPATIBLE data
            // Do not silently return emptyList() - that would mask data corruption
            throw PersistenceException("Failed to load task journal: ${e.message}", e)
        }
    }
}

/** Base exception for persistence failures. */
open class PersistenceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Corrupt(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class Incompatible(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class Partial(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
    class IoFailure(message: String, cause: Throwable? = null) : PersistenceException(message, cause)
}

@Serializable
private data class PersistedTask(
    val id: String,
    val goal: String,
    val role: String,
    val riskClass: String,
    val dependsOn: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val detail: Map<String, String>,
    val note: String,
    val retryBudget: Int,
    val timeoutMs: Long,
    val backoffMs: Long,
    val isExecuted: Boolean,
    val executionAttemptId: String = "",
    val attemptCounter: Int = 1,
    val retriesUsed: Int = 0,
    // Phase 6: Verification state
    val verificationOutcome: String = VerificationOutcome.UNKNOWN.serializedName,
    val verificationEvidence: List<String> = emptyList(),
    val verificationPostconditions: List<PersistedPostconditionResult> = emptyList(),
    val isVerified: Boolean = false,
    val postconditions: List<PersistedPostcondition> = emptyList(),
    /**
     * Phase 9: provenance of the task creator (e.g. a Skill id).
     * Defaults to "core" so pre-Phase-9 journals restore without provenance.
     */
    val createdAtBy: String = "core"
)

/** Durable form of a Core-declared [Postcondition]. */
@Serializable
data class PersistedPostcondition(
    val name: String,
    val description: String,
    val evidenceType: String,
    val expectedValue: String? = null
)

/** Durable form of a [PostconditionResult]. */
@Serializable
data class PersistedPostconditionResult(
    val name: String,
    val description: String,
    val evidenceType: String,
    val expectedValue: String? = null,
    val satisfied: Boolean,
    val evidenceMissing: Boolean
)
